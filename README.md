# AWS Bedrock OpenICF Connector

An OpenICF/Java connector for **PingOne IDM / OpenIDM** that enables identity governance
platforms to discover and manage AWS Bedrock Agents and their associated governance surfaces.

The connector provides read-only visibility into:

- Bedrock Agents and their aliases (one `ConnectorObject` per alias)
- Guardrails attached to agents
- Knowledge bases associated with agents
- Action groups (tools) and their executor configurations
- IAM identity bindings (who can invoke each agent/alias)
- Tool credential classifications (what credentials agents use to call tools)

IAM binding data and tool credential data are produced offline by the
[`bedrock-core-tools-inventory`](https://github.com/srallapally/bedrock-core-tools-inventory)
Lambda. **That Lambda must be deployed and producing output before the connector can
populate `agentPrincipals` or `agentToolCredentials`.** See the inventory README for
deployment instructions.

---

## Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [Build](#2-build)
3. [Deployment](#3-deployment)
4. [Configuration Properties](#4-configuration-properties)
5. [Object Classes and Schema](#5-object-classes-and-schema)
   - 5.1 [`__ACCOUNT__` — Agent / Agent Alias](#51-__account__--agent--agent-alias)
   - 5.2 [`agentGuardrail`](#52-agentguardrail)
   - 5.3 [`agentKnowledgeBase`](#53-agentknowledgebase)
   - 5.4 [`agentTool`](#54-agenttool)
   - 5.5 [`agentIdentityBinding`](#55-agentidentitybinding)
   - 5.6 [`agentToolCredentials`](#56-agenttoolcredentials)
6. [UID Format](#6-uid-format)
7. [Supported Operations](#7-supported-operations)
8. [AWS IAM Permissions](#8-aws-iam-permissions)
9. [S3 Bindings Cache](#9-s3-bindings-cache)
10. [Known Limitations](#10-known-limitations)
11. [Open Tickets](#11-open-tickets)

---

## 1. Prerequisites

| Requirement | Version |
|---|---|
| Java | 11 |
| Maven | 3.6+ |
| PingOne IDM / OpenIDM | 7.x |
| AWS SDK for Java | v2 (bundled in connector JAR) |
| `bedrock-core-tools-inventory` Lambda | Deployed and producing output to the inventory S3 bucket |

AWS credentials available to the IDM host via one of:
- Environment variables (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`)
- `~/.aws/credentials` file
- EC2 / ECS instance profile
- Explicit access key and secret configured on the connector (see [Section 4](#4-configuration-properties))

---

## 2. Build

```bash
git clone <connector-repo-url>
cd aws-bedrock-connector

mvn clean package -DskipTests
```

The connector JAR is produced at:

```
target/aws-bedrock-connector-<version>.jar
```

Verify the bundle before deployment:

```bash
# All dependency JARs must be under lib/, not at the root
unzip -l target/aws-bedrock-connector-*.jar | grep "lib/"

# Bundle-ClassPath must list lib/ prefixed entries
unzip -p target/aws-bedrock-connector-*.jar META-INF/MANIFEST.MF | grep Bundle-ClassPath

# Framework version must be exactly 1.5
unzip -p target/aws-bedrock-connector-*.jar META-INF/MANIFEST.MF | grep ConnectorBundle-FrameworkVersion
```

Expected `ConnectorBundle-FrameworkVersion` output: `ConnectorBundle-FrameworkVersion: 1.5`

If `Bundle-ClassPath` shows JARs at the root without a `lib/` prefix, the
`<Embed-Directory>lib</Embed-Directory>` instruction is missing from `pom.xml`.

---

## 3. Deployment

**3a — Copy the JAR to the connectors directory:**

```bash
cp target/aws-bedrock-connector-*.jar $IDM_HOME/connectors/
```

**3b — Restart IDM or hot-deploy:**

```bash
# Hot-deploy via REST (no restart required in 7.x)
curl -X POST \
  "https://<idm-host>/openidm/system?_action=availableConnectors" \
  -H "Authorization: Bearer <token>"
```

**3c — Verify the connector is visible:**

```bash
curl -s \
  "https://<idm-host>/openidm/system?_action=availableConnectors" \
  -H "Authorization: Bearer <token>" | python3 -m json.tool | grep bedrock
```

**3d — Register the connector configuration:**

```bash
curl -X PUT \
  "https://<idm-host>/openidm/config/provisioner.openicf/aws-bedrock" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d @provisioner.openicf.aws-bedrock.json
```

See [Section 4](#4-configuration-properties) for the full set of properties to include
in `provisioner.openicf.aws-bedrock.json`.

---

## 4. Configuration Properties

All properties are set in the connector's `configurationProperties` block in
`provisioner.openicf.aws-bedrock.json`.

| Property | Type | Required | Default | Description |
|---|---|---|---|---|
| `region` | String | Yes | `us-east-1` | AWS region. One connector instance per region. |
| `accountId` | String | Yes | — | AWS account ID (12-digit string). |
| `useDefaultCredentialsProvider` | Boolean | No | `true` | `true` = use `DefaultCredentialsProvider` chain (env vars, `~/.aws/credentials`, instance profile). `false` = use explicit `accessKeyId` + `secretAccessKey`. |
| `accessKeyId` | String | No | — | AWS access key ID. Used only when `useDefaultCredentialsProvider=false`. |
| `secretAccessKey` | GuardedString | No | — | AWS secret access key. Used only when `useDefaultCredentialsProvider=false`. Stored encrypted. |
| `inventoryBucket` | String | Yes | — | S3 bucket name containing the inventory artifacts produced by `bedrock-core-tools-inventory`. Bucket name only — no `s3://` prefix, no trailing slash. Example: `bedrock-core-inventory`. |
| `bindingsCacheTtlSeconds` | Integer | No | `300` | TTL in seconds for the in-memory bindings cache and tool credentials cache. Both caches share this TTL. |

**Minimal configuration example** (default credentials chain):

```json
{
  "connectorRef": {
    "connectorType": "org.forgerock.openicf.connectors.awsbedrock.AwsBedrockConnector",
    "bundleName": "org.forgerock.openicf.connectors.aws-bedrock-connector",
    "bundleVersion": "1.0.0"
  },
  "configurationProperties": {
    "region": "us-east-1",
    "accountId": "470686885243",
    "useDefaultCredentialsProvider": true,
    "inventoryBucket": "bedrock-core-inventory",
    "bindingsCacheTtlSeconds": 300
  }
}
```

**Explicit credentials example:**

```json
{
  "configurationProperties": {
    "region": "us-east-1",
    "accountId": "470686885243",
    "useDefaultCredentialsProvider": false,
    "accessKeyId": "AKIAIOSFODNN7EXAMPLE",
    "secretAccessKey": {
      "$crypto": {
        "type": "x-simple-encryption",
        "value": { ... }
      }
    },
    "inventoryBucket": "bedrock-core-inventory",
    "bindingsCacheTtlSeconds": 300
  }
}
```

---

## 5. Object Classes and Schema

### 5.1 `__ACCOUNT__` — Agent / Agent Alias

The primary object class. Models identity at the alias level: one `ConnectorObject` is
emitted per agent alias. Agents with zero aliases emit one bare-agent object with
`aliasId` absent.

**UID format:** `agentId:aliasId` for alias objects; `agentId` for bare-agent objects.
See [Section 6](#6-uid-format).

| Attribute | Type | Multi-valued | Read-only | Description |
|---|---|---|---|---|
| `__UID__` | String | No | Yes | `agentId:aliasId` or `agentId` |
| `__NAME__` | String | No | No | Agent name |
| `agentId` | String | No | Yes | Bedrock agent ID |
| `agentArn` | String | No | Yes | Full agent ARN |
| `agentVersion` | String | No | Yes | Agent version (e.g. `DRAFT`) |
| `agentStatus` | String | No | Yes | Agent status |
| `aliasId` | String | No | Yes | Alias ID; absent on bare-agent objects |
| `aliasArn` | String | No | Yes | Full alias ARN; absent on bare-agent objects |
| `aliasName` | String | No | Yes | Alias display name; absent on bare-agent objects |
| `aliasStatus` | String | No | Yes | Alias status; absent on bare-agent objects |
| `roleArn` | String | No | Yes | IAM role ARN the agent assumes (`agentResourceRoleArn`) |
| `foundationModel` | String | No | Yes | Foundation model ID |
| `description` | String | No | No | Agent description |
| `idleSessionTTLInSeconds` | Integer | No | Yes | Session TTL |
| `createdAt` | String | No | Yes | Agent creation timestamp (ISO-8601) |
| `updatedAt` | String | No | Yes | Agent last updated timestamp |
| `preparedAt` | String | No | Yes | Agent last prepared timestamp |
| `guardrailId` | String | No | Yes | Guardrail ID if configured |
| `guardrailVersion` | String | No | Yes | Guardrail version if configured |
| `customerEncryptionKeyArn` | String | No | Yes | KMS key ARN if set |
| `failureReasons` | String | Yes | Yes | Failure reasons if agent is in error state |
| `recommendedActions` | String | Yes | Yes | Recommended remediation actions |
| `agentCollaboration` | String | No | Yes | Multi-agent collaboration mode |
| `connectedAgents` | String | Yes | Yes | Alias ARNs of collaborating agents |
| `tools` | String | Yes | Yes | Action group IDs associated with this agent |
| `knowledgeBases` | String | Yes | Yes | Knowledge base IDs associated with this agent |
| `agentPrincipals` | String | Yes | Yes | IAM principal ARNs that can invoke this alias (from S3 bindings cache). Scoped to this specific alias when `aliasId` is present. |
| `toolCredentialIds` | String | Yes | Yes | Tool credential record IDs (`tc-*`) associated with this agent (from S3 tool credentials cache). |
| `region` | String | No | Yes | AWS region |

### 5.2 `agentGuardrail`

One object per guardrail referenced by any agent. Enriched with content policy details.

**UID format:** `agentId:guardrailId:guardrailVersion`

| Attribute | Type | Multi-valued | Description |
|---|---|---|---|
| `__UID__` | String | No | `agentId:guardrailId:guardrailVersion` |
| `__NAME__` | String | No | Guardrail name |
| `guardrailId` | String | No | Guardrail ID |
| `guardrailVersion` | String | No | Guardrail version |
| `description` | String | No | Description |
| `status` | String | No | Deployment status |
| `inputAction` | String | No | JSON array of content filter objects (type, inputStrength, inputAction, inputModalities, etc.) |
| `outputAction` | String | No | Same payload as `inputAction` — full content policy filters |
| `agentId` | String | No | Agent ID that references this guardrail |
| `region` | String | No | AWS region |

`inputAction` and `outputAction` are absent when the guardrail has no content filters
configured.

### 5.3 `agentKnowledgeBase`

One object per knowledge base association per agent.

**UID format:** `agentId:knowledgeBaseId`

| Attribute | Type | Multi-valued | Description |
|---|---|---|---|
| `__UID__` | String | No | `agentId:knowledgeBaseId` |
| `__NAME__` | String | No | Knowledge base ID (display name) |
| `knowledgeBaseId` | String | No | Knowledge base ID |
| `description` | String | No | Description |
| `knowledgeBaseState` | String | No | `ENABLED` or `DISABLED` |
| `updatedAt` | String | No | Last update timestamp |
| `agentId` | String | No | Owning agent ID |
| `region` | String | No | AWS region |

### 5.4 `agentTool`

One object per action group per agent. Enriched with executor configuration.

**UID format:** `agentId:actionGroupId`

| Attribute | Type | Multi-valued | Description |
|---|---|---|---|
| `__UID__` | String | No | `agentId:actionGroupId` |
| `__NAME__` | String | No | Action group name |
| `actionGroupId` | String | No | Action group ID |
| `actionGroupState` | String | No | `ENABLED` or `DISABLED` |
| `description` | String | No | Description |
| `executorArn` | String | No | Lambda executor ARN (Lambda-backed groups only) |
| `schemaUri` | String | No | S3 URI of OpenAPI schema (S3-backed schema only) |
| `parentActionGroupSignature` | String | No | System action group type (e.g. `AMAZON.UserInput`, `AMAZON.CodeInterpreter`). Absent on custom Lambda-backed groups. |
| `agentId` | String | No | Owning agent ID |
| `region` | String | No | AWS region |

### 5.5 `agentIdentityBinding`

One object per IAM principal per agent/alias binding, sourced from
`latest/agent-bindings.json` in the inventory S3 bucket.

**UID format:** `agentId:scope:principalArn` where `agentId` is `*` for wildcard bindings
and `scope` is `AGENT` or `ALIAS`.

| Attribute | Type | Multi-valued | Description |
|---|---|---|---|
| `__UID__` | String | No | `agentId:scope:principalArn` |
| `__NAME__` | String | No | Principal ARN |
| `agentId` | String | No | Agent ID; `*` for wildcard bindings |
| `aliasId` | String | No | Alias ID; absent for agent-level and wildcard bindings |
| `agentArn` | String | No | Agent ARN; absent for wildcard bindings |
| `aliasArn` | String | No | Alias ARN; absent for agent-level and wildcard bindings |
| `principalArn` | String | No | IAM principal ARN |
| `principalType` | String | No | `ROLE` or `USER` |
| `principalName` | String | No | Role or user name |
| `principalAccountId` | String | No | AWS account ID of the principal |
| `wildcard` | String | No | `"true"` or `"false"` |
| `bindingOrigin` | String | No | `DIRECT_ROLE_POLICY`, `DIRECT_USER_POLICY`, or `GROUP_INHERITED` |
| `sourcePrincipalArn` | String | No | Group ARN when `bindingOrigin=GROUP_INHERITED`; same as `principalArn` otherwise |
| `sourcePrincipalType` | String | No | `group` when inherited |
| `sourcePrincipalName` | String | No | Group name when inherited |
| `conditionJson` | String | No | Serialized IAM condition block; absent when no condition |
| `scope` | String | No | `AGENT` or `ALIAS` |
| `region` | String | No | AWS region |

### 5.6 `agentToolCredentials`

One object per action group across all agents, sourced from
`latest/agent-tool-credentials.json` in the inventory S3 bucket.

**UID format:** `id` field from the Lambda output (`tc-{sha256[:16]}`).

| Attribute | Type | Multi-valued | Description |
|---|---|---|---|
| `__UID__` | String | No | `tc-{sha256[:16]}` |
| `__NAME__` | String | No | Action group name (falls back to action group ID) |
| `id` | String | No | Same as `__UID__` |
| `agentId` | String | No | Owning agent ID |
| `agentArn` | String | No | Full agent ARN |
| `agentServiceRoleArn` | String | No | IAM role the agent assumes when invoking this action group |
| `actionGroupId` | String | No | Action group ID |
| `actionGroupName` | String | No | Action group name |
| `actionGroupState` | String | No | `ENABLED` or `DISABLED` |
| `credentialType` | String | No | `LAMBDA_EXECUTION_ROLE`, `S3_READ`, `CONFLUENCE_SECRET`, or `NONE` |
| `credentialRef` | String | No | ARN identifying the credential surface; never the credential value |
| `apiSchemaSource` | String | No | `S3`, `INLINE`, or absent |
| `functionSchema` | String | No | `"true"` when function-definition schema; `"false"` for OpenAPI |
| `accountId` | String | No | AWS account ID |
| `region` | String | No | AWS region |
| `lambdaExecutionRoleArn` | String | No | IAM execution role ARN of the Lambda function. Populated for `LAMBDA_EXECUTION_ROLE` groups; absent for all other types. |

---

## 6. UID Format

UIDs use `:` as separator. The separator character is literal — agent IDs, alias IDs, and
guardrail IDs do not contain `:`. `split(separator, 3)` with a limit is used internally
to handle the three-segment UIDs on `agentGuardrail` and `agentIdentityBinding`.

| Object class | UID format | Example |
|---|---|---|
| `__ACCOUNT__` (alias) | `agentId:aliasId` | `7VIHRMHHME:ET1YD2TDHF` |
| `__ACCOUNT__` (bare agent) | `agentId` | `7VIHRMHHME` |
| `agentGuardrail` | `agentId:guardrailId:guardrailVersion` | `7VIHRMHHME:abc123:1` |
| `agentKnowledgeBase` | `agentId:knowledgeBaseId` | `7VIHRMHHME:kb-abc123` |
| `agentTool` | `agentId:actionGroupId` | `7VIHRMHHME:ag-abc123` |
| `agentIdentityBinding` | `agentId:scope:principalArn` | `7VIHRMHHME:ALIAS:arn:aws:iam::470686885243:role/my-role` |
| `agentToolCredentials` | `tc-{sha256[:16]}` | `tc-a1b2c3d4e5f6a7b8` |

---

## 7. Supported Operations

| Operation | Supported | Notes |
|---|---|---|
| `SearchOp` (reconciliation) | Yes | All object classes |
| `GetOp` (single object) | Yes | All object classes |
| `SchemaOp` | Yes | Static schema |
| `TestOp` | Yes | Validates connectivity and credential chain |
| `CreateOp` | No | Read-only connector |
| `UpdateOp` | No | Read-only connector |
| `DeleteOp` | No | Read-only connector |
| `SyncOp` | No | Not applicable — no delta stream from Bedrock |

---

## 8. AWS IAM Permissions

Minimum permissions required for the IAM identity used by the connector. No IAM read
permissions are required — IAM scanning is handled exclusively by the inventory Lambda.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "BedrockAgentRead",
      "Effect": "Allow",
      "Action": [
        "bedrock:ListAgents",
        "bedrock:GetAgent",
        "bedrock:ListAgentAliases",
        "bedrock:GetAgentAlias",
        "bedrock:ListAgentActionGroups",
        "bedrock:GetAgentActionGroup",
        "bedrock:ListAgentKnowledgeBases",
        "bedrock:ListAgentCollaborators",
        "bedrock:GetGuardrail"
      ],
      "Resource": "*"
    },
    {
      "Sid": "S3InventoryRead",
      "Effect": "Allow",
      "Action": "s3:GetObject",
      "Resource": "arn:aws:s3:::{inventoryBucket}/*"
    }
  ]
}
```

Replace `{inventoryBucket}` with the value of the `inventoryBucket` configuration property.

---

## 9. S3 Bindings Cache

The connector reads two artifacts from S3 on demand and caches them in memory:

| Artifact | S3 key | Connector cache |
|---|---|---|
| Agent identity bindings | `latest/agent-bindings.json` | `bindingsCache` |
| Tool credential classifications | `latest/agent-tool-credentials.json` | `toolCredentialsCache` |

Both caches share the `bindingsCacheTtlSeconds` TTL (default 300 seconds). Cache misses
trigger a synchronous S3 read. Thread safety is enforced via double-checked locking.

**On cache miss (`NoSuchKeyException`):** WARNING is logged. `agentPrincipals` is absent
on all agent/alias objects; `toolCredentialIds` is absent on all agent/alias objects.
`agentIdentityBinding` and `agentToolCredentials` search operations return empty results.
This is the expected state before the inventory Lambda has run for the first time.

**On cache read error:** ERROR is logged. Same behavior as cache miss — empty result sets,
no exception propagated to IDM reconciliation.

The S3 read path uses the `inventoryBucket` configuration property for the bucket name.
Key paths are fixed constants: `latest/agent-bindings.json` and
`latest/agent-tool-credentials.json`. The bucket name must not be included in the key.

---

## 10. Known Limitations

**Pagination (OPENICF-423 — Open).**
All list calls (`listAgents`, `listAgentAliases`, `listAgentActionGroups`,
`listAgentKnowledgeBases`, `listAgentCollaborators`) use a single-page call with
`maxResults(100)`. Environments with more than 100 of any of these resources will silently
return only the first page. `listAgentsPaginated()` exists in `AwsBedrockClient` but is
not wired into the search paths. This blocks production use at scale.

**Read-only.**
The connector does not support create, update, or delete operations. Bedrock agent
lifecycle management is not in scope.

**Single region per instance.**
One connector instance covers one AWS region. Configure one provisioner per target region.

**Bindings cache freshness.**
`agentPrincipals` and `agentToolCredentials` reflect the last inventory Lambda run, not
real-time IAM state. The default cache TTL is 5 minutes; the inventory Lambda runs every
15 minutes. The maximum staleness of binding data seen by the connector is
`bindingsCacheTtlSeconds + inventory-lambda-schedule-interval` (default: ~20 minutes).

---

## 11. Open Tickets

| Ticket | Status | Description |
|---|---|---|
| OPENICF-423 | **Open** | Pagination across all list methods — blocks production at >100 agents, aliases, action groups, KBs, or collaborators |
