package ar.com.laboratory.model.dto.template;

import ar.com.laboratory.model.enums.DocumentTemplateStatus;
import jakarta.validation.constraints.NotNull;

public record TemplateStatusRequest(
        @NotNull(message = "El estado es obligatorio")
        DocumentTemplateStatus status
) {}
