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
    // Identity binding attributes
    // ---------------------------------------------------------------------
    public static final String ATTR_KIND = "kind";
    public static final String ATTR_PRINCIPAL = "principal";
    public static final String ATTR_PERMISSIONS = "permissions";

    // NEW: virtual, computed principals on the agent
    public static final String ATTR_AGENT_PRINCIPALS = "agentPrincipals";

    private AwsBedrockConstants() {
        // prevent instantiation
    }
}
