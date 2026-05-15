package ar.com.laboratory.consumer;

import ar.com.laboratory.constants.KafkaTopicConstants;
import ar.com.laboratory.constants.LogConstants;
import ar.com.laboratory.model.entity.RawData;
import ar.com.laboratory.model.enums.RawDataStatus;
import ar.com.laboratory.model.event.DocumentGenerationRequestedEvent;
import ar.com.laboratory.repository.RawDataRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * Consumer Kafka del topic {@value KafkaTopicConstants#LOYALTY_DOCUMENT_GENERATION_REQUEST}.
 *
 * <p>Publicado por payment-loyalty. Implementa idempotencia con 3 ramas:
 * nuevo → PENDING, existente no-FAILED → skip, existente FAILED → reset a PENDING.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentGenerationRequestedConsumer {

    private final RawDataRepository rawDataRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${document-generation.kafka.topics.requested:" + KafkaTopicConstants.LOYALTY_DOCUMENT_GENERATION_REQUEST + "}",
            groupId = "${document-generation.kafka.consumer-group-id:" + KafkaTopicConstants.CONSUMER_GROUP_ID + "}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(String payload, Acknowledgment acknowledgment) {
        Objects.requireNonNull(payload, "Kafka payload must not be null");

        DocumentGenerationRequestedEvent event = deserialize(payload);
        if (event == null) {
            log.error("[consumer.event.parse.error] Failed to deserialize payload, skipping ACK");
            return;
        }

        MDC.put(LogConstants.MDC_IDEMPOTENCY_ID, event.getIdempotencyId());
        MDC.put(LogConstants.MDC_TEMPLATE_NAME,  event.getTemplateName());

        try {
            log.debug("[{}] Event received: idempotencyId={}, template={}",
                    LogConstants.LOG_CONSUMER_RECEIVED, event.getIdempotencyId(), event.getTemplateName());

            Optional<RawData> existing = rawDataRepository.findByIdempotencyId(event.getIdempotencyId());

            if (existing.isEmpty()) {
                handleNewEvent(event);
            } else if (existing.get().getStatus() == RawDataStatus.FAILED) {
                handleRetryFailed(existing.get());
            } else {
                handleDuplicate(existing.get());
            }

            acknowledgment.acknowledge();

        } finally {
            MDC.remove(LogConstants.MDC_IDEMPOTENCY_ID);
            MDC.remove(LogConstants.MDC_TEMPLATE_NAME);
        }
    }

    private void handleNewEvent(DocumentGenerationRequestedEvent event) {
        RawData newRecord = RawData.builder()
                .idempotencyId(event.getIdempotencyId())
                .templateName(event.getTemplateName())
                .type(event.getDomain())
                .data(serializeFields(event))
                .status(RawDataStatus.PENDING)
                .retryCount(0)
                .build();
        rawDataRepository.save(newRecord);
        log.info("[{}] New record persisted: idempotencyId={}, processId={}",
                LogConstants.LOG_CONSUMER_PERSISTED, event.getIdempotencyId(), newRecord.getProcessId());
    }

    private void handleRetryFailed(RawData existing) {
        existing.setStatus(RawDataStatus.PENDING);
        existing.setRetryCount(0);
        existing.setErrorMessage(null);
        existing.setDocumentUrl(null);
        rawDataRepository.save(existing);
        log.info("[{}] FAILED record reset to PENDING: idempotencyId={}, processId={}",
                LogConstants.LOG_CONSUMER_IDEMPOTENCY_REPROCESS, existing.getIdempotencyId(), existing.getProcessId());
    }

    private void handleDuplicate(RawData existing) {
        log.info("[{}] Duplicate event skipped: idempotencyId={}, currentStatus={}",
                LogConstants.LOG_CONSUMER_IDEMPOTENCY_SKIP, existing.getIdempotencyId(), existing.getStatus());
    }

    private DocumentGenerationRequestedEvent deserialize(String payload) {
        try {
            return objectMapper.readValue(payload, DocumentGenerationRequestedEvent.class);
        } catch (Exception e) {
            log.error("Failed to deserialize Kafka payload: {}", e.getMessage());
            return null;
        }
    }

    private String serializeFields(DocumentGenerationRequestedEvent event) {
        if (event.getFields() == null || event.getFields().isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(event.getFields());
        } catch (Exception e) {
            log.error("Failed to serialize fields map for idempotencyId={}: {}",
                    event.getIdempotencyId(), e.getMessage());
            return null;
        }
    }
}
