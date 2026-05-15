package ar.com.laboratory.model.dto.template;

import ar.com.laboratory.model.enums.DocumentDomain;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record TemplateUpdateRequest(
        @NotNull(message = "El dominio es obligatorio")
        DocumentDomain domain,

        @NotBlank(message = "La versión es obligatoria")
        @Pattern(regexp = "\\d+\\.\\d+\\.\\d+", message = "La versión debe tener formato semántico (ej. 1.2.0)")
        String version,

        @Size(max = 1000, message = "La descripción no puede superar 1000 caracteres")
        String description,

        @NotBlank(message = "El contenido HTML es obligatorio")
        String content
) {}
