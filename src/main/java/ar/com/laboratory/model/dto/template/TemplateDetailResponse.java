package ar.com.laboratory.model.dto.template;

import ar.com.laboratory.model.enums.DocumentDomain;
import ar.com.laboratory.model.enums.DocumentTemplateStatus;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record TemplateDetailResponse(
        UUID id,
        String name,
        DocumentDomain domain,
        String gcsKey,
        String version,
        DocumentTemplateStatus status,
        String description,
        String content,
        List<String> detectedFields,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime createdAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime updatedAt
) {}
