package ar.com.laboratory.service;

import ar.com.laboratory.exception.StorageFileAlreadyExistsException;
import ar.com.laboratory.exception.StorageFileNotFoundException;
import ar.com.laboratory.exception.StorageInvalidFolderException;
import ar.com.laboratory.model.dto.storage.FileListResponse;
import ar.com.laboratory.model.dto.storage.FileMetadataResponse;
import ar.com.laboratory.model.dto.storage.FileUploadResponse;
import ar.com.laboratory.storage.GcsStorageClient;
import com.google.api.gax.paging.Page;
import com.google.cloud.storage.Blob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class StorageService {

    private final GcsStorageClient gcsStorageClient;

    public FileUploadResponse uploadFile(MultipartFile file, String folder, boolean overwrite) {
        String normalizedFolder = normalizeFolder(folder);
        validateFolder(normalizedFolder);

        String filename = resolveFilename(file);
        String gcsKey = normalizedFolder + filename;

        if (!overwrite && gcsStorageClient.fileExists(gcsKey)) {
            throw new StorageFileAlreadyExistsException(gcsKey);
        }

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read uploaded file content: " + e.getMessage(), e);
        }

        String contentType = StringUtils.hasText(file.getContentType())
                ? file.getContentType()
                : "application/octet-stream";

        Blob blob = gcsStorageClient.uploadFile(gcsKey, content, contentType);

        log.info("File uploaded: gcsKey={}, size={} bytes, overwrite={}", gcsKey, content.length, overwrite);

        return FileUploadResponse.builder()
                .gcsKey(gcsKey)
                .bucket(gcsStorageClient.getBucket())
                .size(content.length)
                .contentType(blob.getContentType())
                .uploadedAt(nowIso())
                .gcsUri(gcsStorageClient.buildGcsUri(gcsKey))
                .build();
    }

    public FileListResponse listFiles(String folder, String pageToken, int pageSize) {
        String normalizedFolder = normalizeFolder(folder);
        validateFolder(normalizedFolder);

        int clampedPageSize = Math.max(1, Math.min(pageSize, 100));
        Page<Blob> page = gcsStorageClient.listFiles(normalizedFolder, pageToken, clampedPageSize);

        List<FileMetadataResponse> files = new ArrayList<>();
        for (Blob blob : page.iterateAll()) {
            if (!blob.getName().endsWith("/")) {
                files.add(toMetadata(blob));
            }
        }

        return FileListResponse.builder()
                .folder(normalizedFolder)
                .pageSize(clampedPageSize)
                .nextPageToken(page.getNextPageToken())
                .files(files)
                .build();
    }

    public FileMetadataResponse getFileMetadata(String gcsKey) {
        validateGcsKey(gcsKey);
        Blob blob = gcsStorageClient.getBlob(gcsKey)
                .orElseThrow(() -> new StorageFileNotFoundException(gcsKey));
        log.debug("Metadata resolved: gcsKey={}, size={}", gcsKey, blob.getSize());
        return toMetadata(blob);
    }

    public byte[] downloadFile(String gcsKey) {
        validateGcsKey(gcsKey);
        byte[] content = gcsStorageClient.downloadFile(gcsKey);
        log.debug("File downloaded: gcsKey={}, size={} bytes", gcsKey, content.length);
        return content;
    }

    public String getContentType(String gcsKey) {
        return gcsStorageClient.getBlob(gcsKey)
                .map(Blob::getContentType)
                .filter(StringUtils::hasText)
                .orElse("application/octet-stream");
    }

    public void deleteFile(String gcsKey) {
        validateGcsKey(gcsKey);
        gcsStorageClient.deleteFile(gcsKey);
        log.info("File deleted: gcsKey={}", gcsKey);
    }

    private String normalizeFolder(String folder) {
        return folder == null ? "" : folder.strip();
    }

    private void validateFolder(String folder) {
        if (folder.isEmpty()) return;
        if (folder.contains("..")) throw new StorageInvalidFolderException(folder, "path traversal ('..') is not allowed");
        if (folder.startsWith("/")) throw new StorageInvalidFolderException(folder, "must not start with '/'");
        if (!folder.endsWith("/")) throw new StorageInvalidFolderException(folder, "must end with '/' (e.g. 'templates/')");
    }

    private void validateGcsKey(String gcsKey) {
        if (!StringUtils.hasText(gcsKey)) throw new IllegalArgumentException("gcsKey must not be blank");
        if (gcsKey.contains("..")) throw new StorageInvalidFolderException(gcsKey, "path traversal ('..') is not allowed");
    }

    private String resolveFilename(MultipartFile file) {
        String filename = StringUtils.hasText(file.getOriginalFilename())
                ? file.getOriginalFilename()
                : file.getName();
        Objects.requireNonNull(filename, "File must have a name");
        if (filename.isBlank()) throw new IllegalArgumentException("File name must not be blank");
        if (filename.contains("..")) throw new StorageInvalidFolderException(filename, "path traversal ('..') in filename is not allowed");
        return filename;
    }

    private FileMetadataResponse toMetadata(Blob blob) {
        String gcsKey = blob.getName();
        String name = gcsKey.contains("/") ? gcsKey.substring(gcsKey.lastIndexOf('/') + 1) : gcsKey;
        return FileMetadataResponse.builder()
                .gcsKey(gcsKey)
                .bucket(blob.getBucket())
                .name(name)
                .size(blob.getSize())
                .contentType(blob.getContentType())
                .createdAt(toIso(blob.getCreateTime()))
                .updatedAt(toIso(blob.getUpdateTime()))
                .gcsUri(gcsStorageClient.buildGcsUri(gcsKey))
                .build();
    }

    private String toIso(Long epochMillis) {
        return epochMillis != null
                ? Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                : null;
    }

    private String nowIso() {
        return Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
