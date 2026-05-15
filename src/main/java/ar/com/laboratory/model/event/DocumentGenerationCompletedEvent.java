package ar.com.laboratory.model.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentGenerationCompletedEvent {

    @JsonProperty("idempotencyId")
    private String idempotencyId;

    @JsonProperty("domain")
    private String domain;

    @JsonProperty("templateName")
    private String templateName;

    @JsonProperty("documentUrl")
    private String documentUrl;

    @JsonProperty("status")
    private String status;

    @JsonProperty("processedAt")
    private LocalDateTime processedAt;
}
