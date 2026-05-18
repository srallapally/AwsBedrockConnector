// src/main/java/org/forgerock/openicf/connectors/awsbedrock/AwsBedrockConnector.java
package org.forgerock.openicf.connectors.awsbedrock;

import org.forgerock.openicf.connectors.awsbedrock.operations.AwsBedrockCrudService;
import org.forgerock.openicf.connectors.awsbedrock.utils.AwsBedrockConstants;
import org.identityconnectors.common.CollectionUtil;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException;
import org.identityconnectors.framework.common.objects.*;
import org.identityconnectors.framework.common.objects.filter.EqualsFilter;
import org.identityconnectors.framework.common.objects.filter.Filter;
import org.identityconnectors.framework.common.objects.filter.FilterTranslator;
import org.identityconnectors.framework.spi.*;
import org.identityconnectors.framework.spi.operations.*;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * OpenICF connector for AWS Bedrock Agents.
 *
 * READ-ONLY v1:
 * - Supports: SchemaOp, TestOp, SearchOp
 * - Rejects: CreateOp, UpdateOp, DeleteOp
 */
@ConnectorClass(configurationClass = AwsBedrockConfiguration.class,
        displayNameKey = "awsbedrock.connector.display")
public class AwsBedrockConnector implements Connector,
        SchemaOp,
        TestOp,
        SearchOp<Filter>,
        CreateOp,
        UpdateOp,
        DeleteOp {

    private static final Log LOG = Log.getLog(AwsBedrockConnector.class);

    // Custom object classes
    public static final ObjectClass OC_ACCOUNT =
            new ObjectClass(ObjectClass.ACCOUNT_NAME);
    public static final ObjectClass OC_GUARDRAIL =
            new ObjectClass(AwsBedrockConstants.OC_GUARDRAIL);
    public static final ObjectClass OC_TOOL =
            new ObjectClass(AwsBedrockConstants.OC_TOOL);
    public static final ObjectClass OC_IDENTITY_BINDING =
            new ObjectClass(AwsBedrockConstants.OC_IDENTITY_BINDING);
    public static final ObjectClass OC_KNOWLEDGE_BASE =
            new ObjectClass(AwsBedrockConstants.OC_KNOWLEDGE_BASE);
    // OPENICF-431
    public static final ObjectClass OC_TOOL_CREDENTIALS =
            new ObjectClass(AwsBedrockConstants.OC_TOOL_CREDENTIALS);

    private AwsBedrockConfiguration configuration;
    private AwsBedrockConnection connection;
    private AwsBedrockCrudService crudService;

    // ---------------------------------------------------------------------
    // Connector lifecycle
    // ---------------------------------------------------------------------

    @Override
    public void init(Configuration cfg) {
        LOG.ok("Initializing AwsBedrockConnector.");

        if (!(cfg instanceof AwsBedrockConfiguration)) {
            throw new IllegalArgumentException(
                    "Configuration must be an instance of AwsBedrockConfiguration.");
        }

        this.configuration = (AwsBedrockConfiguration) cfg;
        this.configuration.validate();

        this.connection = new AwsBedrockConnection(this.configuration);
        this.crudService = new AwsBedrockCrudService(this.connection);

        LOG.ok("AwsBedrockConnector initialized successfully.");
    }

    @Override
    public void dispose() {
        LOG.ok("Disposing AwsBedrockConnector.");
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception e) {
                LOG.warn(e, "Error while closing AwsBedrockConnection.");
            } finally {
                connection = null;
            }
        }
        crudService = null;
    }

    @Override
    public Configuration getConfiguration() {
        return configuration;
    }

    // ---------------------------------------------------------------------
    // SchemaOp
    // ---------------------------------------------------------------------

    @Override
    public Schema schema() {
        LOG.ok("Building schema for AwsBedrockConnector.");

        SchemaBuilder builder = new SchemaBuilder(AwsBedrockConnector.class);

        // -----------------------------------------------------------------
        // agent object class
        // -----------------------------------------------------------------
        ObjectClassInfoBuilder agent = new ObjectClassInfoBuilder();
        agent.setType(ObjectClass.ACCOUNT_NAME);
        //agent.setType(AwsBedrockConstants.OC_AGENT);
        agent.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_PLATFORM, String.class));
        agent.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_AGENT_ID, String.class));
        agent.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_VERSION, String.class));
        agent.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_STATUS, String.class));
        agent.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_DESCRIPTION, String.class));
        agent.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_FOUNDATION_MODEL, String.class));
        agent.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_ROLE_ARN, String.class));
        agent.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_IDLE_TTL, Integer.class));
        agent.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_CREATED_AT, String.class));
        agent.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_UPDATED_AT, String.class));
        agent.addAttributeInfo(AttributeInfoBuilder.build(
                AwsBedrockConstants.ATTR_TOOLS, String.class, EnumSet.of(AttributeInfo.Flags.MULTIVALUED)));
        agent.addAttributeInfo(AttributeInfoBuilder.build(
                AwsBedrockConstants.ATTR_KNOWLEDGE_BASES, String.class, EnumSet.of(AttributeInfo.Flags.MULTIVALUED)));
        // NEW: virtual, computed principals attribute (multi-valued, read-only)
        agent.addAttributeInfo(AttributeInfoBuilder.build(
                AwsBedrockConstants.ATTR_AGENT_PRINCIPALS, String.class, EnumSet.of(AttributeInfo.Flags.MULTIVALUED,
                        AttributeInfo.Flags.NOT_CREATABLE, AttributeInfo.Flags.NOT_UPDATEABLE)));
        agent.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_GUARDRAIL_ID, String.class));
        agent.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_GUARDRAIL_VERSION, String.class));
        agent.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_AGENT_ARN, String.class));
        agent.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_AGENT_NAME, String.class));
        agent.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_CUSTOMER_ENCRYPTION_KEY_ARN, String.class));
        agent.addAttributeInfo(AttributeInfoBuilder.build(
                AwsBedrockConstants.ATTR_FAILURE_REASONS, String.class, EnumSet.of(AttributeInfo.Flags.MULTIVALUED)));
        agent.addAttributeInfo(AttributeInfoBuilder.build(
                AwsBedrockConstants.ATTR_RECOMMENDED_ACTIONS, String.class, EnumSet.of(AttributeInfo.Flags.MULTIVALUED)));
        agent.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_PREPARED_AT, String.class));
        // OPENICF-423: Alias-level identity model attributes
        agent.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_ALIAS_ID, String.class));
        agent.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_ALIAS_NAME, String.class));
        agent.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_ALIAS_STATUS, String.class));
        // OPENICF-423: Region stored per-object for multi-region visibility
        agent.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_REGION, String.class));
        // OPENICF-423: Multi-agent collaboration
        agent.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_AGENT_COLLABORATION, String.class));
        agent.addAttributeInfo(AttributeInfoBuilder.build(
                AwsBedrockConstants.ATTR_CONNECTED_AGENTS, String.class, EnumSet.of(AttributeInfo.Flags.MULTIVALUED)));
        // OPENICF-431: Forward pointer to agentToolCredentials records for this agent
        agent.addAttributeInfo(AttributeInfoBuilder.build(
                AwsBedrockConstants.ATTR_TOOL_CREDENTIAL_IDS, String.class, EnumSet.of(AttributeInfo.Flags.MULTIVALUED,
                        AttributeInfo.Flags.NOT_CREATABLE, AttributeInfo.Flags.NOT_UPDATEABLE)));
        // OPENICF-433: Forward pointer to agentIdentityBinding records for this agent/alias
        agent.addAttributeInfo(AttributeInfoBuilder.build(
                AwsBedrockConstants.ATTR_IDENTITY_BINDING_IDS, String.class, EnumSet.of(AttributeInfo.Flags.MULTIVALUED,
                        AttributeInfo.Flags.NOT_CREATABLE, AttributeInfo.Flags.NOT_UPDATEABLE)));
        builder.defineObjectClass(agent.build());

        // -----------------------------------------------------------------
        // OPENICF-475: 5 non-__ACCOUNT__ OCs — data-driven from manifest
        // objectClassSchema, with hardcoded fallback for pre-474 Lambdas.
        // -----------------------------------------------------------------
        if (!buildObjectClassesFromManifest(builder)) {
            buildHardcodedObjectClasses(builder);
        }

        Schema schema = builder.build();
        LOG.ok("Schema built for AwsBedrockConnector.");
        return schema;
    }

    // ---------------------------------------------------------------------
    // OPENICF-475: Data-driven OC schema from manifest objectClassSchema
    // ---------------------------------------------------------------------

    /**
     * The 5 non-__ACCOUNT__ OC names that the manifest schema must contain
     * for the data-driven path to succeed. If any are missing, fall back
     * to hardcoded schema (all-or-nothing).
     */
    private static final Set<String> MANIFEST_OC_NAMES = Set.of(
            AwsBedrockConstants.OC_GUARDRAIL,
            AwsBedrockConstants.OC_KNOWLEDGE_BASE,
            AwsBedrockConstants.OC_TOOL,
            AwsBedrockConstants.OC_IDENTITY_BINDING,
            AwsBedrockConstants.OC_TOOL_CREDENTIALS
    );

    private static final Map<String, Class<?>> TYPE_MAP = Map.of(
            "string", String.class,
            "integer", Integer.class
    );

    private static final Map<String, AttributeInfo.Flags> FLAG_MAP = Map.of(
            "MULTIVALUED", AttributeInfo.Flags.MULTIVALUED,
            "NOT_CREATABLE", AttributeInfo.Flags.NOT_CREATABLE,
            "NOT_UPDATEABLE", AttributeInfo.Flags.NOT_UPDATEABLE,
            "NOT_READABLE", AttributeInfo.Flags.NOT_READABLE,
            "NOT_RETURNED_BY_DEFAULT", AttributeInfo.Flags.NOT_RETURNED_BY_DEFAULT,
            "REQUIRED", AttributeInfo.Flags.REQUIRED
    );

    /**
     * Attempt to build the 5 non-__ACCOUNT__ OCs from the manifest's
     * objectClassSchema. Returns true if all 5 OCs were built; false
     * if the manifest is unavailable or incomplete (caller should
     * fall back to hardcoded schema).
     */
    private boolean buildObjectClassesFromManifest(SchemaBuilder builder) {
        if (crudService == null) {
            return false;
        }

        Map<String, com.fasterxml.jackson.databind.JsonNode> manifestSchema;
        try {
            manifestSchema = crudService.getManifestSchema();
        } catch (Exception e) {
            LOG.warn(e, "OPENICF-475: Failed to read manifest schema; falling back to hardcoded OC definitions");
            return false;
        }

        if (manifestSchema == null || manifestSchema.isEmpty()) {
            LOG.ok("OPENICF-475: No manifest objectClassSchema available; using hardcoded OC definitions");
            return false;
        }

        // All-or-nothing: all 5 OCs must be present
        for (String ocName : MANIFEST_OC_NAMES) {
            if (!manifestSchema.containsKey(ocName)) {
                LOG.warn("OPENICF-475: manifest objectClassSchema missing OC ''{0}''; falling back to hardcoded", ocName);
                return false;
            }
        }

        for (String ocName : MANIFEST_OC_NAMES) {
            com.fasterxml.jackson.databind.JsonNode ocNode = manifestSchema.get(ocName);
            ObjectClassInfoBuilder ocBuilder = new ObjectClassInfoBuilder();
            ocBuilder.setType(ocName);

            com.fasterxml.jackson.databind.JsonNode attrs = ocNode.get("attributes");
            if (attrs == null || !attrs.isArray()) {
                LOG.warn("OPENICF-475: OC ''{0}'' has no attributes array; falling back to hardcoded", ocName);
                return false;
            }

            for (com.fasterxml.jackson.databind.JsonNode attr : attrs) {
                String name = attr.has("name") ? attr.get("name").asText() : null;
                if (name == null || name.isEmpty()) {
                    continue;
                }

                String typeName = attr.has("type") ? attr.get("type").asText() : "string";
                Class<?> javaType = TYPE_MAP.getOrDefault(typeName, String.class);

                // Parse flags
                EnumSet<AttributeInfo.Flags> flags = EnumSet.noneOf(AttributeInfo.Flags.class);
                com.fasterxml.jackson.databind.JsonNode flagsNode = attr.get("flags");
                if (flagsNode != null && flagsNode.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode flagNode : flagsNode) {
                        String flagStr = flagNode.asText();
                        AttributeInfo.Flags flag = FLAG_MAP.get(flagStr);
                        if (flag != null) {
                            flags.add(flag);
                        } else {
                            LOG.warn("OPENICF-475: Unknown flag ''{0}'' on {1}.{2}; skipping flag", flagStr, ocName, name);
                        }
                    }
                }

                if (flags.isEmpty()) {
                    ocBuilder.addAttributeInfo(AttributeInfoBuilder.build(name, javaType));
                } else {
                    ocBuilder.addAttributeInfo(AttributeInfoBuilder.build(name, javaType, flags));
                }
            }

            builder.defineObjectClass(ocBuilder.build());
        }

        LOG.ok("OPENICF-475: Built {0} object classes from manifest objectClassSchema", MANIFEST_OC_NAMES.size());
        return true;
    }

    /**
     * OPENICF-475: Hardcoded fallback for the 5 non-__ACCOUNT__ OCs.
     * Identical to the pre-475 schema definitions. Used when the manifest
     * is unavailable or incomplete (pre-474 Lambda, S3 read failure, etc.).
     */
    private void buildHardcodedObjectClasses(SchemaBuilder builder) {
        LOG.ok("OPENICF-475: Building 5 non-__ACCOUNT__ OCs from hardcoded definitions (fallback)");

        // agentKnowledgeBase
        ObjectClassInfoBuilder kb = new ObjectClassInfoBuilder();
        kb.setType(AwsBedrockConstants.OC_KNOWLEDGE_BASE);
        kb.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_PLATFORM, String.class));
        kb.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_AGENT_ID, String.class));
        kb.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_AGENT_VERSION, String.class));
        kb.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_KNOWLEDGE_BASE_ID, String.class));
        kb.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_DESCRIPTION, String.class));
        kb.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_STATUS, String.class));
        kb.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_UPDATED_AT, String.class));
        builder.defineObjectClass(kb.build());

        // agentGuardrail
        ObjectClassInfoBuilder guardrail = new ObjectClassInfoBuilder();
        guardrail.setType(AwsBedrockConstants.OC_GUARDRAIL);
        guardrail.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_PLATFORM, String.class));
        guardrail.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_AGENT_ID, String.class));
        guardrail.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_AGENT_VERSION, String.class));
        guardrail.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_GUARDRAIL_ID, String.class));
        guardrail.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_GUARDRAIL_VERSION, String.class));
        guardrail.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_GUARDRAIL_NAME, String.class));
        guardrail.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_GUARDRAIL_DESCRIPTION, String.class));
        guardrail.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_GUARDRAIL_STATE, String.class));
        guardrail.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_GUARDRAIL_DEPLOYMENT_STATUS, String.class));
        guardrail.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_GUARDRAIL_INPUT_ACTION, String.class));
        guardrail.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_GUARDRAIL_OUTPUT_ACTION, String.class));
        builder.defineObjectClass(guardrail.build());

        // agentTool
        ObjectClassInfoBuilder tool = new ObjectClassInfoBuilder();
        tool.setType(AwsBedrockConstants.OC_TOOL);
        tool.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_PLATFORM, String.class));
        tool.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_AGENT_ID, String.class));
        tool.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_AGENT_VERSION, String.class));
        tool.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_ACTION_GROUP_ID, String.class));
        tool.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_ACTION_GROUP_NAME, String.class));
        tool.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_DESCRIPTION, String.class));
        tool.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_STATUS, String.class));
        tool.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_ACTION_GROUP_EXECUTOR_ARN, String.class));
        tool.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_ACTION_GROUP_PARENT_SIGNATURE, String.class));
        tool.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_ACTION_GROUP_SCHEMA_URI, String.class));
        builder.defineObjectClass(tool.build());

        // agentIdentityBinding
        ObjectClassInfoBuilder idBinding = new ObjectClassInfoBuilder();
        idBinding.setType(AwsBedrockConstants.OC_IDENTITY_BINDING);
        idBinding.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_PLATFORM, String.class));
        idBinding.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_AGENT_ID, String.class));
        idBinding.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_KIND, String.class));
        idBinding.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_PRINCIPAL, String.class));
        idBinding.addAttributeInfo(AttributeInfoBuilder.build(
                AwsBedrockConstants.ATTR_PERMISSIONS, String.class, EnumSet.of(AttributeInfo.Flags.MULTIVALUED)));
        builder.defineObjectClass(idBinding.build());

        // agentToolCredentials
        ObjectClassInfoBuilder tc = new ObjectClassInfoBuilder();
        tc.setType(AwsBedrockConstants.OC_TOOL_CREDENTIALS);
        tc.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_TC_ID, String.class));
        tc.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_AGENT_ID, String.class));
        tc.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_TC_AGENT_ARN, String.class));
        tc.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_TC_AGENT_SERVICE_ROLE_ARN, String.class));
        tc.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_TC_ACTION_GROUP_ID, String.class));
        tc.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_TC_ACTION_GROUP_NAME, String.class));
        tc.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_TC_ACTION_GROUP_STATE, String.class));
        tc.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_TC_CREDENTIAL_TYPE, String.class));
        tc.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_TC_CREDENTIAL_REF, String.class));
        tc.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_TC_API_SCHEMA_SOURCE, String.class));
        tc.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_TC_FUNCTION_SCHEMA, String.class));
        tc.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_TC_ACCOUNT_ID, String.class));
        tc.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_TC_REGION, String.class));
        tc.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_TC_LAMBDA_EXECUTION_ROLE_ARN, String.class));
        builder.defineObjectClass(tc.build());
    }

    // ---------------------------------------------------------------------
    // TestOp
    // ---------------------------------------------------------------------

    @Override
    public void test() {
        LOG.ok("Testing AwsBedrockConnector.");
        if (connection == null) {
            throw new IllegalStateException("Connection is not initialized.");
        }
        connection.test();
        LOG.ok("AwsBedrockConnector test completed successfully.");
    }

    // ---------------------------------------------------------------------
    // SearchOp (used for both GET and QUERY, with paging support)
    // ---------------------------------------------------------------------

    @Override
    public FilterTranslator<Filter> createFilterTranslator(ObjectClass objectClass, OperationOptions operationOptions) {
        return CollectionUtil::newList;
    }

    @Override
    public void executeQuery(ObjectClass objectClass,
                             Filter query,
                             ResultsHandler handler,
                             OperationOptions options) {

        if (crudService == null) {
            throw new IllegalStateException("CRUD service is not initialized.");
        }

        if (options != null && options.getPageSize() != null && options.getPageSize() < 0) {
            throw new InvalidAttributeValueException("Page size should not be less than zero.");
        }

        LOG.ok("executeQuery called for objectClass {0}, filter {1}", objectClass, query);

        // Detect GET vs QUERY based on UID filter
        Uid uid = getUidIfGetOperation(query);

        // GET-by-UID: no paging, just a single object
        if (uid != null) {
            handleGetByUid(objectClass, uid, handler, options);
            return;
        }

        // QUERY: apply pageSize and cookie semantics
        int pageSize = (options != null && options.getPageSize() != null)
                ? options.getPageSize()
                : -1;

        int offset = 0;
        if (options != null && options.getPagedResultsCookie() != null) {
            try {
                offset = Integer.parseInt(options.getPagedResultsCookie());
            } catch (NumberFormatException e) {
                LOG.warn(e, "Invalid pagedResultsCookie value: {0}", options.getPagedResultsCookie());
            }
        }

        PagingResultsHandler pagingHandler = new PagingResultsHandler(handler, offset, pageSize);

        if (objectClass.is(OC_ACCOUNT.getObjectClassValue())) {
            crudService.searchAgents(objectClass, query, pagingHandler, options);
        } else if (objectClass.is(OC_GUARDRAIL.getObjectClassValue())) {
            crudService.searchGuardrails(objectClass, query, pagingHandler, options);
        } else if (objectClass.is(OC_KNOWLEDGE_BASE.getObjectClassValue())) {
            crudService.searchKnowledgeBases(objectClass, query, pagingHandler, options);
        } else if (objectClass.is(OC_TOOL.getObjectClassValue())) {
            crudService.searchTools(objectClass, query, pagingHandler, options);
        } else if (objectClass.is(OC_IDENTITY_BINDING.getObjectClassValue())) {
            crudService.searchIdentityBindings(objectClass, query, pagingHandler, options);
            // OPENICF-431
        } else if (objectClass.is(OC_TOOL_CREDENTIALS.getObjectClassValue())) {
            crudService.searchToolCredentials(objectClass, query, pagingHandler, options);
        } else {
            throw new UnsupportedOperationException("Unsupported ObjectClass for search: " + objectClass);
        }

        emitSearchResult(handler, pagingHandler, offset);
    }

    // ---------------------------------------------------------------------
    // GET-by-UID helper (still uses executeQuery entry point)
    // ---------------------------------------------------------------------

    private void handleGetByUid(ObjectClass objectClass,
                                Uid uid,
                                ResultsHandler handler,
                                OperationOptions options) {

        ConnectorObject co = null;

        if (objectClass.is(OC_ACCOUNT.getObjectClassValue())) {
            co = crudService.getAgent(objectClass, uid, options);
        } else if (objectClass.is(OC_GUARDRAIL.getObjectClassValue())) {
            co = crudService.getGuardrail(objectClass, uid, options);
        } else if (objectClass.is(OC_KNOWLEDGE_BASE.getObjectClassValue())) {
            co = crudService.getKnowledgeBase(objectClass, uid, options);
        } else if (objectClass.is(OC_TOOL.getObjectClassValue())) {
            co = crudService.getTool(objectClass, uid, options);
        } else if (objectClass.is(OC_IDENTITY_BINDING.getObjectClassValue())) {
            co = crudService.getIdentityBinding(objectClass, uid, options);
            // OPENICF-431
        } else if (objectClass.is(OC_TOOL_CREDENTIALS.getObjectClassValue())) {
            co = crudService.getToolCredential(objectClass, uid, options);
        } else {
            throw new UnsupportedOperationException("Unsupported ObjectClass for GET: " + objectClass);
        }

        if (co != null) {
            handler.handle(co);
        }

        // For GET, emit a SearchResult with no cookie / remaining info
        if (handler instanceof SearchResultsHandler) {
            ((SearchResultsHandler) handler).handleResult(new SearchResult(null, -1));
        }
    }

    // ---------------------------------------------------------------------
    // Helper: detect GET-by-UID pattern
    // ---------------------------------------------------------------------

    private Uid getUidIfGetOperation(Filter query) {
        if (query instanceof EqualsFilter) {
            Attribute attr = ((EqualsFilter) query).getAttribute();
            if (attr != null && Uid.NAME.equals(attr.getName()) && !attr.getValue().isEmpty()) {
                Object value = attr.getValue().get(0);
                if (value instanceof String) {
                    return new Uid((String) value);
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Paging helpers
    // ---------------------------------------------------------------------

    /**
     * Wrapper handler that applies offset + pageSize semantics on top of the
     * underlying ResultsHandler while still letting the CRUD service iterate
     * over all matches (so we know totalCount).
     */
    private static final class PagingResultsHandler implements ResultsHandler {

        private final ResultsHandler delegate;
        private final int offset;
        private final int pageSize;  // -1 or 0 means "no limit"

        private int seen = 0;
        private int returned = 0;

        PagingResultsHandler(ResultsHandler delegate, int offset, int pageSize) {
            this.delegate = delegate;
            this.offset = Math.max(0, offset);
            this.pageSize = pageSize;
        }

        @Override
        public boolean handle(ConnectorObject obj) {
            seen++;

            // Skip until we reach the offset
            if (seen <= offset) {
                return true;
            }

            // If we have a pageSize limit and already returned that many, skip
            if (pageSize > 0 && returned >= pageSize) {
                // We still return true to let CRUD iterate all and update seen,
                // but we don't forward to the delegate anymore.
                return true;
            }

            boolean cont = delegate.handle(obj);
            if (cont) {
                returned++;
            }
            return cont;
        }

        int getSeen() {
            return seen;
        }

        int getReturned() {
            return returned;
        }
    }

    /**
     * Emits a SearchResult with cookie and remaining based on what the
     * PagingResultsHandler observed.
     */
    private void emitSearchResult(ResultsHandler handler,
                                  PagingResultsHandler pagingHandler,
                                  int offset) {
        if (!(handler instanceof SearchResultsHandler)) {
            return;
        }

        int totalCount = pagingHandler.getSeen();
        int returnedCount = pagingHandler.getReturned();

        String cookie = null;
        if (returnedCount > 0 && totalCount > offset + returnedCount) {
            cookie = String.valueOf(offset + returnedCount);
        }

        int remaining = (totalCount < 0 || returnedCount < 0)
                ? -1
                : Math.max(0, totalCount - (offset + returnedCount));

        ((SearchResultsHandler) handler).handleResult(new SearchResult(cookie, remaining));
    }

    // ---------------------------------------------------------------------
    // CreateOp (READ-ONLY)
    // ---------------------------------------------------------------------

    @Override
    public Uid create(ObjectClass objectClass,
                      Set<Attribute> createAttributes,
                      OperationOptions options) {
        throw new UnsupportedOperationException(
                "Create operation is not supported for AwsBedrockConnector (read-only).");
    }

    // ---------------------------------------------------------------------
    // DeleteOp (READ-ONLY)
    // ---------------------------------------------------------------------

    @Override
    public void delete(ObjectClass objectClass,
                       Uid uid,
                       OperationOptions options) {
        throw new UnsupportedOperationException(
                "Delete operation is not supported for AwsBedrockConnector (read-only).");
    }

    // ---------------------------------------------------------------------
    // UpdateOp (READ-ONLY)
    // ---------------------------------------------------------------------

    @Override
    public Uid update(ObjectClass objectClass,
                      Uid uid,
                      Set<Attribute> replaceAttributes,
                      OperationOptions options) {
        throw new UnsupportedOperationException(
                "Update operation is not supported for AwsBedrockConnector (read-only).");
    }
}