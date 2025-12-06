package org.forgerock.openicf.connectors.awsbedrock;

import org.forgerock.openicf.connectors.awsbedrock.utils.AwsBedrockConstants;
import org.identityconnectors.common.StringUtil;
import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.spi.AbstractConfiguration;
import org.identityconnectors.framework.spi.ConfigurationProperty;

/**
 * Configuration for the AWS Bedrock OpenICF connector.
 *
 * Parameters are directly aligned with AwsBedrockClient:
 * - region
 * - default credentials provider vs explicit access key / secret key
 */
public class AwsBedrockConfiguration extends AbstractConfiguration {

    /**
     * AWS account ID (e.g., "123456789012").
     * Used to resolve Bedrock agent/alias ARNs and IAM relationships.
     */
    private String accountId;

    /**
     * AWS region for Bedrock Agents (e.g., "us-east-1", "us-west-2").
     */
    private String region = AwsBedrockConstants.DEFAULT_REGION;

    /**
     * When true, use the AWS SDK DefaultCredentialsProvider chain.
     * When false, use explicit accessKeyId + secretAccessKey.
     */
    private boolean useDefaultCredentialsProvider = true;

    /**
     * AWS access key ID (only used when useDefaultCredentialsProvider == false).
     */
    private String accessKeyId;

    /**
     * AWS secret access key (only used when useDefaultCredentialsProvider == false).
     */
    private GuardedString secretAccessKey;

    private String s3BindingsBucket = "precomputed-agent-bindings";
    private Long bindingsCacheTtlSeconds = 300L; // 5 minutes

    // ---------------------------------------------------------------------
    // Configuration properties
    // ---------------------------------------------------------------------

    @ConfigurationProperty(
            order = 1,
            displayMessageKey = "awsbedrock.region.display",
            helpMessageKey = "awsbedrock.region.help",
            required = true
    )
    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    @ConfigurationProperty(
            order = 2,
            displayMessageKey = "awsbedrock.accountId.display",
            helpMessageKey = "awsbedrock.accountId.help",
            required = true
    )
    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    @ConfigurationProperty(
            order = 3,
            displayMessageKey = "awsbedrock.useDefaultCredentials.display",
            helpMessageKey = "awsbedrock.useDefaultCredentials.help",
            required = true
    )
    public boolean isUseDefaultCredentialsProvider() {
        return useDefaultCredentialsProvider;
    }

    public void setUseDefaultCredentialsProvider(boolean useDefaultCredentialsProvider) {
        this.useDefaultCredentialsProvider = useDefaultCredentialsProvider;
    }

    @ConfigurationProperty(
            order = 4,
            displayMessageKey = "awsbedrock.accessKeyId.display",
            helpMessageKey = "awsbedrock.accessKeyId.help",
            required = false
    )
    public String getAccessKeyId() {
        return accessKeyId;
    }

    public void setAccessKeyId(String accessKeyId) {
        this.accessKeyId = accessKeyId;
    }

    @ConfigurationProperty(
            order = 5,
            displayMessageKey = "awsbedrock.secretAccessKey.display",
            helpMessageKey = "awsbedrock.secretAccessKey.help",
            required = false,
            confidential = true
    )
    public GuardedString getSecretAccessKey() {
        return secretAccessKey;
    }

    public void setSecretAccessKey(GuardedString secretAccessKey) {
        this.secretAccessKey = secretAccessKey;
    }

    public String getS3BindingsBucket() {
        return s3BindingsBucket;
    }

    public void setS3BindingsBucket(String s3BindingsBucket) {
        this.s3BindingsBucket = s3BindingsBucket;
    }

    public Long getBindingsCacheTtlSeconds() {
        return bindingsCacheTtlSeconds;
    }

    public void setBindingsCacheTtlSeconds(Long bindingsCacheTtlSeconds) {
        this.bindingsCacheTtlSeconds = bindingsCacheTtlSeconds;
    }

    /**
     * Default key where the Lambda writes bindings:
     *   <accountId>/<region>/bindings.json
     */
    public String computeBindingsKey() {
        return String.format("%s/%s/bindings.json", getAccountId(), getRegion());
    }
    // ---------------------------------------------------------------------
    // Validation
    // ---------------------------------------------------------------------

    @Override
    public void validate() {
        if (StringUtil.isBlank(region)) {
            throw new IllegalArgumentException("Region must be specified for AWS Bedrock connector.");
        }
        if (StringUtil.isBlank(accountId)) {
            throw new IllegalArgumentException("AWS accountId must be specified for AWS Bedrock connector.");
        }
        if (!useDefaultCredentialsProvider) {
            if (StringUtil.isBlank(accessKeyId)) {
                throw new IllegalArgumentException(
                        "accessKeyId must be specified when not using the default credentials provider.");
            }
            if (secretAccessKey == null) {
                throw new IllegalArgumentException(
                        "secretAccessKey must be specified when not using the default credentials provider.");
            }
        }
    }
}
