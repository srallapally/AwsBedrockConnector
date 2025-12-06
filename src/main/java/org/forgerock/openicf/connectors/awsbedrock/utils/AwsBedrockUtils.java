package org.forgerock.openicf.connectors.awsbedrock.utils;

import static org.forgerock.openicf.connectors.awsbedrock.utils.AwsBedrockConstants.UID_SEPARATOR;

/**
 * General-purpose utilities for the AWS Bedrock connector.
 *
 * Includes:
 * - UID builder/parsers for agents, guardrails, and tools
 * - Small helper key records used by CRUDQ helper methods
 */
public final class AwsBedrockUtils {

    /**
     * Simple key for an agent (Bedrock agentId is globally unique).
     */
    public record AgentKey(String agentId) { }

    /**
     * Composite key for a guardrail attached to an agent.
     */
    public record GuardrailKey(String agentId, String guardrailId, String guardrailVersion) { }

    /**
     * Composite key for a tool (Action Group) attached to an agent.
     */
    public record ToolKey(String agentId, String actionGroupId) { }

    /**
     * Composite key for a knowledge base associated with an agent.
     */
    public record KnowledgeBaseKey(String agentId, String knowledgeBaseId) { }

    /**
     * Composite key for an identity binding (Agent ↔ Principal).
     *
     * scope = "AGENT" or "ALIAS".
     */
    public record IdentityBindingKey(String agentId,
                                     String scope,
                                     String principalArn) { }

    private AwsBedrockUtils() {
        // prevent construction
    }

    // ---------------------------------------------------------------------
    // Agent UID helpers
    // ---------------------------------------------------------------------

    /**
     * Converts an AWS Bedrock agentId into an OpenICF UID value.
     */
    public static String toAgentUid(String agentId) {
        return agentId;
    }

    /**
     * Extracts agentId from __UID__.
     */
    public static AgentKey fromAgentUid(String uid) {
        return new AgentKey(uid);
    }

    // ---------------------------------------------------------------------
    // Guardrail UID helpers
    // UID format:   agentId:guardrailId:guardrailVersion
    // ---------------------------------------------------------------------

    public static String toGuardrailUid(String agentId,
                                        String guardrailId,
                                        String guardrailVersion) {
        return agentId + UID_SEPARATOR + guardrailId + UID_SEPARATOR + guardrailVersion;
    }

    public static GuardrailKey fromGuardrailUid(String uid) {
        String[] parts = uid.split(UID_SEPARATOR, -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid guardrail UID format: " + uid);
        }
        return new GuardrailKey(parts[0], parts[1], parts[2]);
    }

    // ---------------------------------------------------------------------
    // Tool UID helpers
    // UID format:   agentId:actionGroupId
    // ---------------------------------------------------------------------

    public static String toToolUid(String agentId, String actionGroupId) {
        return agentId + UID_SEPARATOR + actionGroupId;
    }

    public static ToolKey fromToolUid(String uid) {
        String[] parts = uid.split(UID_SEPARATOR, -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid tool UID format: " + uid);
        }
        return new ToolKey(parts[0], parts[1]);
    }

    // ---------------------------------------------------------------------
    // Knowledge base UID helpers
    // UID format:   agentId:knowledgeBaseId
    // ---------------------------------------------------------------------

    public static String toKnowledgeBaseUid(String agentId, String knowledgeBaseId) {
        return agentId + UID_SEPARATOR + knowledgeBaseId;
    }

    public static KnowledgeBaseKey fromKnowledgeBaseUid(String uid) {
        String[] parts = uid.split(UID_SEPARATOR, -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid knowledge base UID format: " + uid);
        }
        return new KnowledgeBaseKey(parts[0], parts[1]);
    }

    // ---------------------------------------------------------------------
    // Identity binding UID helpers
    // UID format:   agentId:scope:principalArn
    // where scope = "AGENT" or "ALIAS"
    // ---------------------------------------------------------------------

    public static String toIdentityBindingUid(String agentId,
                                              String scope,
                                              String principalArn) {
        return agentId + UID_SEPARATOR + scope + UID_SEPARATOR + principalArn;
    }

    public static IdentityBindingKey fromIdentityBindingUid(String uid) {
        String[] parts = uid.split(UID_SEPARATOR, -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid identity binding UID format: " + uid);
        }
        return new IdentityBindingKey(parts[0], parts[1], parts[2]);
    }
}
