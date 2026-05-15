package ar.com.laboratory.controller;

import ar.com.laboratory.model.dto.template.TemplateDetailResponse;
import ar.com.laboratory.model.dto.template.TemplateResponse;
import ar.com.laboratory.model.dto.template.TemplateStatusRequest;
import ar.com.laboratory.model.dto.template.TemplateUpdateRequest;
import ar.com.laboratory.model.dto.template.TemplateUploadResponse;
import ar.com.laboratory.model.enums.DocumentDomain;
import ar.com.laboratory.service.TemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/v1/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templateService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TemplateUploadResponse> uploadTemplate(
            @RequestPart("file") MultipartFile file,
            @RequestParam String name,
            @RequestParam DocumentDomain domain,
            @RequestParam String version,
            @RequestParam(required = false) String description) {
        log.info("Template upload request: name={}, domain={}, version={}", name, domain, version);
        TemplateUploadResponse response = templateService.uploadTemplate(file, name, domain, version, description);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<TemplateResponse>> listTemplates() {
        return ResponseEntity.ok(templateService.listTemplates());
    }

    @GetMapping("/{name}")
    public ResponseEntity<TemplateDetailResponse> getTemplateDetail(@PathVariable String name) {
        return ResponseEntity.ok(templateService.getTemplateDetail(name));
    }

    @PutMapping("/{name}")
    public ResponseEntity<TemplateDetailResponse> updateTemplate(
            @PathVariable String name,
            @Valid @RequestBody TemplateUpdateRequest request) {
        log.info("Template update request: name={}, domain={}, version={}", name, request.domain(), request.version());
        return ResponseEntity.ok(templateService.updateTemplate(name, request));
    }

    @PatchMapping("/{name}/status")
    public ResponseEntity<TemplateResponse> updateStatus(
            @PathVariable String name,
            @Valid @RequestBody TemplateStatusRequest request) {
        log.info("Template status change request: name={}, newStatus={}", name, request.status());
        return ResponseEntity.ok(templateService.updateStatus(name, request));
    }

    @GetMapping("/{name}/fields")
    public ResponseEntity<List<String>> getTemplateFields(@PathVariable String name) {
        return ResponseEntity.ok(templateService.getTemplateFields(name));
    }

    @GetMapping(value = "/{name}/preview", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> previewTemplate(@PathVariable String name) {
        String html = templateService.getTemplateContent(name);
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    @GetMapping("/{name}/download")
    public ResponseEntity<byte[]> downloadTemplate(@PathVariable String name) {
        String html = templateService.getTemplateContent(name);
        byte[] bytes = html.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header("Content-Disposition", "attachment; filename=\"" + name + ".html\"")
                .header("Content-Length", String.valueOf(bytes.length))
                .body(bytes);
    }
}
