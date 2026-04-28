// src/main/java/org/forgerock/openicf/connectors/awsbedrock/utils/AwsBedrockConstants.java
package org.forgerock.openicf.connectors.awsbedrock.utils;

/**
 * Shared constants for the AWS Bedrock connector.
 *
 * This class defines:
 * - Connector-level identifiers
 * - Object class names
 * - Attribute names used across ConnectorObject mappings
 */
public abstract class AwsBedrockConstants {

    // ---------------------------------------------------------------------
    // Connector identification
    // ---------------------------------------------------------------------
    public static final String CONNECTOR_NAME = "awsbedrock";

    // Default region if none is explicitly configured
    public static final String DEFAULT_REGION = "us-east-1";

    // UID separator for composite identifiers (agentId:guardrailId:guardrailVersion, etc.)
    public static final String UID_SEPARATOR = ":";

    // ---------------------------------------------------------------------
    // Object class names
    // ---------------------------------------------------------------------
    public static final String OC_AGENT = "agent";
    public static final String OC_GUARDRAIL = "agentGuardrail";
    public static final String OC_TOOL = "agentTool";
    public static final String OC_IDENTITY_BINDING = "agentIdentityBinding";
    public static final String OC_KNOWLEDGE_BASE = "agentKnowledgeBase";
    // OPENICF-431
    public static final String OC_TOOL_CREDENTIALS = "agentToolCredentials";

    // ---------------------------------------------------------------------
    // S3 inventory key paths (bucket configured via inventoryBucket property)
    // ---------------------------------------------------------------------
    // OPENICF-431
    public static final String AGENT_BINDINGS_S3_KEY =
            "bedrock-core-inventory/latest/agent-bindings.json";
    public static final String TOOL_CREDENTIALS_S3_KEY =
            "bedrock-core-inventory/latest/agent-tool-credentials.json";

    // ---------------------------------------------------------------------
    // Common attribute names
    // ---------------------------------------------------------------------
    public static final String ATTR_PLATFORM = "platform";
    public static final String ATTR_AGENT_ID = "agentId";
    public static final String ATTR_AGENT_VERSION = "agentVersion";

    // ---------------------------------------------------------------------
    // Agent attributes
    // ---------------------------------------------------------------------
    public static final String ATTR_VERSION = "version";
    public static final String ATTR_STATUS = "status";
    public static final String ATTR_DESCRIPTION = "description";
    public static final String ATTR_FOUNDATION_MODEL = "foundationModel";
    public static final String ATTR_ROLE_ARN = "roleArn";
    public static final String ATTR_IDLE_TTL = "idleSessionTtlSeconds";
    public static final String ATTR_CREATED_AT = "createdAt";
    public static final String ATTR_UPDATED_AT = "updatedAt";
    public static final String ATTR_TOOLS = "tools";
    public static final String ATTR_KNOWLEDGE_BASES = "knowledgeBases";
    public static final String ATTR_GUARDRAIL_ID = "guardrailId";
    public static final String ATTR_GUARDRAIL_VERSION = "guardrailVersion";
    public static final String ATTR_AGENT_ARN = "agentArn";
    public static final String ATTR_AGENT_NAME = "agentName";
    public static final String ATTR_CUSTOMER_ENCRYPTION_KEY_ARN = "customerEncryptionKeyArn";
    public static final String ATTR_FAILURE_REASONS = "failureReasons";
    public static final String ATTR_RECOMMENDED_ACTIONS = "recommendedActions";
    public static final String ATTR_PREPARED_AT = "preparedAt";
    // OPENICF-420
    // Alias attributes
    public static final String ATTR_ALIAS_ID = "aliasId";
    public static final String ATTR_ALIAS_NAME = "aliasName";
    public static final String ATTR_ALIAS_STATUS = "agentAliasStatus";
    // OPENICF-431: Forward pointer from agent/alias to its tool credential records
    public static final String ATTR_TOOL_CREDENTIAL_IDS = "toolCredentialIds";
    // Knowledge base attributes
    // ---------------------------------------------------------------------
    public static final String ATTR_KNOWLEDGE_BASE_ID = "knowledgeBaseId";
    public static final String ATTR_KNOWLEDGE_BASE_STATE = "knowledgeBaseState";
    // ---------------------------------------------------------------------
    // Guardrail attributes
    // ---------------------------------------------------------------------
    public static final String ATTR_GUARDRAIL_STATE = "state";
    public static final String ATTR_GUARDRAIL_NAME = "guardrailName";
    public static final String ATTR_GUARDRAIL_DESCRIPTION = "guardrailDescription";
    public static final String ATTR_GUARDRAIL_DEPLOYMENT_STATUS = "deploymentStatus";
    public static final String ATTR_GUARDRAIL_INPUT_ACTION = "inputAction";
    public static final String ATTR_GUARDRAIL_OUTPUT_ACTION = "outputAction";

    public static final String ATTR_GUARDRAIL_ARN = "guardrailArn";
    public static final String ATTR_GUARDRAIL_TIER = "contentFilterTier";
    public static final String ATTR_GUARDRAIL_BLOCKED_INPUT_MESSAGE = "blockedInputMessaging";
    public static final String ATTR_GUARDRAIL_BLOCKED_OUTPUT_MESSAGE = "blockedOutputMessaging";

    // ---------------------------------------------------------------------
    // Tool (Action Group) attributes
    // ---------------------------------------------------------------------
    public static final String ATTR_ACTION_GROUP_ID = "actionGroupId";
    public static final String ATTR_ACTION_GROUP_NAME = "actionGroupName";
    public static final String ATTR_ACTION_GROUP_EXECUTOR_ARN = "executorArn";
    public static final String ATTR_ACTION_GROUP_PARENT_SIGNATURE = "parentActionGroupSignature";
    public static final String ATTR_ACTION_GROUP_SCHEMA_URI = "schemaUri";

    // ---------------------------------------------------------------------
    // agentToolCredentials attributes (OPENICF-431)
    // ---------------------------------------------------------------------
    public static final String ATTR_TC_ID = "id";
    public static final String ATTR_TC_AGENT_ARN = "agentArn";
    public static final String ATTR_TC_AGENT_SERVICE_ROLE_ARN = "agentServiceRoleArn";
    public static final String ATTR_TC_ACTION_GROUP_ID = "actionGroupId";
    public static final String ATTR_TC_ACTION_GROUP_NAME = "actionGroupName";
    public static final String ATTR_TC_ACTION_GROUP_STATE = "actionGroupState";
    public static final String ATTR_TC_CREDENTIAL_TYPE = "credentialType";
    public static final String ATTR_TC_CREDENTIAL_REF = "credentialRef";
    public static final String ATTR_TC_API_SCHEMA_SOURCE = "apiSchemaSource";
    public static final String ATTR_TC_FUNCTION_SCHEMA = "functionSchema";
    public static final String ATTR_TC_ACCOUNT_ID = "accountId";
    public static final String ATTR_TC_REGION = "region";
    // OPENICF-432: Lambda execution role — null until Python Lambda is updated
    public static final String ATTR_TC_LAMBDA_EXECUTION_ROLE_ARN = "lambdaExecutionRoleArn";

    // ---------------------------------------------------------------------
    // Identity binding attributes
    // ---------------------------------------------------------------------
    public static final String ATTR_KIND = "kind";
    public static final String ATTR_PRINCIPAL = "principal";
    public static final String ATTR_PERMISSIONS = "permissions";

    // NEW: virtual, computed principals on the agent
    public static final String ATTR_AGENT_PRINCIPALS = "agentPrincipals";
    //OPENICF-420
    // Region (stored per-object for multi-region visibility)
    public static final String ATTR_REGION = "region";

    // Multi-agent collaboration
    public static final String ATTR_AGENT_COLLABORATION = "agentCollaboration";
    public static final String ATTR_CONNECTED_AGENTS = "connectedAgents";

    private AwsBedrockConstants() {
        // prevent instantiation
    }
}