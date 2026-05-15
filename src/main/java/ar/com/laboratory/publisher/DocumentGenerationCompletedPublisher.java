package ar.com.laboratory.publisher;

import ar.com.laboratory.constants.KafkaTopicConstants;
import ar.com.laboratory.constants.LogConstants;
import ar.com.laboratory.model.event.DocumentGenerationCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Publica {@link DocumentGenerationCompletedEvent} en el topic
 * {@value KafkaTopicConstants#LOYALTY_DOCUMENT_GENERATION_COMPLETED}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentGenerationCompletedPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publish(String topic, DocumentGenerationCompletedEvent event) {
        Objects.requireNonNull(topic, "topic must not be null");
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(event.getIdempotencyId(), "event.idempotencyId must not be null");

        String partitionKey = event.getIdempotencyId();

        try {
            kafkaTemplate.send(topic, partitionKey, event);
            log.debug("[{}] Event published: topic={}, key={}",
                    LogConstants.LOG_PUBLICATION_EVENT_SENT, topic, partitionKey);
        } catch (Exception e) {
            log.error("[{}] Failed to publish event: topic={}, key={}, error={}",
                    LogConstants.LOG_PUBLICATION_EVENT_SENT, topic, partitionKey, e.getMessage());
            throw e;
        }
    }
}
