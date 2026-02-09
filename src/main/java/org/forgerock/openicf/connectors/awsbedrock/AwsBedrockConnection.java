package org.forgerock.openicf.connectors.awsbedrock;

import org.forgerock.openicf.connectors.awsbedrock.client.AwsBedrockClient;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.common.security.GuardedString;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;

import java.io.Closeable;
import java.io.IOException;

/**
 * Manages the lifecycle of the AwsBedrockClient for the connector.
 *
 * This class is instantiated by the connector during init(), and closed
 * during dispose().
 */
public class AwsBedrockConnection implements Closeable {

    private static final Log LOG = Log.getLog(AwsBedrockConnection.class);

    private final AwsBedrockConfiguration configuration;
    private AwsBedrockClient client;
    private final S3Client s3Client;

    public AwsBedrockConnection(AwsBedrockConfiguration configuration) {
        this.configuration = configuration;
        this.client = createClient();
        this.s3Client = createS3Client(configuration);
    }

    private AwsBedrockClient createClient() {
        final String region = configuration.getRegion();
        final String accountId = configuration.getAccountId();
        final String accessKeyId = configuration.getAccessKeyId();
        final String secretAccessKey = toPlainString(configuration.getSecretAccessKey());

        LOG.ok("Creating AwsBedrockClient with explicit credentials for region {0}", region);
        return new AwsBedrockClient(region, accountId, accessKeyId, secretAccessKey);
    }
    /**
     * Builds an S3Client using the same credential strategy as AwsBedrockClient:
     * - If useDefaultCredentialsProvider == true â†’ DefaultCredentialsProvider chain
     * - Else â†’ StaticCredentialsProvider(accessKeyId, secretAccessKey)
     */
    private S3Client createS3Client(AwsBedrockConfiguration configuration) {
        AwsCredentialsProvider credentialsProvider;

        if (configuration.isUseDefaultCredentialsProvider()) {
            credentialsProvider = DefaultCredentialsProvider.create();
        } else {
            String accessKeyId = configuration.getAccessKeyId();
            String secretAccessKey = toPlainString(configuration.getSecretAccessKey());

            AwsBasicCredentials creds = AwsBasicCredentials.create(accessKeyId, secretAccessKey);
            credentialsProvider = StaticCredentialsProvider.create(creds);
        }

        return S3Client.builder()
                .region(Region.of(configuration.getRegion()))
                .credentialsProvider(credentialsProvider)
                .httpClient(UrlConnectionHttpClient.builder().build())
                .build();
    }
    private String toPlainString(GuardedString guarded) {
        if (guarded == null) {
            return null;
        }
        final StringBuilder sb = new StringBuilder();
        guarded.access(chars -> sb.append(chars));
        return sb.toString();
    }

    /**
     * Returns the underlying AwsBedrockClient used for CRUDQ operations.
     */
    public AwsBedrockClient getClient() {
        return client;
    }

    /** Expose the shared S3 client for reading precomputed bindings. */
    public S3Client getS3Client() {
        return s3Client;
    }

    public AwsBedrockConfiguration getConfiguration() {
        return configuration;
    }

    /**
     * Simple connectivity test used by TestOp.
     * For a read-only connector, listing agents is sufficient to verify access.
     */
    public void test() {
        LOG.ok("Testing AWS Bedrock connection by listing agents.");
        client.listAgents();
    }

    @Override
    public void close() throws IOException {
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                LOG.warn(e, "Error while closing AwsBedrockClient");
            } finally {
                client = null;
            }
        }
        if (s3Client != null) {
            try {
                s3Client.close();
            } catch (Exception e) {
                LOG.warn(e, "Error while closing S3Client");
            }
        }
    }
}