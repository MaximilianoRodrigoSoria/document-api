package ar.com.laboratory.service;

import ar.com.laboratory.cache.TemplateCache;
import ar.com.laboratory.exception.DocumentGenerationException;
import ar.com.laboratory.exception.TemplateNotFoundException;
import ar.com.laboratory.model.dto.template.TemplateDetailResponse;
import ar.com.laboratory.model.dto.template.TemplateResponse;
import ar.com.laboratory.model.dto.template.TemplateStatusRequest;
import ar.com.laboratory.model.dto.template.TemplateUpdateRequest;
import ar.com.laboratory.model.dto.template.TemplateUploadResponse;
import ar.com.laboratory.model.entity.DocumentTemplate;
import ar.com.laboratory.model.enums.DocumentDomain;
import ar.com.laboratory.model.enums.DocumentTemplateStatus;
import ar.com.laboratory.repository.DocumentTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateService {

    private static final Pattern MUSTACHE_VAR_PATTERN =
            Pattern.compile("\\{\\{([#^/]?)([^!{}>\\s][^}]*)\\}\\}");

    private final DocumentTemplateRepository templateRepository;
    private final TemplateCache              templateCache;

    @Transactional
    public TemplateUploadResponse uploadTemplate(
            MultipartFile file, String name, DocumentDomain domain, String version, String description) {

        byte[] templateBytes = readBytes(file);
        String htmlContent   = new String(templateBytes, StandardCharsets.UTF_8);
        List<String> detectedVariables = extractMustacheVariables(htmlContent);

        DocumentTemplate entity = templateRepository.findByName(name)
                .map(existing -> updateExisting(existing, domain, htmlContent, version, description))
                .orElseGet(() -> createNew(name, domain, htmlContent, version, description));

        DocumentTemplate saved = templateRepository.save(entity);
        templateCache.invalidate(name);

        log.info("Template registered: name={}, domain={}, version={}, variables={}",
                name, domain, version, detectedVariables.size());

        return new TemplateUploadResponse(saved.getId(), saved.getName(), saved.getDomain(),
                saved.getGcsKey(), saved.getVersion(), saved.getStatus(), saved.getDescription(),
                detectedVariables);
    }

    @Transactional(readOnly = true)
    public List<TemplateResponse> listTemplates() {
        return templateRepository.findAllByOrderByNameAsc().stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public TemplateDetailResponse getTemplateDetail(String name) {
        DocumentTemplate entity = templateRepository.findByName(name)
                .orElseThrow(() -> new TemplateNotFoundException(name));
        return toDetailResponse(entity);
    }

    @Transactional(readOnly = true)
    public String getTemplateContent(String name) {
        return templateRepository.findByName(name)
                .map(DocumentTemplate::getContent)
                .orElseThrow(() -> new TemplateNotFoundException(name));
    }

    @Transactional
    public TemplateDetailResponse updateTemplate(String name, TemplateUpdateRequest request) {
        DocumentTemplate entity = templateRepository.findByName(name)
                .orElseThrow(() -> new TemplateNotFoundException(name));
        entity.setDomain(request.domain());
        entity.setContent(request.content());
        entity.setVersion(request.version());
        entity.setDescription(request.description());
        entity.setStatus(DocumentTemplateStatus.ACTIVE);
        DocumentTemplate saved = templateRepository.save(entity);
        templateCache.invalidate(name);
        log.info("Template updated: name={}, domain={}, version={}", name, request.domain(), request.version());
        return toDetailResponse(saved);
    }

    @Transactional
    public TemplateResponse updateStatus(String name, TemplateStatusRequest request) {
        DocumentTemplate entity = templateRepository.findByName(name)
                .orElseThrow(() -> new TemplateNotFoundException(name));
        DocumentTemplateStatus previousStatus = entity.getStatus();
        entity.setStatus(request.status());
        DocumentTemplate saved = templateRepository.save(entity);
        if (previousStatus == DocumentTemplateStatus.ACTIVE && request.status() != DocumentTemplateStatus.ACTIVE) {
            templateCache.invalidate(name);
            log.info("Template deactivated, cache invalidated: name={}, status={}", name, request.status());
        } else {
            log.info("Template status changed: name={}, {} → {}", name, previousStatus, request.status());
        }
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<String> getTemplateFields(String name) {
        DocumentTemplate entity = templateRepository.findByName(name)
                .orElseThrow(() -> new TemplateNotFoundException(name));
        return extractMustacheVariables(entity.getContent());
    }

    private List<String> extractMustacheVariables(String html) {
        Matcher matcher = MUSTACHE_VAR_PATTERN.matcher(html);
        return matcher.results().map(r -> r.group(2).trim()).distinct().sorted().collect(Collectors.toList());
    }

    private DocumentTemplate createNew(String name, DocumentDomain domain, String content, String version, String description) {
        return DocumentTemplate.builder()
                .name(name).domain(domain).content(content).version(version)
                .status(DocumentTemplateStatus.ACTIVE).description(description).build();
    }

    private DocumentTemplate updateExisting(DocumentTemplate existing, DocumentDomain domain,
            String content, String version, String description) {
        existing.setDomain(domain);
        existing.setContent(content);
        existing.setVersion(version);
        existing.setDescription(description);
        existing.setStatus(DocumentTemplateStatus.ACTIVE);
        return existing;
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length == 0) throw new DocumentGenerationException("Template file is empty", null);
            return bytes;
        } catch (IOException e) {
            throw new DocumentGenerationException("Failed to read uploaded template file", e);
        }
    }

    private TemplateResponse toResponse(DocumentTemplate entity) {
        return new TemplateResponse(entity.getId(), entity.getName(), entity.getDomain(), entity.getGcsKey(),
                entity.getVersion(), entity.getStatus(), entity.getDescription(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private TemplateDetailResponse toDetailResponse(DocumentTemplate entity) {
        List<String> detectedFields = extractMustacheVariables(entity.getContent());
        return new TemplateDetailResponse(entity.getId(), entity.getName(), entity.getDomain(), entity.getGcsKey(),
                entity.getVersion(), entity.getStatus(), entity.getDescription(), entity.getContent(),
                detectedFields, entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
