// src/main/java/org/forgerock/openicf/connectors/awsbedrock/client/AwsBedrockClient.java
package org.forgerock.openicf.connectors.awsbedrock.client;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockagent.BedrockAgentClient;
import software.amazon.awssdk.services.bedrockagent.model.*;
import software.amazon.awssdk.services.bedrock.BedrockClient;
import software.amazon.awssdk.services.bedrock.model.BedrockException;
import software.amazon.awssdk.services.bedrock.model.GetGuardrailRequest;
import software.amazon.awssdk.services.bedrock.model.GetGuardrailResponse;

import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.IamException;
import software.amazon.awssdk.services.iam.model.ListRolesRequest;
import software.amazon.awssdk.services.iam.model.ListRolesResponse;
import software.amazon.awssdk.services.iam.model.Role;
import software.amazon.awssdk.services.iam.model.ListAttachedRolePoliciesRequest;
import software.amazon.awssdk.services.iam.model.ListAttachedRolePoliciesResponse;
import software.amazon.awssdk.services.iam.model.AttachedPolicy;
import software.amazon.awssdk.services.iam.model.ListRolePoliciesRequest;
import software.amazon.awssdk.services.iam.model.ListRolePoliciesResponse;
import software.amazon.awssdk.services.iam.model.GetRolePolicyRequest;
import software.amazon.awssdk.services.iam.model.GetRolePolicyResponse;
import software.amazon.awssdk.services.iam.model.GetPolicyRequest;
import software.amazon.awssdk.services.iam.model.GetPolicyResponse;
import software.amazon.awssdk.services.iam.model.GetPolicyVersionRequest;
import software.amazon.awssdk.services.iam.model.GetPolicyVersionResponse;


import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;

import java.util.List;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * AWS Bedrock Agent client using AWS SDK v2.
 *
 * This is the internal client used by the OpenICF connector. It is responsible
 * for performing read-only operations against the Bedrock Agents API:
 *
 * <ul>
 *     <li>List agents</li>
 *     <li>Get a specific agent</li>
 *     <li>List action groups (tools) for an agent</li>
 *     <li>List knowledge bases for an agent</li>
 * </ul>
 *
 * Authentication modes (mirroring the uploaded AwsBedrockClient.java):
 * <ul>
 *     <li>Default AWS credentials provider chain</li>
 *     <li>Explicit access key / secret key</li>
 * </ul>
 */
public class AwsBedrockClient implements AutoCloseable {

    private final BedrockAgentClient agentClient;
    private final BedrockClient bedrockClient;
    private final IamClient iamClient;
    private final String region;
    private final String accountId;

    /**
     * Creates a client using the default AWS credentials provider chain.
     * This chain checks (in order):
     * <ol>
     *     <li>Environment variables (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, etc.)</li>
     *     <li>System properties (aws.accessKeyId, aws.secretAccessKey)</li>
     *     <li>Credentials file (~/.aws/credentials)</li>
     *     <li>EC2/ECS instance profile / role credentials, and others supported by the SDK</li>
     * </ol>
     *
     * @param region AWS region (e.g., "us-east-1", "us-west-2")
     */
    public AwsBedrockClient(String region,String accountId) {
        Region awsRegion = Region.of(region);
        DefaultCredentialsProvider provider = DefaultCredentialsProvider.create();
        SdkHttpClient httpClient = UrlConnectionHttpClient.builder().build();

        this.agentClient = BedrockAgentClient.builder()
                .region(awsRegion)
                .credentialsProvider(provider)
                .httpClient(httpClient)
                .build();

        this.bedrockClient = BedrockClient.builder()
                .region(awsRegion)
                .credentialsProvider(provider)
                .httpClient(httpClient)
                .build();

        this.iamClient = IamClient.builder()
                .region(awsRegion)
                .credentialsProvider(provider)
                .httpClient(httpClient)
                .build();

        this.region = region;
        this.accountId = accountId;
    }

    /**
     * Creates a client using explicit access key and secret key.
     *
     * @param region          AWS region (e.g., "us-east-1", "us-west-2")
     * @param accessKeyId     AWS access key ID
     * @param secretAccessKey AWS secret access key
     */
    public AwsBedrockClient(String region, String accountId, String accessKeyId, String secretAccessKey) {
        AwsCredentials credentials = AwsBasicCredentials.create(accessKeyId, secretAccessKey);
        Region awsRegion = Region.of(region);

        StaticCredentialsProvider provider = StaticCredentialsProvider.create(credentials);
        SdkHttpClient httpClient = UrlConnectionHttpClient.builder().build();

        this.agentClient = BedrockAgentClient.builder()
                .region(awsRegion)
                .credentialsProvider(provider)
                .httpClient(httpClient)
                .build();

        this.bedrockClient = BedrockClient.builder()
                .region(awsRegion)
                .credentialsProvider(provider)
                .httpClient(httpClient)
                .build();

        this.iamClient = IamClient.builder()
                .region(awsRegion)
                .credentialsProvider(provider)
                .httpClient(httpClient)
                .build();

        this.region = region;
        this.accountId = accountId;
    }

    public String getRegion() {
        return region;
    }

    public String getAccountId() {
        return accountId;
    }

    /**
     * List IAM roles in the configured account.
     * NOTE: This is a simple, single-page implementation. Add pagination if needed.
     */
    public List<Role> listRoles() {
        ListRolesResponse resp = iamClient.listRoles(
                ListRolesRequest.builder().build());
        return resp.roles();
    }

    /**
     * Returns all JSON policy documents (inline + attached customer/AWS-managed)
     * for the given IAM role.
     *
     * NOTE: IAM encodes policy documents as URL-encoded JSON; this method
     * returns decoded JSON strings.
     */
    public List<String> getRolePolicyDocuments(String roleName) {
        List<String> docs = new ArrayList<>();

        // 1. Inline role policies
        ListRolePoliciesResponse inlineResp = iamClient.listRolePolicies(
                ListRolePoliciesRequest.builder()
                        .roleName(roleName)
                        .build());

        for (String policyName : inlineResp.policyNames()) {
            GetRolePolicyResponse rp = iamClient.getRolePolicy(
                    GetRolePolicyRequest.builder()
                            .roleName(roleName)
                            .policyName(policyName)
                            .build());

            String encoded = rp.policyDocument();
            if (encoded != null && !encoded.isEmpty()) {
                String decoded = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
                docs.add(decoded);
            }
        }

        // 2. Attached managed policies (customer- or AWS-managed)
        ListAttachedRolePoliciesResponse attachedResp = iamClient.listAttachedRolePolicies(
                ListAttachedRolePoliciesRequest.builder()
                        .roleName(roleName)
                        .build());

        for (AttachedPolicy ap : attachedResp.attachedPolicies()) {
            GetPolicyResponse gp = iamClient.getPolicy(
                    GetPolicyRequest.builder()
                            .policyArn(ap.policyArn())
                            .build());

            if (gp.policy() == null || gp.policy().defaultVersionId() == null) {
                continue;
            }

            GetPolicyVersionResponse gpv = iamClient.getPolicyVersion(
                    GetPolicyVersionRequest.builder()
                            .policyArn(ap.policyArn())
                            .versionId(gp.policy().defaultVersionId())
                            .build());

            if (gpv.policyVersion() != null && gpv.policyVersion().document() != null) {
                String encoded = gpv.policyVersion().document();
                String decoded = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
                docs.add(decoded);
            }
        }

        return docs;
    }

    /**
     * Lists Bedrock agents in the configured region (single page, up to 100).
     *
     * @return List of AgentSummary objects.
     * @throws BedrockAgentException if the API call fails.
     */
    public List<AgentSummary> listAgents() {
        ListAgentsRequest request = ListAgentsRequest.builder()
                .maxResults(100)
                .build();

        ListAgentsResponse response = agentClient.listAgents(request);
        return response.agentSummaries();
    }

    /**
     * Lists agents with pagination support.
     *
     * @param maxResults Maximum number of results to return (per page).
     * @param nextToken  Token for the next page (null or empty for first page).
     * @return ListAgentsResponse containing agent summaries and next token.
     * @throws BedrockAgentException if the API call fails.
     */
    public ListAgentsResponse listAgentsPaginated(int maxResults, String nextToken) {
        ListAgentsRequest.Builder builder = ListAgentsRequest.builder()
                .maxResults(maxResults);

        if (nextToken != null && !nextToken.isEmpty()) {
            builder.nextToken(nextToken);
        }

        return agentClient.listAgents(builder.build());
    }

    /**
     * Gets detailed information about a specific agent.
     *
     * @param agentId The unique identifier of the agent.
     * @return Agent object with complete details.
     * @throws BedrockAgentException if the agent is not found or API call fails.
     */
    public Agent getAgent(String agentId) {
        GetAgentRequest request = GetAgentRequest.builder()
                .agentId(agentId)
                .build();

        GetAgentResponse response = agentClient.getAgent(request);
        return response.agent();
    }
    /**
     * List aliases for a given Bedrock agent.
     */
    public List<AgentAliasSummary> listAgentAliases(String agentId) {
        ListAgentAliasesRequest request = ListAgentAliasesRequest.builder()
                .agentId(agentId)
                .maxResults(100) // TODO: add pagination if you expect >100 aliases
                .build();

        ListAgentAliasesResponse response = agentClient.listAgentAliases(request);
        return response.agentAliasSummaries();
    }

    // OPENICF-422: Get full details for a single agent alias (needed for alias-level identity model).
    /**
     * Fetches full details for a specific agent alias.
     *
     * @param agentId The Bedrock agent ID.
     * @param aliasId The alias ID.
     * @return AgentAlias with complete details.
     * @throws BedrockAgentException if the alias is not found or API call fails.
     */
    public AgentAlias getAgentAlias(String agentId, String aliasId) {
        GetAgentAliasRequest request = GetAgentAliasRequest.builder()
                .agentId(agentId)
                .agentAliasId(aliasId)
                .build();

        GetAgentAliasResponse response = agentClient.getAgentAlias(request);
        return response.agentAlias();
    }

    // OPENICF-422: List collaborators for multi-agent collaboration (connectedAgents attribute).
    /**
     * Lists the collaborator agents associated with a specific agent.
     *
     * @param agentId      The unique identifier of the agent.
     * @param agentVersion The version of the agent (e.g., "DRAFT").
     * @return List of AgentCollaboratorSummary objects.
     * @throws BedrockAgentException if the API call fails.
     */
    public List<AgentCollaboratorSummary> listAgentCollaborators(String agentId, String agentVersion) {
        ListAgentCollaboratorsRequest request = ListAgentCollaboratorsRequest.builder()
                .agentId(agentId)
                .agentVersion(agentVersion)
                .maxResults(100) // TODO: add pagination if you expect >100 collaborators
                .build();

        ListAgentCollaboratorsResponse response = agentClient.listAgentCollaborators(request);
        return response.agentCollaboratorSummaries();
    }

    /**
     * Lists the action groups (tools) associated with a specific agent.
     *
     * @param agentId      The unique identifier of the agent.
     * @param agentVersion The version of the agent (e.g., "DRAFT" for working draft).
     * @return List of ActionGroupSummary objects.
     * @throws BedrockAgentException if the API call fails.
     */
    public List<ActionGroupSummary> listAgentActionGroups(String agentId, String agentVersion) {
        ListAgentActionGroupsRequest request = ListAgentActionGroupsRequest.builder()
                .agentId(agentId)
                .agentVersion(agentVersion)
                .maxResults(100)
                .build();

        ListAgentActionGroupsResponse response = agentClient.listAgentActionGroups(request);
        return response.actionGroupSummaries();
    }
    /**
     * Fetches full details for a single agent action group.
     *
     * @param agentId       The Bedrock agent ID.
     * @param agentVersion  The agent version (e.g. "DRAFT").
     * @param actionGroupId The action group ID.
     * @return AgentActionGroup, or null if not found.
     * @throws BedrockAgentException if the API call fails.
     */
    public AgentActionGroup getAgentActionGroup(String agentId,
                                                String agentVersion,
                                                String actionGroupId) {
        GetAgentActionGroupRequest request = GetAgentActionGroupRequest.builder()
                .agentId(agentId)
                .agentVersion(agentVersion)
                .actionGroupId(actionGroupId)
                .build();

        GetAgentActionGroupResponse response = agentClient.getAgentActionGroup(request);
        return response.agentActionGroup();
    }
    /**
     * Lists the knowledge bases associated with a specific agent.
     *
     * @param agentId      The unique identifier of the agent.
     * @param agentVersion The version of the agent (e.g., "DRAFT" for working draft).
     * @return List of AgentKnowledgeBaseSummary objects.
     * @throws BedrockAgentException if the API call fails.
     */
    public List<AgentKnowledgeBaseSummary> listAgentKnowledgeBases(String agentId, String agentVersion) {
        ListAgentKnowledgeBasesRequest request = ListAgentKnowledgeBasesRequest.builder()
                .agentId(agentId)
                .agentVersion(agentVersion)
                .maxResults(100)
                .build();

        ListAgentKnowledgeBasesResponse response = agentClient.listAgentKnowledgeBases(request);
        return response.agentKnowledgeBaseSummaries();
    }

    /**
     * Fetches details about a guardrail from the Bedrock control plane.
     *
     * @param guardrailId      ID or ARN from GuardrailConfiguration.guardrailIdentifier().
     * @param guardrailVersion Optional version (e.g. "DRAFT", "1"). If null/empty, Bedrock
     *                         returns the DRAFT version by default.
     */
    public GetGuardrailResponse getGuardrail(String guardrailId, String guardrailVersion) {
        GetGuardrailRequest.Builder builder = GetGuardrailRequest.builder()
                .guardrailIdentifier(guardrailId);

        if (guardrailVersion != null && !guardrailVersion.isEmpty()) {
            builder.guardrailVersion(guardrailVersion);
        }

        return bedrockClient.getGuardrail(builder.build());
    }

    /**
     * Placeholder for retrieving the invocation resource policy JSON for a given
     * Bedrock resource (agent or alias). This is the seam used by the
     * AwsBedrockCrudService to implement Option C (IdentityBinding) by mapping
     * resource policy statements to AgentIdentityBinding rows.
     *
     * For now, this returns {@code null} and does not call any AWS APIs.
     * A future implementation is expected to:
     * <ul>
     *     <li>Resolve the appropriate AWS API (e.g., Bedrock or IAM) for the resource type.</li>
     *     <li>Issue a GetResourcePolicy-style call for {@code resourceArn}.</li>
     *     <li>Return the JSON policy document as a String, or {@code null} if none exists.</li>
     * </ul>
     *
     * @param resourceArn The ARN of the Bedrock agent or alias whose invocation policy
     *                    should be retrieved.
     * @return JSON string representing the resource policy document, or {@code null}
     *         if no policy is configured or retrieval is not implemented.
     */
    public String getInvocationPolicyJson(String resourceArn) {
        // TODO: Implement using AWS resource policy APIs (Bedrock / IAM) once
        //       the runtime environment has the appropriate SDK modules and permissions.
        return null;
    }

    /**
     * Placeholder for retrieving the invocation policy JSON for a specific
     * Bedrock agent alias. This is a thin wrapper around
     * {@link #getInvocationPolicyJson(String)} to improve readability at call sites.
     *
     * @param aliasArn ARN of the Bedrock agent alias.
     * @return JSON string representing the resource policy document, or {@code null}.
     */
    public String getAliasInvocationPolicyJson(String aliasArn) {
        return getInvocationPolicyJson(aliasArn);
    }

    /**
     * Closes the underlying AWS BedrockAgentClient.
     */
    @Override
    public void close() {
        if (agentClient != null) {
            agentClient.close();
        }
        if(bedrockClient != null) {
            bedrockClient.close();
        }
        if (iamClient != null) {
            iamClient.close();
        }
    }
}