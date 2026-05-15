package ar.com.laboratory.controller;

import ar.com.laboratory.model.dto.storage.FileListResponse;
import ar.com.laboratory.model.dto.storage.FileMetadataResponse;
import ar.com.laboratory.model.dto.storage.FileUploadResponse;
import ar.com.laboratory.service.StorageService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.HandlerMapping;

@Slf4j
@RestController
@RequestMapping("/v1/storage")
@RequiredArgsConstructor
public class StorageController {

    private final StorageService storageService;

    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FileUploadResponse> uploadFile(
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "") String folder,
            @RequestParam(defaultValue = "false") boolean overwrite) {
        log.debug("Upload request: folder='{}', filename='{}', overwrite={}", folder, file.getOriginalFilename(), overwrite);
        FileUploadResponse response = storageService.uploadFile(file, folder, overwrite);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/files")
    public ResponseEntity<FileListResponse> listFiles(
            @RequestParam(defaultValue = "") String folder,
            @RequestParam(required = false) String pageToken,
            @RequestParam(defaultValue = "20") int pageSize) {
        log.debug("List request: folder='{}', pageToken={}, pageSize={}", folder, pageToken, pageSize);
        return ResponseEntity.ok(storageService.listFiles(folder, pageToken, pageSize));
    }

    @GetMapping("/files/content")
    public ResponseEntity<byte[]> downloadFile(@RequestParam String gcsKey) {
        log.debug("Download request: gcsKey='{}'", gcsKey);
        byte[] content = storageService.downloadFile(gcsKey);
        String contentType = storageService.getContentType(gcsKey);
        String filename = extractFilename(gcsKey);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        headers.setContentDisposition(ContentDisposition.attachment().filename(filename).build());
        headers.setContentLength(content.length);
        return ResponseEntity.ok().headers(headers).body(content);
    }

    @GetMapping("/files/**")
    public ResponseEntity<FileMetadataResponse> getFileMetadata(HttpServletRequest request) {
        String gcsKey = extractGcsKey(request);
        log.debug("Metadata request: gcsKey='{}'", gcsKey);
        return ResponseEntity.ok(storageService.getFileMetadata(gcsKey));
    }

    @DeleteMapping("/files/**")
    public ResponseEntity<Void> deleteFile(HttpServletRequest request) {
        String gcsKey = extractGcsKey(request);
        log.info("Delete request: gcsKey='{}'", gcsKey);
        storageService.deleteFile(gcsKey);
        return ResponseEntity.noContent().build();
    }

    private String extractGcsKey(HttpServletRequest request) {
        String pathWithinMapping = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        return pathWithinMapping != null ? pathWithinMapping : "";
    }

    private String extractFilename(String gcsKey) {
        int lastSlash = gcsKey.lastIndexOf('/');
        return lastSlash >= 0 ? gcsKey.substring(lastSlash + 1) : gcsKey;
    }
}
