package ar.com.laboratory.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.NoCredentials;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
@Configuration
public class GcsConfig {

    @Value("${gcs.emulator-host:}")
    private String emulatorHost;

    @Value("${gcs.project-id:local-project}")
    private String projectId;

    @Value("${gcs.bucket-name:document-generator-bucket}")
    private String bucketName;

    @Value("${gcs.credentials-json:}")
    private String credentialsJson;

    @Bean
    public Storage gcsStorage() throws IOException {
        StorageOptions.Builder builder = StorageOptions.newBuilder()
                .setProjectId(projectId);

        if (StringUtils.hasText(emulatorHost)) {
            log.info("[GcsConfig] Local emulator (fake-gcs-server): {}", emulatorHost);
            builder.setHost(emulatorHost)
                   .setCredentials(NoCredentials.getInstance());
            Storage storage = builder.build().getService();
            ensureBucketExists(storage);
            return storage;
        }

        if (StringUtils.hasText(credentialsJson)) {
            log.info("[GcsConfig] Service account credentials (project: {})", projectId);
            GoogleCredentials credentials = ServiceAccountCredentials.fromStream(
                    new ByteArrayInputStream(credentialsJson.getBytes(StandardCharsets.UTF_8))
            );
            builder.setCredentials(credentials);
        } else {
            log.info("[GcsConfig] Application Default Credentials (project: {})", projectId);
        }

        return builder.build().getService();
    }

    /**
     * Creates the GCS bucket in the local emulator if it does not already exist.
     * fake-gcs-server also creates buckets from the filesystem on startup,
     * but this call makes startup deterministic even on a clean checkout.
     */
    private void ensureBucketExists(Storage storage) {
        try {
            if (storage.get(bucketName) == null) {
                storage.create(BucketInfo.of(bucketName));
                log.info("[GcsConfig] Bucket '{}' created in local emulator", bucketName);
            } else {
                log.debug("[GcsConfig] Bucket '{}' already exists in local emulator", bucketName);
            }
        } catch (Exception e) {
            log.warn("[GcsConfig] Could not auto-create bucket '{}': {}", bucketName, e.getMessage());
        }
    }
}
