package ar.com.laboratory.model.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * Evento Kafka consumido desde el topic document.generation.requested.
 *
 * <p>Los @JsonProperty son obligatorios porque el ObjectMapper global usa SNAKE_CASE
 * (configurado en application.yml para los endpoints REST). Sin estas anotaciones,
 * Jackson buscaría "idempotency_id" y "template_name" en el payload camelCase
 * que publican los productores externos, dejando los campos en null.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentGenerationRequestedEvent {

    @NotBlank
    @JsonProperty("idempotencyId")
    private String idempotencyId;

    @NotBlank
    @JsonProperty("domain")
    private String domain;

    @NotBlank
    @JsonProperty("templateName")
    private String templateName;

    @NotNull
    @JsonProperty("fields")
    private Map<String, Object> fields;
}
