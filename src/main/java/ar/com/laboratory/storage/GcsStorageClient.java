package ar.com.laboratory.storage;

import ar.com.laboratory.exception.DocumentGenerationException;
import ar.com.laboratory.exception.StorageFileNotFoundException;
import ar.com.laboratory.exception.TemplateDownloadException;
import com.google.api.gax.paging.Page;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class GcsStorageClient {

    private final Storage gcsStorage;

    @Value("${gcs.bucket-name:document-generator-bucket}")
    private String bucket;

    @Value("${gcs.public-url-base:https://storage.googleapis.com}")
    private String publicUrlBase;

    public String uploadDocument(String idempotencyId, String domain, byte[] pdfBytes) {
        Objects.requireNonNull(idempotencyId, "idempotencyId must not be null");
        Objects.requireNonNull(pdfBytes, "pdfBytes must not be null");

        String folder = (domain != null && !domain.isBlank())
                ? domain.toLowerCase().trim()
                : "documents";
        String gcsKey = folder + "/" + idempotencyId + ".pdf";

        try {
            BlobId blobId = BlobId.of(bucket, gcsKey);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType("application/pdf").build();
            gcsStorage.create(blobInfo, pdfBytes);
            String documentUri = buildPublicUrl(gcsKey);
            log.debug("PDF uploaded to GCS: key={}, size={} bytes", gcsKey, pdfBytes.length);
            return documentUri;
        } catch (Exception e) {
            throw new DocumentGenerationException("Failed to upload PDF to GCS key '" + gcsKey + "'", e);
        }
    }

    public byte[] downloadTemplate(String gcsKey) {
        Objects.requireNonNull(gcsKey, "gcsKey must not be null");
        try {
            BlobId blobId = BlobId.of(bucket, gcsKey);
            byte[] content = gcsStorage.readAllBytes(blobId);
            log.debug("Template downloaded from GCS: key={}, size={} bytes", gcsKey, content.length);
            return content;
        } catch (Exception e) {
            throw new TemplateDownloadException(gcsKey, e);
        }
    }

    public Blob uploadFile(String gcsKey, byte[] content, String contentType) {
        Objects.requireNonNull(gcsKey, "gcsKey must not be null");
        Objects.requireNonNull(content, "content must not be null");
        BlobId blobId = BlobId.of(bucket, gcsKey);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                .setContentType(contentType != null ? contentType : "application/octet-stream").build();
        Blob blob = gcsStorage.create(blobInfo, content);
        log.debug("File uploaded to GCS: key={}, size={} bytes, contentType={}", gcsKey, content.length, contentType);
        return blob;
    }

    public byte[] downloadFile(String gcsKey) {
        Objects.requireNonNull(gcsKey, "gcsKey must not be null");
        Blob blob = gcsStorage.get(BlobId.of(bucket, gcsKey));
        if (blob == null || !blob.exists()) throw new StorageFileNotFoundException(gcsKey);
        byte[] content = blob.getContent();
        log.debug("File downloaded from GCS: key={}, size={} bytes", gcsKey, content.length);
        return content;
    }

    public Optional<Blob> getBlob(String gcsKey) {
        Objects.requireNonNull(gcsKey, "gcsKey must not be null");
        Blob blob = gcsStorage.get(BlobId.of(bucket, gcsKey));
        return (blob != null && blob.exists()) ? Optional.of(blob) : Optional.empty();
    }

    public boolean fileExists(String gcsKey) {
        Objects.requireNonNull(gcsKey, "gcsKey must not be null");
        Blob blob = gcsStorage.get(BlobId.of(bucket, gcsKey));
        return blob != null && blob.exists();
    }

    public Page<Blob> listFiles(String prefix, String pageToken, int pageSize) {
        List<Storage.BlobListOption> options = new ArrayList<>();
        if (prefix != null && !prefix.isBlank()) options.add(Storage.BlobListOption.prefix(prefix));
        options.add(Storage.BlobListOption.pageSize(pageSize));
        if (pageToken != null && !pageToken.isBlank()) options.add(Storage.BlobListOption.pageToken(pageToken));
        Page<Blob> page = gcsStorage.list(bucket, options.toArray(new Storage.BlobListOption[0]));
        log.debug("Listed GCS objects: prefix='{}', pageSize={}", prefix, pageSize);
        return page;
    }

    public void deleteFile(String gcsKey) {
        Objects.requireNonNull(gcsKey, "gcsKey must not be null");
        boolean deleted = gcsStorage.delete(BlobId.of(bucket, gcsKey));
        if (!deleted) throw new StorageFileNotFoundException(gcsKey);
        log.debug("File deleted from GCS: key={}", gcsKey);
    }

    public String buildPublicUrl(String gcsKey) {
        return publicUrlBase + "/" + bucket + "/" + gcsKey;
    }

    public String buildGcsUri(String gcsKey) {
        return "gs://" + bucket + "/" + gcsKey;
    }

    public String getBucket() {
        return bucket;
    }
}
