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
    public static final ObjectClass OC_AGENT =
            new ObjectClass(AwsBedrockConstants.OC_AGENT);
    public static final ObjectClass OC_GUARDRAIL =
            new ObjectClass(AwsBedrockConstants.OC_GUARDRAIL);
    public static final ObjectClass OC_TOOL =
            new ObjectClass(AwsBedrockConstants.OC_TOOL);
    public static final ObjectClass OC_IDENTITY_BINDING =
            new ObjectClass(AwsBedrockConstants.OC_IDENTITY_BINDING);
    public static final ObjectClass OC_KNOWLEDGE_BASE =
            new ObjectClass(AwsBedrockConstants.OC_KNOWLEDGE_BASE);
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
        agent.setType(AwsBedrockConstants.OC_AGENT);
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
                AwsBedrockConstants.ATTR_AGENT_PRINCIPALS, String.class,EnumSet.of(AttributeInfo.Flags.MULTIVALUED,
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
        builder.defineObjectClass(agent.build());

        // -----------------------------------------------------------------
        // agentKnowledgeBase object class (Knowledge Bases per Agent)
        // -----------------------------------------------------------------
        ObjectClassInfoBuilder kb = new ObjectClassInfoBuilder();
        kb.setType(AwsBedrockConstants.OC_KNOWLEDGE_BASE);

        // Common context
        kb.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_PLATFORM, String.class));
        kb.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_AGENT_ID, String.class));
        kb.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_AGENT_VERSION, String.class));

        // KB-specific
        kb.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_KNOWLEDGE_BASE_ID, String.class));
        kb.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_DESCRIPTION, String.class));
        kb.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_STATUS, String.class));
        kb.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_UPDATED_AT, String.class));

        builder.defineObjectClass(kb.build());

        // -----------------------------------------------------------------
        // agentGuardrail object class
        // -----------------------------------------------------------------
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

        // -----------------------------------------------------------------
        // agentTool object class (Action Groups)
        // -----------------------------------------------------------------
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

        // -----------------------------------------------------------------
        // agentIdentityBinding object class
        // -----------------------------------------------------------------
        ObjectClassInfoBuilder idBinding = new ObjectClassInfoBuilder();
        idBinding.setType(AwsBedrockConstants.OC_IDENTITY_BINDING);
        idBinding.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_PLATFORM, String.class));
        idBinding.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_AGENT_ID, String.class));
        idBinding.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_KIND, String.class));
        idBinding.addAttributeInfo(AttributeInfoBuilder.build(AwsBedrockConstants.ATTR_PRINCIPAL, String.class));
        idBinding.addAttributeInfo(AttributeInfoBuilder.build(
                AwsBedrockConstants.ATTR_PERMISSIONS, String.class, EnumSet.of(AttributeInfo.Flags.MULTIVALUED)));
        builder.defineObjectClass(idBinding.build());

        Schema schema = builder.build();
        LOG.ok("Schema built for AwsBedrockConnector.");
        return schema;
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

        if (objectClass.is(OC_AGENT.getObjectClassValue())) {
            crudService.searchAgents(objectClass, query, pagingHandler, options);
        } else if (objectClass.is(OC_GUARDRAIL.getObjectClassValue())) {
            crudService.searchGuardrails(objectClass, query, pagingHandler, options);
        } else if (objectClass.is(OC_KNOWLEDGE_BASE.getObjectClassValue())) {
            crudService.searchKnowledgeBases(objectClass, query, pagingHandler, options);
        } else if (objectClass.is(OC_TOOL.getObjectClassValue())) {
            crudService.searchTools(objectClass, query, pagingHandler, options);
        } else if (objectClass.is(OC_IDENTITY_BINDING.getObjectClassValue())) {
            crudService.searchIdentityBindings(objectClass, query, pagingHandler, options);
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

        if (objectClass.is(OC_AGENT.getObjectClassValue())) {
            co = crudService.getAgent(objectClass, uid, options);
        } else if (objectClass.is(OC_GUARDRAIL.getObjectClassValue())) {
            co = crudService.getGuardrail(objectClass, uid, options);
        } else if (objectClass.is(OC_KNOWLEDGE_BASE.getObjectClassValue())) {
            co = crudService.getKnowledgeBase(objectClass, uid, options);
        } else if (objectClass.is(OC_TOOL.getObjectClassValue())) {
            co = crudService.getTool(objectClass, uid, options);
        } else if (objectClass.is(OC_IDENTITY_BINDING.getObjectClassValue())) {
            co = crudService.getIdentityBinding(objectClass, uid, options);
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
