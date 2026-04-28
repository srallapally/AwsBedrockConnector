// src/main/java/org/forgerock/openicf/connectors/awsbedrock/operations/AwsBedrockCrudService.java
package org.forgerock.openicf.connectors.awsbedrock.operations;

import org.forgerock.openicf.connectors.awsbedrock.AwsBedrockConnection;
import org.forgerock.openicf.connectors.awsbedrock.client.AwsBedrockClient;
import org.forgerock.openicf.connectors.awsbedrock.utils.AwsBedrockConstants;
import org.forgerock.openicf.connectors.awsbedrock.utils.AwsBedrockUtils;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.objects.*;
import org.identityconnectors.framework.common.objects.filter.Filter;

import software.amazon.awssdk.services.bedrockagent.model.*;
import software.amazon.awssdk.services.bedrockagent.model.S3Identifier;
import software.amazon.awssdk.services.bedrock.model.GetGuardrailResponse;
import software.amazon.awssdk.services.bedrock.model.BedrockException;
import software.amazon.awssdk.services.iam.model.IamException;
import software.amazon.awssdk.services.iam.model.Role;

import static org.forgerock.openicf.connectors.awsbedrock.utils.AwsBedrockConstants.*;
import static org.forgerock.openicf.connectors.awsbedrock.utils.AwsBedrockUtils.*;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Read-only CRUDQ helper for the AWS Bedrock connector.
 *
 * This service is responsible for:
 * - Listing and mapping Bedrock Agents, Guardrails, Tools (Action Groups)
 *   into ConnectorObject instances.
 * - Providing basic Get-style lookups for each object class.
 *
 * Create/Update/Delete semantics are intentionally not implemented here;
 * they will be rejected at the connector level for a read-only v1.
 */
public class AwsBedrockCrudService {

    private static final Log LOG = Log.getLog(AwsBedrockCrudService.class);
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private final AwsBedrockConnection connection;

    // key -> list of bindings
    private volatile Map<String, List<AgentIdentityBinding>> bindingsByKey = new ConcurrentHashMap<>();
    private volatile Instant bindingsLoadedAt = Instant.EPOCH;
    private static final String WILDCARD_KEY = "WILDCARD";

    // OPENICF-431: Tool credentials cache — id -> record, agentId -> list of ids
    private volatile Map<String, JsonNode> toolCredentialsById = new ConcurrentHashMap<>();
    private volatile Map<String, List<String>> toolCredentialIdsByAgent = new ConcurrentHashMap<>();
    private volatile Instant toolCredentialsLoadedAt = Instant.EPOCH;

    public AwsBedrockCrudService(AwsBedrockConnection connection) {
        this.connection = connection;
    }

    private AwsBedrockClient client() {
        return connection.getClient();
    }
    private S3Client s3Client() {
        return connection.getS3Client();
    }
    // =================================================================
    // Search helpers
    // =================================================================
    private static String agentKey(String agentId) {
        return "AGENT#" + agentId;
    }

    private static String aliasKey(String agentId, String aliasId) {
        return "ALIAS#" + agentId + "/" + aliasId;
    }

    // OPENICF-424: Rewritten to emit one ConnectorObject per alias (alias-level identity model).
    // Agents with zero aliases emit a single bare-agent ConnectorObject.
    /**
     * Search for agents and stream them to the handler.
     * Current implementation ignores complex filters and returns all agents.
     */
    public void searchAgents(ObjectClass objectClass,
                             Filter query,
                             ResultsHandler handler,
                             OperationOptions options) {

        AwsBedrockClient client = client();
        List<AgentSummary> summaries = client.listAgents();

        for (AgentSummary summary : summaries) {
            Agent agent = client.getAgent(summary.agentId());

            // OPENICF-424: List aliases for this agent
            List<AgentAliasSummary> aliases;
            try {
                aliases = client.listAgentAliases(agent.agentId());
            } catch (BedrockAgentException e) {
                LOG.warn(e, "Failed to list aliases for agent {0}", agent.agentId());
                aliases = Collections.emptyList();
            }

            if (aliases != null && !aliases.isEmpty()) {
                // OPENICF-424: Emit one ConnectorObject per alias
                for (AgentAliasSummary alias : aliases) {
                    ConnectorObject obj = toAgentAliasConnectorObject(objectClass, agent, alias);
                    if (obj == null) {
                        continue;
                    }
                    if (query != null && !query.accept(obj)) {
                        continue;
                    }
                    if (!handler.handle(obj)) {
                        LOG.ok("Handler requested to stop processing agents.");
                        return;
                    }
                }
            } else {
                // OPENICF-424: Bare agent (no aliases) — emit single ConnectorObject
                ConnectorObject obj = toBareAgentConnectorObject(objectClass, agent);
                if (obj == null) {
                    continue;
                }
                if (query != null && !query.accept(obj)) {
                    continue;
                }
                if (!handler.handle(obj)) {
                    LOG.ok("Handler requested to stop processing agents.");
                    break;
                }
            }
        }
    }

    /**
     * Search for guardrails associated with agents.
     * One ConnectorObject per (agent, guardrail) pair.
     */
    public void searchGuardrails(ObjectClass objectClass,
                                 Filter query,
                                 ResultsHandler handler,
                                 OperationOptions options) {

        AwsBedrockClient client = client();

        // First list all agents, because guardrail configuration hangs off the agent
        List<AgentSummary> summaries = client.listAgents();

        for (AgentSummary summary : summaries) {
            String agentId = summary.agentId();
            // Fetch full agent to get guardrailConfiguration
            Agent agent;
            try {
                agent = client.getAgent(agentId);
            } catch (BedrockAgentException e) {
                LOG.warn(e, "Failed to retrieve agent {0} while searching guardrails", agentId);
                continue;
            }

            GuardrailConfiguration guardrailConfig = agent.guardrailConfiguration();
            if (guardrailConfig == null
                    || guardrailConfig.guardrailIdentifier() == null
                    || guardrailConfig.guardrailIdentifier().isEmpty()) {
                // This agent has no guardrail attached; skip
                continue;
            }

            // Now enrich from Bedrock control plane
            GetGuardrailResponse guardrailDetails = null;
            try {
                guardrailDetails = client.getGuardrail(
                        guardrailConfig.guardrailIdentifier(),
                        guardrailConfig.guardrailVersion());
            } catch (BedrockException e) {
                LOG.warn(e,
                        "Failed to retrieve guardrail details for identifier {0}, version {1}",
                        guardrailConfig.guardrailIdentifier(), guardrailConfig.guardrailVersion());
                // We still return a basic guardrail object; just without extra details
            }

            ConnectorObject obj = toGuardrailConnectorObject(
                    objectClass, agent, guardrailConfig, guardrailDetails);
            if (obj == null) {
                continue;
            }

            // Apply ICF filter if present
            if (query != null && !query.accept(obj)) {
                continue;
            }

            // Stream to caller; respect handler's request to stop
            if (!handler.handle(obj)) {
                LOG.ok("Handler requested to stop processing guardrails.");
                return;
            }
        }
    }


    public void searchKnowledgeBases(ObjectClass objectClass,
                                     Filter query,
                                     ResultsHandler handler,
                                     OperationOptions options) {

        AwsBedrockClient client = client();
        List<AgentSummary> summaries = client.listAgents();

        for (AgentSummary summary : summaries) {
            String agentId = summary.agentId();

            // Resolve agent version (explicit if available, otherwise "DRAFT")
            Agent agent = client.getAgent(agentId);
            String agentVersion = agent.agentVersion() != null ? agent.agentVersion() : "DRAFT";

            List<AgentKnowledgeBaseSummary> kbs =
                    client.listAgentKnowledgeBases(agentId, agentVersion);

            for (AgentKnowledgeBaseSummary kb : kbs) {
                ConnectorObject obj =
                        toKnowledgeBaseConnectorObject(objectClass, agentId, agentVersion, kb);
                if (obj == null) {
                    continue;
                }

                if (query != null && !query.accept(obj)) {
                    continue;
                }

                if (!handler.handle(obj)) {
                    LOG.ok("Handler requested to stop processing knowledge bases.");
                    return;
                }
            }
        }
    }

    /**
     * Search for tools (Action Groups) associated with agents.
     * One ConnectorObject per (agent, actionGroup).
     */
    public void searchTools(ObjectClass objectClass,
                            Filter query,
                            ResultsHandler handler,
                            OperationOptions options) {

        AwsBedrockClient client = client();
        List<AgentSummary> summaries = client.listAgents();

        for (AgentSummary summary : summaries) {
            String agentId = summary.agentId();
            // Prefer explicit agent.version if present; fall back to "DRAFT".
            Agent agent = client.getAgent(agentId);
            String agentVersion = agent.agentVersion() != null ? agent.agentVersion() : "DRAFT";

            List<ActionGroupSummary> actionGroups =
                    client.listAgentActionGroups(agentId, agentVersion);

            for (ActionGroupSummary group : actionGroups) {
                ConnectorObject obj = toToolConnectorObject(objectClass, agentId, agentVersion, group);
                if (obj == null) {
                    continue;
                }

                if (query != null && !query.accept(obj)) {
                    continue;
                }

                if (!handler.handle(obj)) {
                    LOG.ok("Handler requested to stop processing tools.");
                    return;
                }
            }
        }
    }

    /**
     * Search for identity bindings (Agent ↔ Principal edges).
     *
     * Currently this uses a helper that is wired for future Bedrock policy
     * retrieval; until that is implemented, no bindings will be returned.
     */
    public void searchIdentityBindings(ObjectClass objectClass,
                                       Filter query,
                                       ResultsHandler handler,
                                       OperationOptions options) {

        AwsBedrockClient client = client();
        List<AgentSummary> summaries = client.listAgents();

        for (AgentSummary summary : summaries) {
            // OPENICF-426: null aliasId → all bindings for this agent
            List<AgentIdentityBinding> bindings =
                    listIdentityBindingsForAgent(client, summary.agentId(), null);

            for (AgentIdentityBinding binding : bindings) {
                // OPENICF-427: Skip wildcard bindings here — emitted once below to avoid
                // duplicate UIDs (one wildcard binding would otherwise appear once per agent).
                if ("*".equals(binding.agentId)) {
                    continue;
                }

                ConnectorObject obj = toIdentityBindingConnectorObject(objectClass, binding);
                if (obj == null) {
                    continue;
                }

                if (query != null && !query.accept(obj)) {
                    continue;
                }

                if (!handler.handle(obj)) {
                    LOG.ok("Handler requested to stop processing identity bindings.");
                    return;
                }
            }
        }

        // OPENICF-427: Emit wildcard bindings exactly once — not scoped to any specific
        // agent, so must not be repeated per agent.
        Map<String, List<AgentIdentityBinding>> cache = getBindingsCache();
        List<AgentIdentityBinding> wildcardBindings = cache.get(WILDCARD_KEY);
        if (wildcardBindings != null) {
            for (AgentIdentityBinding binding : wildcardBindings) {
                ConnectorObject obj = toIdentityBindingConnectorObject(objectClass, binding);
                if (obj == null) {
                    continue;
                }
                if (query != null && !query.accept(obj)) {
                    continue;
                }
                if (!handler.handle(obj)) {
                    LOG.ok("Handler requested to stop processing identity bindings.");
                    return;
                }
            }
        }
    }

    // OPENICF-431: Search all agentToolCredentials records from the S3 cache.
    public void searchToolCredentials(ObjectClass objectClass,
                                      Filter query,
                                      ResultsHandler handler,
                                      OperationOptions options) {
        Map<String, JsonNode> cache = getToolCredentialsCache();
        for (JsonNode record : cache.values()) {
            ConnectorObject obj = toToolCredentialConnectorObject(objectClass, record);
            if (obj == null) {
                continue;
            }
            if (query != null && !query.accept(obj)) {
                continue;
            }
            if (!handler.handle(obj)) {
                LOG.ok("Handler requested to stop processing tool credentials.");
                return;
            }
        }
    }

    // =================================================================
    // Get helpers
    // =================================================================

    // OPENICF-424: Rewritten to handle both alias UIDs (agentId:aliasId) and bare agent UIDs.
    public ConnectorObject getAgent(ObjectClass objectClass,
                                    Uid uid,
                                    OperationOptions options) {
        AwsBedrockClient client = client();
        String uidValue = uid.getUidValue();

        if (isAliasUid(uidValue)) {
            // OPENICF-424: Alias-level GET
            AwsBedrockUtils.AgentAliasKey key = fromAgentAliasUid(uidValue);
            Agent agent;
            try {
                agent = client.getAgent(key.agentId());
            } catch (BedrockAgentException e) {
                LOG.info(e, "Agent not found for alias UID {0}", uidValue);
                return null;
            }
            AgentAlias alias;
            try {
                alias = client.getAgentAlias(key.agentId(), key.aliasId());
            } catch (BedrockAgentException e) {
                LOG.info(e, "Alias not found for UID {0}", uidValue);
                return null;
            }
            // Build a summary from the full alias for the mapping method
            AgentAliasSummary summary = AgentAliasSummary.builder()
                    .agentAliasId(alias.agentAliasId())
                    .agentAliasName(alias.agentAliasName())
                    .agentAliasStatus(alias.agentAliasStatus())
                    .createdAt(alias.createdAt())
                    .updatedAt(alias.updatedAt())
                    .description(alias.description())
                    .build();
            return toAgentAliasConnectorObject(objectClass, agent, summary);
        } else {
            // Bare agent GET (no alias)
            AwsBedrockUtils.AgentKey key = fromAgentUid(uidValue);
            Agent agent;
            try {
                agent = client.getAgent(key.agentId());
            } catch (BedrockAgentException e) {
                LOG.info(e, "Agent not found for UID {0}", uidValue);
                return null;
            }
            return toBareAgentConnectorObject(objectClass, agent);
        }
    }

    public ConnectorObject getGuardrail(ObjectClass objectClass,
                                        Uid uid,
                                        OperationOptions options) {
        AwsBedrockClient client = client();
        GuardrailKey key = fromGuardrailUid(uid.getUidValue());

        Agent agent;
        try {
            agent = client.getAgent(key.agentId());
        } catch (BedrockAgentException e) {
            LOG.info(e, "Agent not found when resolving guardrail UID {0}", uid.getUidValue());
            return null;
        }

        GuardrailConfiguration cfg = agent.guardrailConfiguration();
        if (cfg == null) {
            return null;
        }

        // Basic consistency check on ID/version
        if (!key.guardrailId().equals(cfg.guardrailIdentifier())
                || !key.guardrailVersion().equals(cfg.guardrailVersion())) {
            LOG.ok("Guardrail configuration for agent {0} does not match UID {1}",
                    key.agentId(), uid.getUidValue());
            return null;
        }

        return toGuardrailConnectorObject(objectClass, agent, cfg);
    }

    public ConnectorObject getTool(ObjectClass objectClass,
                                   Uid uid,
                                   OperationOptions options) {
        AwsBedrockClient client = client();
        ToolKey key = fromToolUid(uid.getUidValue());

        Agent agent;
        try {
            agent = client.getAgent(key.agentId());
        } catch (BedrockAgentException e) {
            LOG.info(e, "Agent not found when resolving tool UID {0}", uid.getUidValue());
            return null;
        }
        String agentVersion = agent.agentVersion() != null ? agent.agentVersion() : "DRAFT";

        List<ActionGroupSummary> groups =
                client.listAgentActionGroups(key.agentId(), agentVersion);

        for (ActionGroupSummary group : groups) {
            if (key.actionGroupId().equals(group.actionGroupId())) {
                return toToolConnectorObject(objectClass, key.agentId(), agentVersion, group);
            }
        }

        return null;
    }

    public ConnectorObject getKnowledgeBase(ObjectClass objectClass,
                                            Uid uid,
                                            OperationOptions options) {

        KnowledgeBaseKey key = fromKnowledgeBaseUid(uid.getUidValue());
        AwsBedrockClient client = client();

        Agent agent;
        try {
            agent = client.getAgent(key.agentId());
        } catch (BedrockAgentException e) {
            LOG.ok(e, "Failed to get agent {0} for knowledge base UID {1}",
                    key.agentId(), uid.getUidValue());
            return null;
        }

        if (agent == null) {
            LOG.ok("No agent found for knowledge base UID {0}", uid.getUidValue());
            return null;
        }

        String agentVersion = agent.agentVersion() != null ? agent.agentVersion() : "DRAFT";

        List<AgentKnowledgeBaseSummary> kbs =
                client.listAgentKnowledgeBases(key.agentId(), agentVersion);

        for (AgentKnowledgeBaseSummary kb : kbs) {
            if (key.knowledgeBaseId().equals(kb.knowledgeBaseId())) {
                return toKnowledgeBaseConnectorObject(objectClass, key.agentId(), agentVersion, kb);
            }
        }

        LOG.ok("No knowledge base {0} found for agent {1}",
                key.knowledgeBaseId(), key.agentId());
        return null;
    }

    public ConnectorObject getIdentityBinding(ObjectClass objectClass,
                                              Uid uid,
                                              OperationOptions options) {

        AwsBedrockUtils.IdentityBindingKey key =
                AwsBedrockUtils.fromIdentityBindingUid(uid.getUidValue());

        AwsBedrockClient client = client();

        // OPENICF-426: null aliasId → search all bindings for this agent
        List<AgentIdentityBinding> bindings =
                listIdentityBindingsForAgent(client, key.agentId(), null);

        for (AgentIdentityBinding binding : bindings) {
            if (binding.scope.equals(key.scope())
                    && binding.principalArn.equals(key.principalArn())) {
                return toIdentityBindingConnectorObject(objectClass, binding);
            }
        }

        LOG.ok("No identity binding found for UID {0}", uid.getUidValue());
        return null;
    }

    // OPENICF-431: GET by UID for agentToolCredentials — UID is the tc-* id field.
    public ConnectorObject getToolCredential(ObjectClass objectClass,
                                             Uid uid,
                                             OperationOptions options) {
        Map<String, JsonNode> cache = getToolCredentialsCache();
        JsonNode record = cache.get(uid.getUidValue());
        if (record == null) {
            LOG.ok("No tool credential found for UID {0}", uid.getUidValue());
            return null;
        }
        return toToolCredentialConnectorObject(objectClass, record);
    }

    // =================================================================
    // Mapping helpers
    // =================================================================

    // OPENICF-424: Common agent attribute mapping shared by both bare-agent and alias paths.
    // OPENICF-426: Added aliasId parameter to scope identity binding lookup.
    //   aliasId == null → all bindings (bare agent rollup view)
    //   aliasId != null → agent-level bindings + this alias's bindings only
    // OPENICF-431: Populates toolCredentialIds from tool credentials cache.
    // Sets all agent-level attributes on the builder. Does NOT set UID, NAME, or alias attributes.
    private void buildCommonAgentAttributes(ConnectorObjectBuilder b, Agent agent, String aliasId) {
        String agentId = agent.agentId();

        b.addAttribute(AttributeBuilder.build(ATTR_PLATFORM, AwsBedrockConstants.CONNECTOR_NAME));
        b.addAttribute(AttributeBuilder.build(ATTR_AGENT_ID, agentId));
        b.addAttribute(AttributeBuilder.build(ATTR_AGENT_NAME, agent.agentName()));
        b.addAttribute(AttributeBuilder.build(ATTR_VERSION, agent.agentVersion()));
        if (agent.agentStatus() != null) {
            b.addAttribute(AttributeBuilder.build(ATTR_STATUS, agent.agentStatusAsString()));
        }
        b.addAttribute(AttributeBuilder.build(ATTR_DESCRIPTION, agent.description()));
        b.addAttribute(AttributeBuilder.build(ATTR_FOUNDATION_MODEL, agent.foundationModel()));
        b.addAttribute(AttributeBuilder.build(ATTR_ROLE_ARN, agent.agentResourceRoleArn()));

        if (agent.idleSessionTTLInSeconds() != null) {
            b.addAttribute(AttributeBuilder.build(
                    ATTR_IDLE_TTL,
                    agent.idleSessionTTLInSeconds().intValue()
            ));
        }

        if (agent.createdAt() != null) {
            b.addAttribute(AttributeBuilder.build(ATTR_CREATED_AT, agent.createdAt().toString()));
        }
        if (agent.updatedAt() != null) {
            b.addAttribute(AttributeBuilder.build(ATTR_UPDATED_AT, agent.updatedAt().toString()));
        }

        String agentArn = String.format("arn:aws:bedrock:%s:%s:agent/%s",
                client().getRegion(),
                client().getAccountId(),
                agentId);
        b.addAttribute(AttributeBuilder.build(ATTR_AGENT_ARN, agentArn));

        // OPENICF-424: Region stored per-object
        b.addAttribute(AttributeBuilder.build(ATTR_REGION, client().getRegion()));

        // Add customerEncryptionKeyArn if present
        if (agent.customerEncryptionKeyArn() != null && !agent.customerEncryptionKeyArn().isEmpty()) {
            b.addAttribute(AttributeBuilder.build(
                    ATTR_CUSTOMER_ENCRYPTION_KEY_ARN,
                    agent.customerEncryptionKeyArn()));
        }

        // Add failureReasons if present
        if (agent.failureReasons() != null && !agent.failureReasons().isEmpty()) {
            b.addAttribute(AttributeBuilder.build(
                    ATTR_FAILURE_REASONS,
                    agent.failureReasons().toArray(new String[0])));
        }

        // Add recommendedActions if present
        if (agent.recommendedActions() != null && !agent.recommendedActions().isEmpty()) {
            b.addAttribute(AttributeBuilder.build(
                    ATTR_RECOMMENDED_ACTIONS,
                    agent.recommendedActions().toArray(new String[0])));
        }

        // Add preparedAt if present
        if (agent.preparedAt() != null) {
            b.addAttribute(AttributeBuilder.build(ATTR_PREPARED_AT, agent.preparedAt().toString()));
        }

        // Guardrail summary (ID/version only for now)
        GuardrailConfiguration cfg = agent.guardrailConfiguration();
        if (cfg != null) {
            if (cfg.guardrailIdentifier() != null) {
                b.addAttribute(AttributeBuilder.build(ATTR_GUARDRAIL_ID, cfg.guardrailIdentifier()));
            }
            if (cfg.guardrailVersion() != null) {
                b.addAttribute(AttributeBuilder.build(ATTR_GUARDRAIL_VERSION, cfg.guardrailVersion()));
            }
        }

        // Tools (Action Group IDs only; details are exposed via agentTool object class)
        String agentVersion = agent.agentVersion() != null ? agent.agentVersion() : "DRAFT";
        List<String> toolIds = new ArrayList<>();
        try {
            List<ActionGroupSummary> groups =
                    client().listAgentActionGroups(agentId, agentVersion);
            for (ActionGroupSummary g : groups) {
                toolIds.add(g.actionGroupId());
            }
        } catch (Exception e) {
            LOG.warn(e, "Failed to list action groups for agent {0}", agentId);
        }
        if (!toolIds.isEmpty()) {
            b.addAttribute(AttributeBuilder.build(ATTR_TOOLS, toolIds));
        }

        // Knowledge bases (KB IDs only; details exposed via DataCapability/analytics)
        List<String> kbIds = new ArrayList<>();
        try {
            List<AgentKnowledgeBaseSummary> kbs =
                    client().listAgentKnowledgeBases(agentId, agentVersion);
            for (AgentKnowledgeBaseSummary kb : kbs) {
                kbIds.add(kb.knowledgeBaseId());
            }
        } catch (Exception e) {
            LOG.warn(e, "Failed to list knowledge bases for agent {0}", agentId);
        }
        if (!kbIds.isEmpty()) {
            b.addAttribute(AttributeBuilder.build(ATTR_KNOWLEDGE_BASES, kbIds));
        }

        // OPENICF-424: agentCollaboration
        if (agent.agentCollaboration() != null) {
            b.addAttribute(AttributeBuilder.build(
                    ATTR_AGENT_COLLABORATION,
                    agent.agentCollaborationAsString()));
        }

        // OPENICF-424: connectedAgents (collaborator agent IDs)
        List<String> collaboratorIds = getConnectedAgentIds(agentId, agentVersion);
        if (!collaboratorIds.isEmpty()) {
            b.addAttribute(AttributeBuilder.build(
                    ATTR_CONNECTED_AGENTS,
                    collaboratorIds));
        }

        // OPENICF-426: Scope identity bindings to the specific alias (or all for bare agents).
        long start = System.currentTimeMillis();
        try {
            List<AgentIdentityBinding> bindings =
                    listIdentityBindingsForAgent(client(), agentId, aliasId);

            Set<String> principals = new LinkedHashSet<>();
            for (AgentIdentityBinding binding : bindings) {
                String packed = packPrincipal(
                        binding.principalType,
                        binding.accountId,
                        binding.principalArn);
                principals.add(packed);
            }

            if (!principals.isEmpty()) {
                b.addAttribute(AttributeBuilder.build(
                        ATTR_AGENT_PRINCIPALS,
                        principals.toArray(new String[0])));
            }
        } catch (Exception e) {
            LOG.warn(e, "Failed to compute agentPrincipals for agent {0}", agentId);
        }
        long elapsed = System.currentTimeMillis() - start;
        LOG.info("Computed identity bindings for agent {0} in {1} ms", agentId, elapsed);

        // OPENICF-431: Forward pointer — tool credential IDs for this agent.
        try {
            getToolCredentialsCache(); // ensure cache is warm
            List<String> credIds = toolCredentialIdsByAgent.get(agentId);
            if (credIds != null && !credIds.isEmpty()) {
                b.addAttribute(AttributeBuilder.build(
                        ATTR_TOOL_CREDENTIAL_IDS,
                        credIds.toArray(new String[0])));
            }
        } catch (Exception e) {
            LOG.warn(e, "Failed to compute toolCredentialIds for agent {0}", agentId);
        }
    }

    // OPENICF-424: Renamed from toAgentConnectorObject. Bare agent (no alias) path.
    private ConnectorObject toBareAgentConnectorObject(ObjectClass objectClass, Agent agent) {
        if (agent == null) {
            return null;
        }

        ConnectorObjectBuilder b = new ConnectorObjectBuilder();
        b.setObjectClass(objectClass);

        String agentId = agent.agentId();
        b.setUid(new Uid(toAgentUid(agentId)));
        b.setName(new Name(agent.agentName()));

        // OPENICF-426: null aliasId → all bindings (rollup view for bare agent)
        buildCommonAgentAttributes(b, agent, null);

        return b.build();
    }

    // OPENICF-424: New method. One ConnectorObject per agent alias.
    private ConnectorObject toAgentAliasConnectorObject(ObjectClass objectClass,
                                                        Agent agent,
                                                        AgentAliasSummary alias) {
        if (agent == null || alias == null || alias.agentAliasId() == null) {
            return null;
        }

        ConnectorObjectBuilder b = new ConnectorObjectBuilder();
        b.setObjectClass(objectClass);

        String agentId = agent.agentId();
        String aliasId = alias.agentAliasId();

        // OPENICF-424: UID = agentId:aliasId
        b.setUid(new Uid(toAgentAliasUid(agentId, aliasId)));
        // OPENICF-424: __NAME__ = agentName / aliasName
        String aliasName = alias.agentAliasName() != null ? alias.agentAliasName() : aliasId;
        b.setName(new Name(agent.agentName() + " / " + aliasName));

        // OPENICF-426: pass aliasId so bindings are scoped to this alias only
        buildCommonAgentAttributes(b, agent, aliasId);

        // OPENICF-424: Alias-specific attributes
        b.addAttribute(AttributeBuilder.build(ATTR_ALIAS_ID, aliasId));
        b.addAttribute(AttributeBuilder.build(ATTR_ALIAS_NAME, aliasName));
        if (alias.agentAliasStatus() != null) {
            b.addAttribute(AttributeBuilder.build(
                    ATTR_ALIAS_STATUS,
                    alias.agentAliasStatusAsString()));
        }

        return b.build();
    }

    // OPENICF-424: Helper to fetch collaborator agent IDs for connectedAgents attribute.
    private List<String> getConnectedAgentIds(String agentId, String agentVersion) {
        List<String> ids = new ArrayList<>();
        try {
            List<AgentCollaboratorSummary> collaborators =
                    client().listAgentCollaborators(agentId, agentVersion);
            for (AgentCollaboratorSummary c : collaborators) {
                if (c.agentDescriptor() != null
                        && c.agentDescriptor().aliasArn() != null) {
                    ids.add(c.agentDescriptor().aliasArn());
                }
            }
        } catch (BedrockAgentException e) {
            // Multi-agent collaboration may not be enabled; fail soft
            LOG.ok("No collaborators found for agent {0}: {1}", agentId, e.getMessage());
        } catch (Exception e) {
            LOG.warn(e, "Failed to list collaborators for agent {0}", agentId);
        }
        return ids;
    }

    private ConnectorObject toGuardrailConnectorObject(ObjectClass objectClass,
                                                       Agent agent,
                                                       GuardrailConfiguration guardrailConfig,
                                                       GetGuardrailResponse details) {
        if (guardrailConfig == null || guardrailConfig.guardrailIdentifier() == null) {
            return null;
        }

        String agentId = agent.agentId();
        String guardrailId = guardrailConfig.guardrailIdentifier();
        String guardrailVersion = guardrailConfig.guardrailVersion();

        // Use the shared helper so format matches fromGuardrailUid()
        String uidValue = toGuardrailUid(
                agentId,
                guardrailId,
                (guardrailVersion != null && !guardrailVersion.isEmpty())
                        ? guardrailVersion
                        : "DRAFT");

        ConnectorObjectBuilder b = new ConnectorObjectBuilder();
        b.setObjectClass(objectClass);
        b.setUid(new Uid(uidValue));

        // Prefer name from GetGuardrailResponse; fall back to ID
        String name = guardrailId;
        if (details != null && details.name() != null && !details.name().isEmpty()) {
            name = details.name();
        }
        b.setName(new Name(name));

        b.addAttribute(AttributeBuilder.build(ATTR_PLATFORM, AwsBedrockConstants.CONNECTOR_NAME));
        b.addAttribute(AttributeBuilder.build(ATTR_AGENT_ID, agentId));
        if (agent.agentVersion() != null && !agent.agentVersion().isEmpty()) {
            b.addAttribute(AttributeBuilder.build(ATTR_AGENT_VERSION, agent.agentVersion()));
        }

        b.addAttribute(AttributeBuilder.build(ATTR_GUARDRAIL_ID, guardrailId));
        if (guardrailVersion != null && !guardrailVersion.isEmpty()) {
            b.addAttribute(AttributeBuilder.build(ATTR_GUARDRAIL_VERSION, guardrailVersion));
        }

        if (details != null) {
            if (details.description() != null && !details.description().isEmpty()) {
                b.addAttribute(AttributeBuilder.build(
                        ATTR_GUARDRAIL_DESCRIPTION,
                        details.description()));
            }

            String status = details.statusAsString();
            if (status != null && !status.isEmpty()) {
                b.addAttribute(AttributeBuilder.build(ATTR_GUARDRAIL_STATE, status));
                b.addAttribute(AttributeBuilder.build(ATTR_GUARDRAIL_DEPLOYMENT_STATUS, status));
            }

            // OPENICF-428: Serialize full contentPolicy as JSON into both inputAction and
            // outputAction. Each filter entry carries its own inputAction/outputAction fields;
            // there is no single top-level action value. Both attributes receive the same
            // payload so neither is null. A future schema revision can split them properly.
            // AWS SDK model objects are not Jackson-serializable directly; convert to Maps first.
            if (details.contentPolicy() != null && details.contentPolicy().filters() != null) {
                try {
                    List<Map<String, Object>> filterMaps = new ArrayList<>();
                    for (software.amazon.awssdk.services.bedrock.model.GuardrailContentFilter f
                            : details.contentPolicy().filters()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("type", f.typeAsString());
                        m.put("inputStrength", f.inputStrengthAsString());
                        m.put("outputStrength", f.outputStrengthAsString());
                        m.put("inputAction", f.inputActionAsString());
                        m.put("outputAction", f.outputActionAsString());
                        if (f.inputModalities() != null) {
                            m.put("inputModalities", f.inputModalitiesAsStrings());
                        }
                        if (f.outputModalities() != null) {
                            m.put("outputModalities", f.outputModalitiesAsStrings());
                        }
                        filterMaps.add(m);
                    }
                    String contentPolicyJson = JSON_MAPPER.writeValueAsString(filterMaps);
                    b.addAttribute(AttributeBuilder.build(ATTR_GUARDRAIL_INPUT_ACTION, contentPolicyJson));
                    b.addAttribute(AttributeBuilder.build(ATTR_GUARDRAIL_OUTPUT_ACTION, contentPolicyJson));
                } catch (Exception e) {
                    LOG.warn(e, "Failed to serialize contentPolicy for guardrail {0}", guardrailId);
                }
            }
        }

        return b.build();
    }

    private ConnectorObject toGuardrailConnectorObject(ObjectClass objectClass,
                                                       Agent agent,
                                                       GuardrailConfiguration guardrailConfig) {
        return toGuardrailConnectorObject(objectClass, agent, guardrailConfig, null);
    }

    private ConnectorObject toKnowledgeBaseConnectorObject(ObjectClass objectClass,
                                                           String agentId,
                                                           String agentVersion,
                                                           AgentKnowledgeBaseSummary kb) {
        if (kb == null || kb.knowledgeBaseId() == null) {
            return null;
        }

        String uidValue = toKnowledgeBaseUid(agentId, kb.knowledgeBaseId());

        ConnectorObjectBuilder b = new ConnectorObjectBuilder();
        b.setObjectClass(objectClass);
        b.setUid(new Uid(uidValue));
        b.setName(new Name(kb.knowledgeBaseId()));

        // Context
        b.addAttribute(AttributeBuilder.build(ATTR_PLATFORM, AwsBedrockConstants.CONNECTOR_NAME));
        b.addAttribute(AttributeBuilder.build(ATTR_AGENT_ID, agentId));
        b.addAttribute(AttributeBuilder.build(ATTR_AGENT_VERSION, agentVersion));

        // KB-specific
        b.addAttribute(AttributeBuilder.build(ATTR_KNOWLEDGE_BASE_ID, kb.knowledgeBaseId()));

        String desc = kb.description();
        if (desc != null && !desc.isEmpty()) {
            b.addAttribute(AttributeBuilder.build(ATTR_DESCRIPTION, desc));
        }

        String state = kb.knowledgeBaseStateAsString();
        if (state != null && !state.isEmpty()) {
            b.addAttribute(AttributeBuilder.build(ATTR_STATUS, state));
        }

        if (kb.updatedAt() != null) {
            b.addAttribute(AttributeBuilder.build(ATTR_UPDATED_AT, kb.updatedAt().toString()));
        }

        return b.build();
    }

    private ConnectorObject toToolConnectorObject(ObjectClass objectClass,
                                                  String agentId,
                                                  String agentVersion,
                                                  ActionGroupSummary group) {
        if (group == null || group.actionGroupId() == null) {
            return null;
        }

        String uidValue = toToolUid(agentId, group.actionGroupId());

        ConnectorObjectBuilder b = new ConnectorObjectBuilder();
        b.setObjectClass(objectClass);
        b.setUid(new Uid(uidValue));

        String name = group.actionGroupName() != null
                ? group.actionGroupName()
                : group.actionGroupId();
        b.setName(new Name(name));

        b.addAttribute(AttributeBuilder.build(ATTR_PLATFORM, AwsBedrockConstants.CONNECTOR_NAME));
        b.addAttribute(AttributeBuilder.build(ATTR_AGENT_ID, agentId));
        b.addAttribute(AttributeBuilder.build(ATTR_AGENT_VERSION, agentVersion));

        b.addAttribute(AttributeBuilder.build(ATTR_ACTION_GROUP_ID, group.actionGroupId()));
        b.addAttribute(AttributeBuilder.build(ATTR_ACTION_GROUP_NAME, group.actionGroupName()));
        b.addAttribute(AttributeBuilder.build(ATTR_DESCRIPTION, group.description()));

        if (group.actionGroupState() != null) {
            b.addAttribute(AttributeBuilder.build(ATTR_STATUS, group.actionGroupStateAsString()));
        }
        try {
            // Use the helper on AwsBedrockClient via connection
            AgentActionGroup detail =
                    client().getAgentActionGroup(agentId, agentVersion, group.actionGroupId());

            if (detail != null) {
                // Executor ARN (Lambda or custom control)
                ActionGroupExecutor exec = detail.actionGroupExecutor();
                if (exec != null) {
                    // Only treat Lambda as an "executor ARN"
                    String lambdaArn = exec.lambda();
                    if (lambdaArn != null && !lambdaArn.isEmpty()) {
                        b.addAttribute(AttributeBuilder.build(
                                ATTR_ACTION_GROUP_EXECUTOR_ARN,
                                lambdaArn));
                    }
                }

                // Schema URI (when schema stored in S3)
                APISchema apiSchema = detail.apiSchema();
                if (apiSchema != null && apiSchema.s3() != null) {
                    S3Identifier s3 = apiSchema.s3();

                    String bucket = s3.s3BucketName();
                    String key    = s3.s3ObjectKey();

                    if (bucket != null && !bucket.isEmpty()
                            && key != null && !key.isEmpty()) {

                        // Construct a canonical S3 URI from bucket + key
                        String schemaUri = "s3://" + bucket + "/" + key;

                        b.addAttribute(AttributeBuilder.build(
                                ATTR_ACTION_GROUP_SCHEMA_URI,
                                schemaUri));
                    }
                }

                // OPENICF-429: parentActionSignature — identifies built-in system action groups
                // (e.g. AMAZON.UserInput, AMAZON.CodeInterpreter). Available from getAgentActionGroup().
                // SDK method is parentActionSignatureAsString() — note "Action" not "ActionGroup".
                String parentSig = detail.parentActionSignatureAsString();
                if (parentSig != null && !parentSig.isEmpty()) {
                    b.addAttribute(AttributeBuilder.build(
                            ATTR_ACTION_GROUP_PARENT_SIGNATURE,
                            parentSig));
                }
            }
        } catch (BedrockAgentException e) {
            LOG.info(e,
                    "Failed to fetch details for action group {0} on agent {1}",
                    group.actionGroupId(), agentId);
            // Fail soft: we still return the basic tool object without enriched attributes.
        }

        return b.build();
    }

    // OPENICF-431: Map a raw JsonNode record from agent-tool-credentials.json into
    // an ICF ConnectorObject for the agentToolCredentials object class.
    // UID = the Lambda-computed id field (tc-{sha256[:16]}).
    // __NAME__ = actionGroupName (human-readable; falls back to actionGroupId).
    private ConnectorObject toToolCredentialConnectorObject(ObjectClass objectClass, JsonNode record) {
        if (record == null) {
            return null;
        }
        String id = textOrNull(record, "id");
        if (id == null || id.isEmpty()) {
            return null;
        }

        ConnectorObjectBuilder b = new ConnectorObjectBuilder();
        b.setObjectClass(objectClass);
        b.setUid(new Uid(id));

        String name = textOrNull(record, "actionGroupName");
        if (name == null || name.isEmpty()) {
            name = textOrNull(record, "actionGroupId");
        }
        b.setName(new Name(name != null ? name : id));

        b.addAttribute(AttributeBuilder.build(ATTR_TC_ID, id));
        addIfPresent(b, record, "agentId",             ATTR_AGENT_ID);
        addIfPresent(b, record, "agentArn",            ATTR_TC_AGENT_ARN);
        addIfPresent(b, record, "agentServiceRoleArn", ATTR_TC_AGENT_SERVICE_ROLE_ARN);
        addIfPresent(b, record, "actionGroupId",       ATTR_TC_ACTION_GROUP_ID);
        addIfPresent(b, record, "actionGroupName",     ATTR_TC_ACTION_GROUP_NAME);
        addIfPresent(b, record, "actionGroupState",    ATTR_TC_ACTION_GROUP_STATE);
        addIfPresent(b, record, "credentialType",      ATTR_TC_CREDENTIAL_TYPE);
        addIfPresent(b, record, "credentialRef",       ATTR_TC_CREDENTIAL_REF);
        addIfPresent(b, record, "apiSchemaSource",     ATTR_TC_API_SCHEMA_SOURCE);
        addIfPresent(b, record, "accountId",           ATTR_TC_ACCOUNT_ID);
        addIfPresent(b, record, "region",              ATTR_TC_REGION);
        // OPENICF-432: absent until Python Lambda adds lambda:GetFunction call
        addIfPresent(b, record, "lambdaExecutionRoleArn", ATTR_TC_LAMBDA_EXECUTION_ROLE_ARN);

        // functionSchema is a boolean in the payload — serialize as String for ICF
        JsonNode fsNode = record.get("functionSchema");
        if (fsNode != null && !fsNode.isNull()) {
            b.addAttribute(AttributeBuilder.build(ATTR_TC_FUNCTION_SCHEMA,
                    String.valueOf(fsNode.asBoolean())));
        }

        return b.build();
    }

    // Helper: add a String attribute only when the field is present and non-null in the record.
    private void addIfPresent(ConnectorObjectBuilder b, JsonNode record,
                              String jsonField, String attrName) {
        String value = textOrNull(record, jsonField);
        if (value != null) {
            b.addAttribute(AttributeBuilder.build(attrName, value));
        }
    }

    /**
     * Internal representation of a single identity binding edge:
     * Agent + scope (AGENT/ALIAS) + principal + actions (+ optional condition).
     */
    private static final class AgentIdentityBinding {
        final String agentId;
        final String agentVersion;
        final String scope;          // "AGENT" or "ALIAS"
        final String aliasId;        // nullable
        final String principalArn;
        final String principalType;  // IAM_ROLE, IAM_USER, SSO_PERMISSION_SET, etc.
        final String accountId;      // nullable
        final List<String> actions;
        final String effect;         // ALLOW / DENY
        final String conditionJson;  // raw JSON of Condition, nullable

        AgentIdentityBinding(String agentId,
                             String agentVersion,
                             String scope,
                             String aliasId,
                             String principalArn,
                             String principalType,
                             String accountId,
                             List<String> actions,
                             String effect,
                             String conditionJson) {
            this.agentId = agentId;
            this.agentVersion = agentVersion;
            this.scope = scope;
            this.aliasId = aliasId;
            this.principalArn = principalArn;
            this.principalType = principalType;
            this.accountId = accountId;
            this.actions = actions;
            this.effect = effect;
            this.conditionJson = conditionJson;
        }
    }

    /**
     * Map an internal AgentIdentityBinding into an ICF ConnectorObject for
     * the agentIdentityBinding object class.
     */
    private ConnectorObject toIdentityBindingConnectorObject(ObjectClass objectClass,
                                                             AgentIdentityBinding binding) {
        if (binding == null || binding.principalArn == null || binding.principalArn.isEmpty()) {
            return null;
        }

        String uidValue = toIdentityBindingUid(
                binding.agentId,
                binding.scope,
                binding.principalArn);

        ConnectorObjectBuilder b = new ConnectorObjectBuilder();
        b.setObjectClass(objectClass);
        b.setUid(new Uid(uidValue));
        b.setName(new Name(binding.principalArn));

        // Context
        b.addAttribute(AttributeBuilder.build(
                ATTR_PLATFORM,
                AwsBedrockConstants.CONNECTOR_NAME));
        b.addAttribute(AttributeBuilder.build(
                ATTR_AGENT_ID,
                binding.agentId));

        // KIND = binding scope (AGENT/ALIAS)
        b.addAttribute(AttributeBuilder.build(
                ATTR_KIND,
                binding.scope));

        // PRINCIPAL = "<type>:<accountId>:<arn>"
        String packedPrincipal = packPrincipal(
                binding.principalType,
                binding.accountId,
                binding.principalArn);

        b.addAttribute(AttributeBuilder.build(
                ATTR_PRINCIPAL,
                packedPrincipal));

        // PERMISSIONS = actions[]
        if (binding.actions != null && !binding.actions.isEmpty()) {
            b.addAttribute(AttributeBuilder.build(
                    ATTR_PERMISSIONS,
                    binding.actions.toArray(new String[0])));
        }

        // effect / conditionJson are currently kept internal only (for future).

        return b.build();
    }

    /**
     * Helper to pack principal parts into the canonical string representation
     * used on both agentIdentityBinding and agent.agentPrincipals[].
     */
    private String packPrincipal(String principalType,
                                 String accountId,
                                 String principalArn) {
        String type = principalType != null ? principalType : "";
        String acct = accountId != null ? accountId : "";
        String arn = principalArn != null ? principalArn : "";
        return type + ":" + acct + ":" + arn;
    }

    // OPENICF-426: Replaced listIdentityBindingsForAgentAndAliases with alias-scoped variant.
    // When aliasId is null, returns all bindings (agent-level + all aliases) — used by
    //   bare agent path and searchIdentityBindings.
    // When aliasId is non-null, returns agent-level bindings + only that alias's bindings —
    //   used by alias ConnectorObject path so agentPrincipals is scoped correctly.
    private List<AgentIdentityBinding> listIdentityBindingsForAgent(AwsBedrockClient client,
                                                                    String agentId,
                                                                    String aliasId) {
        Map<String, List<AgentIdentityBinding>> cache = getBindingsCache();
        List<AgentIdentityBinding> results = new ArrayList<>();

        // 1. Agent-level bindings (always included)
        List<AgentIdentityBinding> direct = cache.get(agentKey(agentId));
        if (direct != null) {
            results.addAll(direct);
        }

        // OPENICF-427: Wildcard bindings apply to every agent and alias.
        List<AgentIdentityBinding> wildcardBindings = cache.get(WILDCARD_KEY);
        if (wildcardBindings != null) {
            results.addAll(wildcardBindings);
        }

        if (aliasId != null) {
            // OPENICF-426: Scoped — only this alias's bindings
            List<AgentIdentityBinding> aliasBindings = cache.get(aliasKey(agentId, aliasId));
            if (aliasBindings != null) {
                results.addAll(aliasBindings);
            }
        } else {
            // All alias bindings (bare agent rollup or identity binding search)
            List<AgentAliasSummary> aliases = client.listAgentAliases(agentId);
            if (aliases != null && !aliases.isEmpty()) {
                for (AgentAliasSummary alias : aliases) {
                    String aId = alias.agentAliasId();
                    if (aId == null || aId.isEmpty()) {
                        continue;
                    }
                    List<AgentIdentityBinding> aliasBindings = cache.get(aliasKey(agentId, aId));
                    if (aliasBindings != null) {
                        results.addAll(aliasBindings);
                    }
                }
            }
        }

        LOG.ok("Resolved {0} identity bindings for agent {1} (aliasId={2})",
                results.size(), agentId, aliasId != null ? aliasId : "ALL");
        return results;
    }

    /**
     * Parse an IAM policy document JSON and extract AgentIdentityBinding rows
     * for a given Bedrock agent by matching alias ARNs.
     *
     * We look for statements with:
     *   Effect: "Allow"
     *   Action: includes "bedrock:InvokeAgent"
     *   Resource: exactly matches one of the alias ARNs in aliasArnById
     */
    private List<AgentIdentityBinding> parsePolicyDocumentForBindings(String agentId,
                                                                      String agentVersion,
                                                                      String roleArn,
                                                                      String policyJson,
                                                                      Map<String, String> aliasArnById) {
        List<AgentIdentityBinding> bindings = new ArrayList<>();

        if (policyJson == null || policyJson.isEmpty()) {
            return bindings;
        }

        JsonNode root;
        try {
            root = JSON_MAPPER.readTree(policyJson);
        } catch (IOException e) {
            LOG.warn(e, "Failed to parse IAM policy JSON for role {0}", roleArn);
            return bindings;
        }

        JsonNode statements = root.get("Statement");
        if (statements == null || statements.isNull()) {
            return bindings;
        }

        // Normalize: Statement can be an object or an array
        if (!statements.isArray()) {
            statements = JSON_MAPPER.createArrayNode().add(statements);
        }

        for (JsonNode stmt : statements) {
            String effect = asTextOrNull(stmt.get("Effect"));
            if (!"Allow".equalsIgnoreCase(effect)) {
                continue;
            }

            JsonNode actionNode = stmt.get("Action");
            if (!containsInvokeAgentAction(actionNode)) {
                continue;
            }

            JsonNode resourceNode = stmt.get("Resource");
            if (resourceNode == null || resourceNode.isNull()) {
                continue;
            }

            List<String> resources = toStringList(resourceNode);
            if (resources.isEmpty()) {
                continue;
            }

            List<String> actions = extractActions(actionNode);
            String conditionJson = stmt.has("Condition") && !stmt.get("Condition").isNull()
                    ? stmt.get("Condition").toString()
                    : null;

            String accountId = extractAccountFromArn(roleArn);

            for (String resArn : resources) {
                // Does this resource match any alias ARN for this agent?
                String matchedAliasId = null;
                for (Map.Entry<String, String> entry : aliasArnById.entrySet()) {
                    if (resArn.equals(entry.getValue())) {
                        matchedAliasId = entry.getKey();
                        break;
                    }
                }

                if (matchedAliasId == null) {
                    continue;
                }

                bindings.add(new AgentIdentityBinding(
                        agentId,
                        agentVersion,
                        "ALIAS",                  // scope
                        matchedAliasId,           // aliasId
                        roleArn,                  // principalArn
                        "IAM_ROLE",               // principalType
                        accountId,                // accountId
                        actions,
                        effect,
                        conditionJson
                ));
            }
        }

        return bindings;
    }

    private String extractAccountFromArn(String arn) {
        // arn:partition:service:region:account-id:...
        if (arn == null || arn.isEmpty()) {
            return null;
        }
        String[] parts = arn.split(":", 6);
        return parts.length >= 5 ? parts[4] : null;
    }

    private boolean containsInvokeAgentAction(JsonNode actionNode) {
        if (actionNode == null || actionNode.isNull()) {
            return false;
        }
        if (actionNode.isTextual()) {
            return "bedrock:InvokeAgent".equalsIgnoreCase(actionNode.asText());
        }
        if (actionNode.isArray()) {
            for (JsonNode n : actionNode) {
                if (n.isTextual()
                        && "bedrock:InvokeAgent".equalsIgnoreCase(n.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<String> extractActions(JsonNode actionNode) {
        List<String> actions = new ArrayList<>();
        if (actionNode == null || actionNode.isNull()) {
            return actions;
        }
        if (actionNode.isTextual()) {
            actions.add(actionNode.asText());
        } else if (actionNode.isArray()) {
            for (JsonNode n : actionNode) {
                if (n.isTextual()) {
                    actions.add(n.asText());
                }
            }
        }
        return actions;
    }

    // OPENICF-431: Updated to read from inventoryBucket + AGENT_BINDINGS_S3_KEY constant.
    // Previously read from s3BindingsBucket + "{accountId}/{region}/bindings.json".
    private Map<String, List<AgentIdentityBinding>> loadBindingsFromS3() {
        AwsBedrockClient client = client();
        String accountId = client.getAccountId();
        String region = client.getRegion();
        String bucket = connection.getConfiguration().getInventoryBucket();
        String key = AGENT_BINDINGS_S3_KEY;

        Map<String, List<AgentIdentityBinding>> map = new HashMap<>();

        try {
            GetObjectRequest req = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();

            try (ResponseInputStream<GetObjectResponse> in = s3Client().getObject(req)) {
                JsonNode root = JSON_MAPPER.readTree(in);

                String payloadAccountId = textOrNull(root, "accountId");
                if (payloadAccountId != null && !payloadAccountId.isEmpty()) {
                    accountId = payloadAccountId;
                }

                JsonNode bindingsNode = root.get("bindings");
                if (bindingsNode != null && bindingsNode.isArray()) {
                    for (JsonNode b : bindingsNode) {
                        String agentArn = textOrNull(b, "agentArn");
                        String aliasArn = textOrNull(b, "aliasArn");
                        String principalType = textOrNull(b, "principalType");
                        String principalArn = textOrNull(b, "principalArn");
                        String principalAccountId = textOrNull(b, "principalAccountId");

                        String bindingAccountId = (principalAccountId != null && !principalAccountId.isEmpty())
                                ? principalAccountId
                                : accountId;

                        String agentId = null;
                        String aliasId = null;
                        String scope;

                        if (aliasArn != null && !aliasArn.isEmpty()) {
                            String[] ids = extractIdsFromAliasArn(aliasArn);
                            if (ids == null) {
                                continue;
                            }
                            agentId = ids[0];
                            aliasId = ids[1];
                            scope = "ALIAS";
                        } else if (agentArn != null && !agentArn.isEmpty()) {
                            agentId = extractAgentIdFromAgentArn(agentArn);
                            if (agentId == null) {
                                continue;
                            }
                            scope = "AGENT";
                        } else {
                            // OPENICF-427: Wildcard binding (no agentArn, no aliasArn).
                            // Applies to all agents and aliases; stored under WILDCARD_KEY.
                            // principalArn is set; scope treated as "AGENT" for consistency
                            // with agentIdentityBinding object class conventions.
                            boolean isWildcard = b.has("wildcard") && b.get("wildcard").asBoolean();
                            if (!isWildcard || principalArn == null || principalArn.isEmpty()) {
                                continue;
                            }
                            List<String> actions = Collections.singletonList("bedrock:InvokeAgent");
                            AgentIdentityBinding binding = new AgentIdentityBinding(
                                    "*",           // agentId sentinel — wildcard applies to all agents
                                    null,          // agentVersion not encoded in IAM policy
                                    "AGENT",       // scope — consistent with agentIdentityBinding conventions
                                    null,          // aliasId
                                    principalArn,
                                    principalType,
                                    bindingAccountId,
                                    actions,
                                    "ALLOW",
                                    textOrNull(b, "conditionJson")
                            );
                            map.computeIfAbsent(WILDCARD_KEY, k -> new ArrayList<>()).add(binding);
                            continue;
                        }

                        // For our purposes, the action is always bedrock:InvokeAgent, effect Allow
                        List<String> actions = Collections.singletonList("bedrock:InvokeAgent");
                        String effect = "ALLOW";
                        String conditionJson = null;

                        AgentIdentityBinding binding = new AgentIdentityBinding(
                                agentId,
                                null,          // agentVersion not encoded in IAM policy
                                scope,
                                aliasId,
                                principalArn,
                                principalType,
                                bindingAccountId,
                                actions,
                                effect,
                                conditionJson
                        );

                        String mapKey;
                        if ("ALIAS".equals(scope)) {
                            mapKey = aliasKey(agentId, aliasId);
                        } else {
                            mapKey = agentKey(agentId);
                        }

                        map.computeIfAbsent(mapKey, k -> new ArrayList<>()).add(binding);
                    }
                }
            }

            LOG.ok("Loaded {0} identity binding key entries from S3", map.size());
        } catch (NoSuchKeyException e) {
            LOG.warn(e, "No precomputed bindings file found at s3://{0}/{1}", bucket, key);
        } catch (Exception e) {
            LOG.error(e, "Failed to load precomputed bindings from S3 at s3://{0}/{1}", bucket, key);
        }

        return map;
    }

    private Map<String, List<AgentIdentityBinding>> getBindingsCache() {
        Instant now = Instant.now();
        long cacheTtl = connection.getConfiguration().getBindingsCacheTtlSeconds();
        if (bindingsByKey.isEmpty()
                || bindingsLoadedAt.plusSeconds(cacheTtl).isBefore(now)) {
            synchronized (this) {
                if (bindingsByKey.isEmpty()
                        || bindingsLoadedAt.plusSeconds(cacheTtl).isBefore(now)) {
                    LOG.ok("Refreshing precomputed agent bindings cache from S3");
                    bindingsByKey = loadBindingsFromS3();
                    bindingsLoadedAt = now;
                }
            }
        }
        return bindingsByKey;
    }

    // OPENICF-431: Load agent-tool-credentials.json from S3 and index by id and agentId.
    private void loadToolCredentialsFromS3() {
        String bucket = connection.getConfiguration().getInventoryBucket();
        String key = TOOL_CREDENTIALS_S3_KEY;

        Map<String, JsonNode> byId = new HashMap<>();
        Map<String, List<String>> byAgent = new HashMap<>();

        try {
            GetObjectRequest req = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();

            try (ResponseInputStream<GetObjectResponse> in = s3Client().getObject(req)) {
                JsonNode root = JSON_MAPPER.readTree(in);
                if (root != null && root.isArray()) {
                    for (JsonNode record : root) {
                        String id = textOrNull(record, "id");
                        String agentId = textOrNull(record, "agentId");
                        if (id == null || id.isEmpty()) {
                            continue;
                        }
                        byId.put(id, record);
                        if (agentId != null && !agentId.isEmpty()) {
                            byAgent.computeIfAbsent(agentId, k -> new ArrayList<>()).add(id);
                        }
                    }
                }
            }

            LOG.ok("Loaded {0} tool credential records from S3", byId.size());
        } catch (NoSuchKeyException e) {
            LOG.warn(e, "No tool credentials file found at s3://{0}/{1}", bucket, key);
        } catch (Exception e) {
            LOG.error(e, "Failed to load tool credentials from S3 at s3://{0}/{1}", bucket, key);
        }

        toolCredentialsById = byId;
        toolCredentialIdsByAgent = byAgent;
    }

    // OPENICF-431: TTL-driven cache for tool credentials — mirrors getBindingsCache() pattern.
    private Map<String, JsonNode> getToolCredentialsCache() {
        Instant now = Instant.now();
        long cacheTtl = connection.getConfiguration().getBindingsCacheTtlSeconds();
        if (toolCredentialsById.isEmpty()
                || toolCredentialsLoadedAt.plusSeconds(cacheTtl).isBefore(now)) {
            synchronized (this) {
                if (toolCredentialsById.isEmpty()
                        || toolCredentialsLoadedAt.plusSeconds(cacheTtl).isBefore(now)) {
                    LOG.ok("Refreshing tool credentials cache from S3");
                    loadToolCredentialsFromS3();
                    toolCredentialsLoadedAt = now;
                }
            }
        }
        return toolCredentialsById;
    }

    private String extractAgentIdFromAgentArn(String agentArn) {
        if (agentArn == null) {
            return null;
        }
        int idx = agentArn.indexOf(":agent/");
        if (idx < 0) {
            return null;
        }
        return agentArn.substring(idx + ":agent/".length());
    }

    private String[] extractIdsFromAliasArn(String aliasArn) {
        // Returns [agentId, aliasId] or null if cannot parse
        if (aliasArn == null) {
            return null;
        }
        int idx = aliasArn.indexOf("agent-alias/");
        if (idx < 0) {
            return null;
        }
        String rest = aliasArn.substring(idx + "agent-alias/".length());
        String[] parts = rest.split("/", 2);
        if (parts.length != 2) {
            return null;
        }
        return parts;
    }

    private List<String> toStringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || node.isNull()) {
            return values;
        }
        if (node.isTextual()) {
            values.add(node.asText());
        } else if (node.isArray()) {
            for (JsonNode n : node) {
                if (n.isTextual()) {
                    values.add(n.asText());
                }
            }
        }
        return values;
    }

    private String asTextOrNull(JsonNode node) {
        return (node != null && !node.isNull()) ? node.asText() : null;
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return (v != null && !v.isNull()) ? v.asText() : null;
    }

}