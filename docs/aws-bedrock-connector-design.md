# AWS Bedrock Connector — System Design & Operations Guide

**Version:** 1.0
**Date:** 2026-04-27
**Platform:** PingOne IDM / OpenICF / AWS
**Status:** Released — OPENICF-423 (pagination) open; all other planned items complete

---

## Table of Contents

1. [Purpose and Scope](#1-purpose-and-scope)
2. [Architecture Overview](#2-architecture-overview)
3. [Object Classes and Schema](#3-object-classes-and-schema)
4. [S3 Artifact Schemas](#4-s3-artifact-schemas)
5. [Inventory Lambda — Build and Deployment](#5-inventory-lambda--build-and-deployment)
6. [Connector Configuration Reference](#6-connector-configuration-reference)
7. [AWS Permission Requirements](#7-aws-permission-requirements)
8. [Known Limitations and Operational Notes](#8-known-limitations-and-operational-notes)
9. [Enhancement Roadmap](#9-enhancement-roadmap)

---

## 1. Purpose and Scope

This document describes the end-to-end design of the AWS Bedrock OpenICF connector and its companion offline inventory Lambda. It is the authoritative reference for system architects, operations engineers, and developers who build, deploy, or maintain this system.

The system has two distinct components:

- **AWS Bedrock Connector** — a Java-based OpenICF bundle deployed in PingOne IDM. It performs live reconciliation of Bedrock Agents (at the alias level), their action groups, knowledge bases, and guardrails, and reads pre-computed identity binding and tool credential data from S3 artifacts.
- **`bedrock-core-tools-inventory` Lambda** — a Python 3.11 Lambda function triggered on a schedule by EventBridge. It scans IAM policies for agent and model access, classifies action group tool credentials, and emits normalized JSON artifacts to S3 for downstream connector ingestion.

This document covers:

- System architecture and component responsibilities
- Object classes and attribute schemas
- Live vs. offline data split and rationale
- S3 artifact schemas
- Lambda build, deployment, and scheduling
- Connector configuration reference
- AWS permission requirements
- Known limitations and operational guidance

---

## 2. Architecture Overview

### 2.1 System Components

The system follows a split-responsibility architecture. Agent alias discovery, action group collection, knowledge base collection, and guardrail enrichment run live in the connector during each IDM reconciliation. IAM identity binding collection and action group tool credential classification are moved offline to a scheduled Lambda that writes artifacts to S3. The connector reads those artifacts during reconciliation without making any IAM API calls itself.

| Component | Technology | Responsibility |
|---|---|---|
| AWS Bedrock Connector | Java, OpenICF, OSGi bundle | Live agent alias / action group / KB / guardrail discovery; reads S3 artifacts for identity bindings and tool credentials |
| `bedrock-core-tools-inventory` Lambda | Python 3.11, AWS Lambda | Offline IAM policy scanning (roles, users, group-inherited), agent binding normalization, action group credential classification, foundation model catalog; writes artifacts to S3 |
| Amazon S3 | S3 bucket (`bedrock-core-inventory`) | Durable artifact store; connector reads from `latest/` prefix; Lambda writes to `runs/` and promotes to `latest/` |
| Amazon EventBridge | AWS managed service | Triggers the Lambda on a schedule (default: every 15 minutes) |
| PingOne IDM | ForgeRock/Ping IDM runtime | Hosts the OpenICF connector; drives reconciliation and policy evaluation |

### 2.2 Data Flow

The following describes the data flow during a reconciliation cycle:

1. EventBridge fires on schedule and invokes `bedrock-core-tools-inventory`.
2. The Lambda scans IAM roles, users, and group memberships for `bedrock:InvokeAgent` / `bedrock:InvokeModel` policies, enumerates agents and their action groups, and classifies each action group's credential type.
3. The Lambda writes six JSON artifacts to a timestamped run prefix in S3, then promotes them atomically to the stable `latest/` prefix.
4. PingOne IDM triggers a reconciliation of the AWS Bedrock connector.
5. The connector calls the Bedrock Agent API to discover agents, aliases, action groups, knowledge bases, and guardrails (live).
6. The connector fetches `agent-bindings.json` and `agent-tool-credentials.json` from the S3 `latest/` prefix using the connector's configured IAM credentials. Both files are TTL-cached in memory (default 300 s).
7. The connector maps all collected data to OpenICF `ConnectorObject`s and returns them to IDM for reconciliation processing.

### 2.3 Live vs. Offline Split

The split between live and offline collection is deliberate. IAM policy scanning at scale requires listing all roles and users in the account, then fetching every attached and inline policy — an operation that is both API-intensive and subject to IAM `GetPolicyVersion` throttling (default 5 TPS). Moving IAM collection offline decouples IAM freshness from reconciliation latency and eliminates IAM read permissions from the connector's credential footprint entirely.

| Data Type | Collection Method | Rationale |
|---|---|---|
| Agents and aliases | Live — connector calls Bedrock Agent API | Alias creation/deletion is high-signal; must be current at reconciliation time |
| Action groups (tools) | Live — connector fetches per-agent | Tool configuration changes are governance-relevant and must be current |
| Knowledge bases | Live — connector fetches per-agent | Same rationale as action groups |
| Guardrails | Live — connector calls Bedrock `GetGuardrail` per agent | Derived from live agent payload guardrail configuration; must be current |
| Identity bindings (`bedrock:InvokeAgent`) | Offline — Lambda writes to S3 | Avoids full-account IAM scan on every reconciliation; decouples IAM freshness from IDM scheduling |
| Identity bindings (`bedrock:InvokeModel`) | Offline — Lambda writes to S3 | Same rationale |
| Action group tool credentials | Offline — Lambda classifies per action group | Lambda executor lookup (`lambda:GetFunction`) and credential type classification are expensive at scale; only relevant at Lambda deploy time |
| Foundation model catalog | Offline — Lambda writes to S3 | Catalog changes infrequently; no need to call `ListFoundationModels` on every reconciliation |

---

## 3. Object Classes and Schema

The connector exposes six object classes to PingOne IDM. All are read-only (`SearchOp` only).

### 3.1 Agent / Agent Alias (`__ACCOUNT__`)

One `ConnectorObject` is emitted per agent alias (alias-level identity model). Agents with zero aliases emit a single bare-agent object. UID = `agentId:aliasId` for alias-level objects; `agentId` for bare-agent objects.

| Attribute | Type | Source | Notes |
|---|---|---|---|
| `__UID__` | String | Connector | `agentId:aliasId` (alias) or `agentId` (bare agent) |
| `__NAME__` | String | Live API | `agentName` |
| `platform` | String | Connector | Always `AWS_BEDROCK` |
| `agentId` | String | Live API | Bedrock agent ID |
| `agentArn` | String | Live API | Full agent ARN |
| `agentVersion` | String | Live API | e.g. `DRAFT` |
| `agentStatus` | String | Live API | e.g. `PREPARED`, `NOT_PREPARED` |
| `foundationModel` | String | Live API | Foundation model ID |
| `description` | String | Live API | |
| `roleArn` | String | Live API | `agentResourceRoleArn` — IAM role the agent assumes |
| `idleSessionTTLInSeconds` | Integer | Live API | Session TTL |
| `createdAt` | String | Live API | ISO-8601 |
| `updatedAt` | String | Live API | ISO-8601 |
| `preparedAt` | String | Live API | ISO-8601 |
| `customerEncryptionKeyArn` | String | Live API | KMS key ARN, if set |
| `failureReasons` | String[] | Live API | Non-empty when agent is in error state |
| `recommendedActions` | String[] | Live API | Suggested remediation actions |
| `agentCollaboration` | String | Live API | Multi-agent collaboration mode |
| `guardrailId` | String | Live API | Guardrail ID from `guardrailConfiguration`, if set |
| `guardrailVersion` | String | Live API | Guardrail version from `guardrailConfiguration` |
| `aliasId` | String | Live API | Alias ID; absent on bare-agent objects |
| `aliasName` | String | Live API | Alias display name; absent on bare-agent objects |
| `aliasStatus` | String | Live API | Alias status; absent on bare-agent objects |
| `aliasDescription` | String | Live API | Alias description; absent on bare-agent objects |
| `aliasCreatedAt` | String | Live API | ISO-8601; absent on bare-agent objects |
| `aliasUpdatedAt` | String | Live API | ISO-8601; absent on bare-agent objects |
| `region` | String | Connector | AWS region for this connector instance |
| `tools` | String[] | Live API | Action group IDs attached to this agent |
| `knowledgeBases` | String[] | Live API | Knowledge base IDs attached to this agent |
| `connectedAgents` | String[] | Live API | Collaborator alias ARNs (multi-agent collaboration) |
| `agentPrincipals` | String[] | S3 artifact | IAM principals with `bedrock:InvokeAgent` on this alias; populated from `agent-bindings.json` cache |
| `toolCredentialIds` | String[] | S3 artifact | `agentToolCredentials` record IDs for action groups on this agent; populated from `agent-tool-credentials.json` cache |

### 3.2 Agent Tool — Action Group (`agentTool`)

Represents a Bedrock Agent action group. UID = `agentId:actionGroupId`.

| Attribute | Type | Source | Notes |
|---|---|---|---|
| `platform` | String | Connector | Always `AWS_BEDROCK` |
| `agentId` | String | Live API | Parent agent ID |
| `actionGroupId` | String | Live API | Action group ID |
| `actionGroupName` | String | Live API | Display name |
| `actionGroupState` | String | Live API | `ENABLED` or `DISABLED` |
| `description` | String | Live API | |
| `executorArn` | String | Live API | Lambda executor ARN for Lambda-backed action groups |
| `schemaUri` | String | Live API | `s3://{bucket}/{key}` for S3-hosted OpenAPI schemas |
| `parentActionSignature` | String | Live API | System action group type (e.g. `AMAZON.UserInput`); absent on custom groups |
| `region` | String | Connector | AWS region |

### 3.3 Agent Knowledge Base (`agentKnowledgeBase`)

Represents a knowledge base attached to a Bedrock Agent. UID = `agentId:knowledgeBaseId`.

| Attribute | Type | Source | Notes |
|---|---|---|---|
| `platform` | String | Connector | Always `AWS_BEDROCK` |
| `agentId` | String | Live API | Parent agent ID |
| `knowledgeBaseId` | String | Live API | Knowledge base ID |
| `description` | String | Live API | |
| `knowledgeBaseState` | String | Live API | `ENABLED` or `DISABLED` |
| `updatedAt` | String | Live API | ISO-8601 |
| `region` | String | Connector | AWS region |

### 3.4 Agent Guardrail (`agentGuardrail`)

Represents the guardrail configuration attached to a Bedrock Agent. One guardrail object per agent that has a `guardrailConfiguration`. UID = `agentId:guardrailId:guardrailVersion`.

| Attribute | Type | Source | Notes |
|---|---|---|---|
| `platform` | String | Connector | Always `AWS_BEDROCK` |
| `agentId` | String | Derived | Parent agent ID |
| `guardrailId` | String | Live API | Bedrock guardrail ID |
| `guardrailVersion` | String | Live API | Guardrail version |
| `guardrailName` | String | Live API | Display name |
| `description` | String | Live API | |
| `status` | String | Live API | Deployment status |
| `inputAction` | String | Live API | JSON array of content filter objects (type, inputStrength, outputStrength, inputAction, outputAction, inputModalities, outputModalities); absent when no content filters configured |
| `outputAction` | String | Live API | Same payload as `inputAction`; schema split deferred |
| `region` | String | Connector | AWS region |

### 3.5 Agent Identity Binding (`agentIdentityBinding`)

Represents a caller-access binding derived from IAM policies by the offline Lambda. Read from `agent-bindings.json` in S3. UID = `agentId:scope:principalArn` (or `*:AGENT:principalArn` for wildcard resource bindings).

| Attribute | Type | Source | Notes |
|---|---|---|---|
| `platform` | String | Connector | Always `AWS_BEDROCK` |
| `agentId` | String | S3 artifact | Bedrock agent ID; `*` for wildcard-resource bindings |
| `agentArn` | String | S3 artifact | Full agent ARN; `null` for wildcard bindings |
| `agentVersion` | String | S3 artifact | Agent version; `null` in v1 |
| `aliasArn` | String | S3 artifact | Full alias ARN; `null` for agent-level or wildcard bindings |
| `principalType` | String | S3 artifact | `ROLE` or `USER` |
| `principalName` | String | S3 artifact | IAM role or user name |
| `principalArn` | String | S3 artifact | Full IAM principal ARN (user ARN for group-inherited bindings, not group ARN) |
| `principalAccountId` | String | S3 artifact | AWS account ID of the principal |
| `wildcard` | Boolean | S3 artifact | `true` when the IAM policy resource was `*` or matched all agents |
| `scope` | String | S3 artifact | Always `AGENT` in v1 |
| `conditionJson` | String | S3 artifact | Serialized IAM condition block, if present; `null` otherwise |
| `bindingOrigin` | String | S3 artifact | `DIRECT_ROLE_POLICY`, `DIRECT_USER_POLICY`, or `GROUP_INHERITED` |
| `sourcePrincipalArn` | String | S3 artifact | ARN of the directly-bound principal (equals `principalArn` for role and direct-user bindings) |
| `sourcePrincipalType` | String | S3 artifact | `role`, `user` |
| `sourcePrincipalName` | String | S3 artifact | Name of the directly-bound principal |

### 3.6 Agent Tool Credentials (`agentToolCredentials`)

Represents the credential classification of a Bedrock Agent action group, as determined by the offline Lambda. Read from `agent-tool-credentials.json` in S3. UID = Lambda-computed `id` field (`tc-{sha256[:16]}`).

| Attribute | Type | Source | Notes |
|---|---|---|---|
| `platform` | String | Connector | Always `AWS_BEDROCK` |
| `id` | String | S3 artifact | Deterministic ID: `tc-{sha256[:16]}` of `(agentId\|actionGroupId\|region)` |
| `agentId` | String | S3 artifact | Bedrock agent ID |
| `agentArn` | String | S3 artifact | Full agent ARN |
| `agentServiceRoleArn` | String | S3 artifact | IAM role the agent assumes (`agentResourceRoleArn`) |
| `actionGroupId` | String | S3 artifact | Action group ID |
| `actionGroupName` | String | S3 artifact | Action group display name |
| `actionGroupState` | String | S3 artifact | `ENABLED` or `DISABLED` |
| `credentialType` | String | S3 artifact | `LAMBDA_EXECUTION_ROLE`, `CONFLUENCE_SECRET`, `S3_READ`, or `NONE` |
| `credentialRef` | String | S3 artifact | Lambda ARN, Secrets Manager ARN, or S3 URI depending on `credentialType` |
| `apiSchemaSource` | String | S3 artifact | `S3`, `INLINE`, or absent |
| `functionSchema` | String | S3 artifact | `"true"` or `"false"` — serialized as String for ICF compatibility |
| `accountId` | String | S3 artifact | AWS account ID |
| `region` | String | S3 artifact | AWS region |
| `lambdaExecutionRoleArn` | String | S3 artifact | IAM execution role of the Lambda function; populated only for `LAMBDA_EXECUTION_ROLE` type; absent otherwise |

---

## 4. S3 Artifact Schemas

The Lambda writes six JSON artifacts to S3. The connector reads two of them (`agent-bindings.json` and `agent-tool-credentials.json`). All artifacts are written to a timestamped run prefix and then promoted atomically to the stable `latest/` prefix.

### 4.1 Artifact Layout

```
s3://<CORE_INVENTORY_BUCKET>/<OUTPUT_PREFIX>runs/<TIMESTAMP>/
  models.json
  model-bindings.json
  agent-bindings.json
  agent-tool-credentials.json
  principals.json
  manifest.json

s3://<CORE_INVENTORY_BUCKET>/latest/
  models.json
  model-bindings.json
  agent-bindings.json
  agent-tool-credentials.json
  principals.json
  manifest.json
```

The connector reads exclusively from the `latest/` prefix using the stable key constants `latest/agent-bindings.json` and `latest/agent-tool-credentials.json`. The run-specific prefix is retained for auditability and rollback.

Promotion to `latest/` uses sequential `CopyObject` calls and only begins after all six `PutObject` calls succeed. A `PutObject` failure aborts the Lambda without updating `latest/`, leaving the previous snapshot intact.

### 4.2 `agent-bindings.json`

Single object wrapping an array of normalized IAM-to-agent bindings. Each entry represents one IAM principal with a policy granting `bedrock:InvokeAgent` on a specific agent alias or on a wildcard resource.

**Envelope:**

| Field | Type | Description |
|---|---|---|
| `accountId` | string | AWS account ID |
| `region` | string | AWS region |
| `generatedAt` | string | UTC timestamp — ISO-8601 |
| `bindings` | object[] | Array of binding records (see below) |

**Binding record:**

| Field | Type | Description |
|---|---|---|
| `agentArn` | string | Full agent ARN; `null` for wildcard-resource bindings |
| `agentVersion` | string | Agent version; `null` in v1 |
| `aliasArn` | string | Full alias ARN; `null` for agent-level or wildcard bindings |
| `principalType` | string | `ROLE` or `USER` |
| `principalName` | string | IAM role or user name |
| `principalArn` | string | Full IAM principal ARN |
| `principalAccountId` | string | Account ID of the principal |
| `wildcard` | boolean | `true` when IAM resource was `*`, `arn:...:*`, or `/*` |
| `conditionJson` | string | Serialized IAM condition block; `null` if absent |
| `bindingOrigin` | string | `DIRECT_ROLE_POLICY`, `DIRECT_USER_POLICY`, or `GROUP_INHERITED` |
| `sourcePrincipalArn` | string | ARN of the directly-bound principal |
| `sourcePrincipalType` | string | `role` or `user` |
| `sourcePrincipalName` | string | Name of the directly-bound principal |

**Connector parsing notes:**
- `agentArn` and `aliasArn` are parsed to extract `agentId` and `aliasId`.
- Bindings are stored in memory keyed as `AGENT#agentId` and `ALIAS#agentId/aliasId`.
- Wildcard entries (`agentArn: null`, `aliasArn: null`, `wildcard: true`) are stored under `WILDCARD_KEY` and appended to every per-agent result set.

### 4.3 `agent-tool-credentials.json`

Array of action group credential classification records. One record per action group per agent.

| Field | Type | Description |
|---|---|---|
| `id` | string | Deterministic ID: `tc-{sha256[:16]}` of `(agentId\|actionGroupId\|region)` |
| `agentId` | string | Bedrock agent ID |
| `agentArn` | string | Full agent ARN |
| `agentServiceRoleArn` | string | Agent's IAM execution role ARN |
| `actionGroupId` | string | Action group ID |
| `actionGroupName` | string | Action group display name |
| `actionGroupState` | string | `ENABLED` or `DISABLED` |
| `credentialType` | string | `LAMBDA_EXECUTION_ROLE`, `CONFLUENCE_SECRET`, `S3_READ`, or `NONE` |
| `credentialRef` | string | Lambda ARN, Secrets Manager ARN, or S3 URI depending on type |
| `apiSchemaSource` | string | `S3`, `INLINE`, or absent |
| `functionSchema` | boolean | `true` when the action group uses a function schema instead of an OpenAPI schema |
| `lambdaExecutionRoleArn` | string | Lambda function's IAM execution role; populated via `lambda:GetFunction` for `LAMBDA_EXECUTION_ROLE` type; `null` for all other types |
| `accountId` | string | AWS account ID |
| `region` | string | AWS region |

### 4.4 `manifest.json`

Single object describing the run. The connector does not read this file; it is used for operational validation and monitoring.

| Field | Type | Description |
|---|---|---|
| `generatedAt` | string | UTC timestamp — ISO-8601 |
| `accountId` | string | AWS account ID |
| `region` | string | AWS region |
| `modelCount` | number | Records in `models.json` |
| `modelBindingCount` | number | Records in `model-bindings.json` |
| `agentBindingCount` | number | Records in `agent-bindings.json` |
| `agentToolCredentialCount` | number | Records in `agent-tool-credentials.json` |
| `principalCount` | number | Records in `principals.json` |
| `warnings` | string[] | Warning conditions emitted during the run (e.g. `WILDCARD_BINDINGS_PRESENT`, `NO_AGENT_BINDINGS_FOUND`) |

---

## 5. Inventory Lambda — Build and Deployment

The `bedrock-core-tools-inventory` Lambda is a Python 3.11 function packaged as a ZIP file. It is triggered by EventBridge on a schedule. Source code is at `https://github.com/srallapally/bedrock-core-tools-inventory`.

### 5.1 Prerequisites

- AWS CLI configured with credentials sufficient to create IAM roles, Lambda functions, S3 buckets, and EventBridge rules
- Python 3.11+, `pip`, `zip` installed locally

### 5.2 Create the Deployment Bucket

This bucket stages the Lambda ZIP during deployment only. It is separate from the inventory output bucket.

```bash
aws s3api create-bucket \
  --bucket bedrock-core-tools-inventory-deploy \
  --region us-east-1 \
  --create-bucket-configuration LocationConstraint=us-east-1

aws s3api put-public-access-block \
  --bucket bedrock-core-tools-inventory-deploy \
  --public-access-block-configuration \
    BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
```

### 5.3 Create the IAM Execution Role

**Create the trust policy and role:**
```bash
cat > /tmp/lambda-trust-policy.json << 'EOF'
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": {"Service": "lambda.amazonaws.com"},
    "Action": "sts:AssumeRole"
  }]
}
EOF

aws iam create-role \
  --role-name bedrock-core-inventory-lambda-role \
  --assume-role-policy-document file:///tmp/lambda-trust-policy.json

aws iam attach-role-policy \
  --role-name bedrock-core-inventory-lambda-role \
  --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole
```

**Attach the custom inventory policy:**
```bash
cat > /tmp/inventory-policy.json << 'EOF'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "IAMReadForBindings",
      "Effect": "Allow",
      "Action": [
        "iam:ListRoles", "iam:ListRolePolicies", "iam:GetRolePolicy",
        "iam:ListAttachedRolePolicies",
        "iam:ListUsers", "iam:ListUserPolicies", "iam:GetUserPolicy",
        "iam:ListAttachedUserPolicies",
        "iam:ListGroupsForUser", "iam:ListGroupPolicies", "iam:GetGroupPolicy",
        "iam:ListAttachedGroupPolicies",
        "iam:GetPolicy", "iam:GetPolicyVersion"
      ],
      "Resource": "*"
    },
    {
      "Sid": "BedrockRead",
      "Effect": "Allow",
      "Action": [
        "bedrock:ListFoundationModels",
        "bedrock:ListAgents", "bedrock:GetAgent",
        "bedrock:ListAgentActionGroups", "bedrock:GetAgentActionGroup"
      ],
      "Resource": "*"
    },
    {
      "Sid": "LambdaReadForToolCredentials",
      "Effect": "Allow",
      "Action": "lambda:GetFunction",
      "Resource": "*"
    },
    {
      "Sid": "S3WriteInventory",
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:GetObject", "s3:CopyObject"],
      "Resource": "arn:aws:s3:::bedrock-core-inventory/*"
    },
    {
      "Sid": "STSCallerIdentity",
      "Effect": "Allow",
      "Action": "sts:GetCallerIdentity",
      "Resource": "*"
    }
  ]
}
EOF

aws iam put-role-policy \
  --role-name bedrock-core-inventory-lambda-role \
  --policy-name BedrockCoreInventoryPolicy \
  --policy-document file:///tmp/inventory-policy.json
```

Wait ~10 seconds for IAM propagation before proceeding.

### 5.4 Package the Lambda

```bash
git clone https://github.com/srallapally/bedrock-core-tools-inventory.git
cd bedrock-core-tools-inventory

pip install -r requirements.txt -t package/
cp src/*.py package/

cd package
zip -r ../bedrock-core-tools-inventory.zip .
cd ..

# Verify handler is at the root (no path prefix)
unzip -l bedrock-core-tools-inventory.zip | grep handler.py
```

### 5.5 Deploy the Lambda

**Upload ZIP:**
```bash
aws s3 cp bedrock-core-tools-inventory.zip \
  s3://bedrock-core-tools-inventory-deploy/bedrock-core-tools-inventory.zip \
  --region us-east-1
```

**Create the function (first time):**
```bash
aws lambda create-function \
  --function-name bedrock-core-tools-inventory \
  --runtime python3.11 \
  --handler handler.handler \
  --role arn:aws:iam::<ACCOUNT_ID>:role/bedrock-core-inventory-lambda-role \
  --code S3Bucket=bedrock-core-tools-inventory-deploy,S3Key=bedrock-core-tools-inventory.zip \
  --timeout 900 \
  --memory-size 256 \
  --environment "Variables={
    REGION=us-east-1,
    CORE_INVENTORY_BUCKET=bedrock-core-inventory,
    OUTPUT_PREFIX=bedrock-core-inventory/,
    ACCOUNT_ID=<ACCOUNT_ID>
  }" \
  --region us-east-1
```

**Update existing code:**
```bash
aws lambda update-function-code \
  --function-name bedrock-core-tools-inventory \
  --s3-bucket bedrock-core-tools-inventory-deploy \
  --s3-key bedrock-core-tools-inventory.zip \
  --region us-east-1
```

### 5.6 Create the EventBridge Schedule

```bash
# Create rule
aws events put-rule \
  --name bedrock-core-inventory-schedule \
  --schedule-expression "rate(15 minutes)" \
  --state ENABLED \
  --region us-east-1

# Add Lambda target
aws events put-targets \
  --rule bedrock-core-inventory-schedule \
  --targets "Id=LambdaTarget,Arn=arn:aws:lambda:us-east-1:<ACCOUNT_ID>:function:bedrock-core-tools-inventory" \
  --region us-east-1

# Grant EventBridge permission to invoke
aws lambda add-permission \
  --function-name bedrock-core-tools-inventory \
  --statement-id EventBridgeInvoke \
  --action lambda:InvokeFunction \
  --principal events.amazonaws.com \
  --source-arn <RULE_ARN> \
  --region us-east-1
```

Recommended schedule frequencies:

- **Every 15 minutes** (`rate(15 minutes)`) — maximum freshness; suitable for high-change environments
- **Hourly** (`rate(1 hour)`) — balanced freshness; recommended for most deployments
- **Every 6 hours** (`rate(6 hours)`) — suitable for stable environments

### 5.7 Invoke Manually and Verify

```bash
aws lambda invoke \
  --function-name bedrock-core-tools-inventory \
  --payload '{}' \
  --region us-east-1 \
  /tmp/response.json && cat /tmp/response.json
```

Expected: `{"statusCode": 200, "run_prefix": "bedrock-core-inventory/runs/<TIMESTAMP>/"}`

A `statusCode` of `500` means the Lambda ran but failed internally — check CloudWatch Logs before proceeding.

**Tail CloudWatch Logs:**
```bash
aws logs tail /aws/lambda/bedrock-core-tools-inventory \
  --follow \
  --region us-east-1
```

Look for `uploaded agent-bindings.json` and `uploaded agent-tool-credentials.json`. The final log line should be `"statusCode": 200`.

**Verify S3 artifacts:**
```bash
aws s3 ls s3://bedrock-core-inventory/latest/ --region us-east-1
```

Expected — six files: `agent-bindings.json`, `agent-tool-credentials.json`, `manifest.json`, `model-bindings.json`, `models.json`, `principals.json`.

```bash
# Inspect manifest counts
aws s3 cp s3://bedrock-core-inventory/latest/manifest.json - \
  --region us-east-1 | python3 -m json.tool

# Inspect agent bindings
aws s3 cp s3://bedrock-core-inventory/latest/agent-bindings.json - \
  --region us-east-1 | python3 -m json.tool
```

Confirm `agentBindingCount` and `agentToolCredentialCount` are non-zero and `generatedAt` reflects the current timestamp.

---

## 6. Connector Configuration Reference

All properties map to `AwsBedrockConfiguration` fields.

| Property | Type | Required | Default | Description |
|---|---|---|---|---|
| `region` | String | Yes | `us-east-1` | AWS region for all Bedrock and S3 API calls. One connector instance per region. |
| `accountId` | String | Yes | — | AWS account ID. |
| `useDefaultCredentialsProvider` | Boolean | No | `true` | If `true`, use the `DefaultCredentialsProvider` chain (environment variables, `~/.aws/credentials`, instance profile). If `false`, `accessKeyId` and `secretAccessKey` are required. |
| `accessKeyId` | String | Conditional | — | Explicit AWS access key ID. Required when `useDefaultCredentialsProvider=false`. |
| `secretAccessKey` | GuardedString | Conditional | — | Explicit AWS secret access key. Required when `useDefaultCredentialsProvider=false`. Decrypted only at client construction time. |
| `inventoryBucket` | String | Yes | `bedrock-core-inventory` | S3 bucket name for inventory artifacts. Bucket name only — no protocol prefix, no path, no trailing slash. |
| `bindingsCacheTtlSeconds` | Integer | No | `300` | TTL in seconds for the in-memory bindings and tool credentials caches. Both caches share this TTL. |

### 6.1 Multi-Region Deployment

Each connector instance covers one AWS region. To provide governance visibility across multiple regions, deploy one connector configuration per target region. Each connector reads from a region-specific inventory bucket (or a shared bucket with per-region prefixes) populated by a Lambda instance deployed in that region.

### 6.2 Credential Modes

**Default credentials chain** (`useDefaultCredentialsProvider=true`):
Uses `DefaultCredentialsProvider` — resolves credentials from environment variables, then `~/.aws/credentials` file, then EC2/ECS instance profile. Recommended for PingOne IDM deployments running on EC2 or ECS.

**Explicit key** (`useDefaultCredentialsProvider=false`):
Uses `accessKeyId` + `secretAccessKey` directly. All three Bedrock SDK clients (`BedrockAgentClient`, `BedrockClient`) and the S3 client are constructed with the same credentials at connector initialization.

---

## 7. AWS Permission Requirements

### 7.1 Connector IAM Credentials

Minimum permissions required for the IAM user or role configured on the connector. The connector requires no IAM read permissions — IAM scanning is handled exclusively by the inventory Lambda.

| Permission | Service | Scope | Required For |
|---|---|---|---|
| `bedrock:ListAgents` | Bedrock Agent | Account | All object class searches |
| `bedrock:GetAgent` | Bedrock Agent | Account | All object class searches |
| `bedrock:ListAgentAliases` | Bedrock Agent | Account | Alias enumeration |
| `bedrock:GetAgentAlias` | Bedrock Agent | Account | Alias-level GET |
| `bedrock:ListAgentActionGroups` | Bedrock Agent | Account | Tool search and agent attribute enrichment |
| `bedrock:GetAgentActionGroup` | Bedrock Agent | Account | Tool attribute enrichment |
| `bedrock:ListAgentKnowledgeBases` | Bedrock Agent | Account | Knowledge base search |
| `bedrock:ListAgentCollaborators` | Bedrock Agent | Account | `connectedAgents` attribute |
| `bedrock:GetGuardrail` | Bedrock | Account | Guardrail search and enrichment |
| `s3:GetObject` | S3 | `arn:aws:s3:::{inventoryBucket}/*` | Bindings cache read; tool credentials cache read |

**Sample least-privilege inline policy:**
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "BedrockAgentRead",
      "Effect": "Allow",
      "Action": [
        "bedrock:ListAgents", "bedrock:GetAgent",
        "bedrock:ListAgentAliases", "bedrock:GetAgentAlias",
        "bedrock:ListAgentActionGroups", "bedrock:GetAgentActionGroup",
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
      "Resource": "arn:aws:s3:::bedrock-core-inventory/*"
    }
  ]
}
```

### 7.2 Inventory Lambda Execution Role

Minimum permissions required for `bedrock-core-inventory-lambda-role`.

| Permission | Service | Scope | Required For |
|---|---|---|---|
| `iam:ListRoles` | IAM | Account | Role enumeration |
| `iam:ListRolePolicies` | IAM | Account | Inline role policy listing |
| `iam:GetRolePolicy` | IAM | Account | Inline role policy document fetch |
| `iam:ListAttachedRolePolicies` | IAM | Account | Managed role policy listing |
| `iam:ListUsers` | IAM | Account | User enumeration |
| `iam:ListUserPolicies` | IAM | Account | Inline user policy listing |
| `iam:GetUserPolicy` | IAM | Account | Inline user policy document fetch |
| `iam:ListAttachedUserPolicies` | IAM | Account | Managed user policy listing |
| `iam:ListGroupsForUser` | IAM | Account | Group membership enumeration |
| `iam:ListGroupPolicies` | IAM | Account | Inline group policy listing |
| `iam:GetGroupPolicy` | IAM | Account | Inline group policy document fetch |
| `iam:ListAttachedGroupPolicies` | IAM | Account | Managed group policy listing |
| `iam:GetPolicy` | IAM | Account | Managed policy default version resolution |
| `iam:GetPolicyVersion` | IAM | Account | Managed policy document fetch |
| `bedrock:ListFoundationModels` | Bedrock | Account | Foundation model catalog |
| `bedrock:ListAgents` | Bedrock Agent | Account | Agent enumeration |
| `bedrock:GetAgent` | Bedrock Agent | Account | Agent service role ARN fetch |
| `bedrock:ListAgentActionGroups` | Bedrock Agent | Account | Action group enumeration |
| `bedrock:GetAgentActionGroup` | Bedrock Agent | Account | Action group executor classification |
| `lambda:GetFunction` | Lambda | Account | Lambda execution role ARN for `LAMBDA_EXECUTION_ROLE` action groups |
| `s3:PutObject` | S3 | `arn:aws:s3:::{CORE_INVENTORY_BUCKET}/*` | Artifact upload |
| `s3:CopyObject` | S3 | `arn:aws:s3:::{CORE_INVENTORY_BUCKET}/*` | `latest/` promotion |
| `sts:GetCallerIdentity` | STS | `*` | Account ID resolution on cold start (avoidable via `ACCOUNT_ID` env var) |

---

## 8. Known Limitations and Operational Notes

### 8.1 Pagination Gap (OPENICF-423)

All list methods in `AwsBedrockClient` use `maxResults(100)` with no `nextToken` loop. Environments with more than 100 agents, 100 action groups per agent, 100 aliases per agent, 100 knowledge bases per agent, or 100 collaborators per agent will silently return only the first page. `listAgentsPaginated()` exists in `AwsBedrockClient` but is not yet wired into `AwsBedrockCrudService`. This is the only open gap blocking production use at scale. See OPENICF-423.

### 8.2 Inventory Freshness

Identity binding and tool credential data surfaced by the connector is only as fresh as the last successful Lambda invocation. If the Lambda fails or has not run recently, the connector will serve stale S3 data. Monitor Lambda execution status in CloudWatch and set alerting on function errors. The Lambda timeout is 900 seconds; set CloudWatch alarm threshold below that.

### 8.3 S3 Read Failure Behavior

Both `loadBindingsFromS3()` and `loadToolCredentialsFromS3()` handle `NoSuchKeyException` as a WARNING (empty result returned, reconciliation continues) and all other exceptions as ERROR (empty result returned, reconciliation continues). This is intentional fail-soft behavior: a missing or unreadable S3 artifact should not abort a reconciliation that can still return live agent data. However, `agentPrincipals` and `toolCredentialIds` will be absent on all objects when the cache is empty.

### 8.4 IAM Throttling on GetPolicyVersion

`iam:GetPolicyVersion` has a default service rate limit of 5 TPS. In accounts with more than ~2,000 IAM roles, sustained calls to this API will trigger throttling. The Lambda wraps all policy fetches in `retry.with_retry()` (up to 5 attempts, exponential backoff with jitter). For very large accounts, set `IAM_INTER_CALL_DELAY_MS=100` (or higher) on the Lambda environment to spread calls below the throttle threshold.

### 8.5 Group-Inherited Binding Principal Attribution

When an IAM user inherits `bedrock:InvokeAgent` via a group policy, the binding record's `principalArn` is set to the **user's ARN**, not the group ARN. This is an intentional design decision: the effective access holder is the user, and identity governance platforms evaluate access by user, not by group membership.

### 8.6 Wildcard Binding Deduplication

IAM policies granting `bedrock:InvokeAgent` on resource `*` are emitted as wildcard binding records (`wildcard: true`, `agentArn: null`, `aliasArn: null`). In the connector, wildcard bindings are stored under a single `WILDCARD_KEY` and appended once to every per-agent identity binding result set. `searchIdentityBindings()` emits wildcard bindings exactly once — not once per agent — to prevent N duplicates for N agents. Wildcard objects are identifiable by `agentId = "*"` and `scope = "AGENT"`.

### 8.7 Alias-Level Binding Scoping

`agentPrincipals` on an alias-level `__ACCOUNT__` object is scoped to bindings for that specific alias only, plus agent-level bindings. It does not roll up bindings for all aliases of the parent agent. This eliminates false positives in access reviews where two aliases of the same agent have different IAM access controls.

### 8.8 Verified Test Environment

| Parameter | Value |
|---|---|
| Account ID | `470686885243` |
| Region | `us-east-1` |
| Lambda function name | `bedrock-core-tools-inventory` |
| Lambda execution role | `bedrock-core-inventory-lambda-role` |
| Inventory bucket | `bedrock-core-inventory` |
| EventBridge rule | `bedrock-core-inventory-schedule` (every 15 minutes) |

---

## 9. Enhancement Roadmap

| ID | Description | Status |
|---|---|---|
| OPENICF-421 | Alias-level identity model — one `ConnectorObject` per alias | Done |
| OPENICF-422 | S3-backed identity bindings cache with TTL | Done |
| OPENICF-423 | Pagination across all list methods (`nextToken` loop) | **Open** |
| OPENICF-424 | Lambda IAM user scanning | Done |
| OPENICF-425 | Lambda group-inherited policy scanning | Done |
| OPENICF-426 | Lambda wildcard resource emission and deduplication | Done |
| OPENICF-427 | Connector-side wildcard binding support | Done |
| OPENICF-428 | Guardrail `inputAction` / `outputAction` content policy serialization | Done |
| OPENICF-429 | `parentActionSignature` attribute on `agentTool` objects | Done |
| OPENICF-430 | `agentPrincipals` scoped to the specific alias on alias-level objects | Done |
| OPENICF-431 | `agentToolCredentials` OC; `toolCredentialIds` on `__ACCOUNT__`; Lambda consolidation | Done |
| OPENICF-432 | `lambdaExecutionRoleArn` in `agent-tool-credentials.json` via `lambda:GetFunction` | Done |
