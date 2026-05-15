package ar.com.laboratory.model.dto.template;

import ar.com.laboratory.model.enums.DocumentDomain;
import ar.com.laboratory.model.enums.DocumentTemplateStatus;

import java.util.List;
import java.util.UUID;

public record TemplateUploadResponse(
        UUID id,
        String name,
        DocumentDomain domain,
        String gcsKey,
        String version,
        DocumentTemplateStatus status,
        String description,
        List<String> detectedFields
) {}
