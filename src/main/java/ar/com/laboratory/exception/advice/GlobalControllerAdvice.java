package ar.com.laboratory.exception.advice;

import ar.com.laboratory.exception.DocumentGenerationException;
import ar.com.laboratory.exception.IdempotencyConflictException;
import ar.com.laboratory.exception.StorageFileAlreadyExistsException;
import ar.com.laboratory.exception.StorageFileNotFoundException;
import ar.com.laboratory.exception.StorageInvalidFolderException;
import ar.com.laboratory.exception.TemplateDownloadException;
import ar.com.laboratory.exception.TemplateNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalControllerAdvice {

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, Object>> handleIdempotencyConflict(IdempotencyConflictException ex) {
        log.warn("Idempotency conflict for idempotencyId='{}': {}", ex.getIdempotencyId(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorBody(HttpStatus.CONFLICT, ex.getMessage()));
    }

    @ExceptionHandler(StorageFileAlreadyExistsException.class)
    public ResponseEntity<Map<String, Object>> handleStorageFileAlreadyExists(StorageFileAlreadyExistsException ex) {
        log.warn("Storage conflict: gcsKey='{}': {}", ex.getGcsKey(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorBody(HttpStatus.CONFLICT, ex.getMessage()));
    }

    @ExceptionHandler(StorageFileNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleStorageFileNotFound(StorageFileNotFoundException ex) {
        log.warn("Storage file not found: gcsKey='{}': {}", ex.getGcsKey(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody(HttpStatus.NOT_FOUND, ex.getMessage()));
    }

    @ExceptionHandler(StorageInvalidFolderException.class)
    public ResponseEntity<Map<String, Object>> handleStorageInvalidFolder(StorageInvalidFolderException ex) {
        log.warn("Invalid storage folder='{}': {}", ex.getFolder(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(HttpStatus.BAD_REQUEST, ex.getMessage()));
    }

    @ExceptionHandler(TemplateNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleTemplateNotFound(TemplateNotFoundException ex) {
        log.warn("Template not found: templateName='{}': {}", ex.getTemplateName(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody(HttpStatus.NOT_FOUND, ex.getMessage()));
    }

    @ExceptionHandler(TemplateDownloadException.class)
    public ResponseEntity<Map<String, Object>> handleTemplateDownload(TemplateDownloadException ex) {
        log.error("Template download failure: gcsKey='{}': {}", ex.getGcsKey(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(errorBody(HttpStatus.BAD_GATEWAY, ex.getMessage()));
    }

    @ExceptionHandler(DocumentGenerationException.class)
    public ResponseEntity<Map<String, Object>> handleDocumentGeneration(DocumentGenerationException ex) {
        log.error("Document generation error: processId={}: {}", ex.getProcessId(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorBody(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorBody(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error"));
    }

    private Map<String, Object> errorBody(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return body;
    }
}
