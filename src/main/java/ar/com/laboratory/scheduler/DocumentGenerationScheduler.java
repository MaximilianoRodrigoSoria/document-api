package ar.com.laboratory.scheduler;

import ar.com.laboratory.cache.TemplateCache;
import ar.com.laboratory.config.DocumentGenerationProperties;
import ar.com.laboratory.constants.LogConstants;
import ar.com.laboratory.exception.DocumentGenerationException;
import ar.com.laboratory.generator.HtmlPdfDocumentGenerator;
import ar.com.laboratory.model.entity.RawData;
import ar.com.laboratory.model.event.DocumentGenerationCompletedEvent;
import ar.com.laboratory.publisher.DocumentGenerationCompletedPublisher;
import ar.com.laboratory.service.DocumentGenerationService;
import ar.com.laboratory.storage.GcsStorageClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentGenerationScheduler {

    private final DocumentGenerationService            service;
    private final TemplateCache                        templateCache;
    private final HtmlPdfDocumentGenerator             htmlPdfDocumentGenerator;
    private final GcsStorageClient                     gcsStorageClient;
    private final DocumentGenerationCompletedPublisher publisher;
    private final DocumentGenerationProperties         properties;
    private final ObjectMapper                         objectMapper;

    @Scheduled(fixedDelayString = "${document-generation.schedule.fixed-delay}")
    public void process() {
        Instant start = Instant.now();

        int recovered = service.recoverStuckProcessing(
                properties.getSchedule().getRecoveryTimeoutMinutes());

        List<RawData> claimed = service.claimPendingRecords(
                properties.getSchedule().getBatchSize());

        if (claimed.isEmpty()) {
            log.debug("[{}] No pending records to process", LogConstants.LOG_SCHEDULER_NO_RECORDS);
            return;
        }

        log.info("[{}] Cycle started: claimed={}, recovered={}",
                LogConstants.LOG_SCHEDULER_START, claimed.size(), recovered);

        int success = 0;
        int errors  = 0;

        for (RawData record : claimed) {
            boolean ok = processOne(record);
            if (ok) success++;
            else    errors++;
        }

        long elapsedMs = Duration.between(start, Instant.now()).toMillis();
        log.info("[{}] Cycle done: claimed={}, success={}, errors={}, recovered={}, elapsedMs={}",
                LogConstants.LOG_SCHEDULER_DONE, claimed.size(), success, errors, recovered, elapsedMs);
    }

    private boolean processOne(RawData record) {
        MDC.put(LogConstants.MDC_PROCESS_ID,     String.valueOf(record.getProcessId()));
        MDC.put(LogConstants.MDC_IDEMPOTENCY_ID, record.getIdempotencyId());
        MDC.put(LogConstants.MDC_TEMPLATE_NAME,  record.getTemplateName());

        try {
            var compiledTemplate = templateCache.getCompiledTemplate(record.getTemplateName());
            Map<String, Object> fields = deserializeData(record.getData());
            byte[] pdfBytes = htmlPdfDocumentGenerator.generate(compiledTemplate, fields);

            String documentUrl = gcsStorageClient.uploadDocument(
                    record.getIdempotencyId(), record.getType(), pdfBytes);

            log.debug("[{}] PDF generated and uploaded: processId={}, size={} bytes, url={}",
                    LogConstants.LOG_SCHEDULER_GCS_UPLOADED,
                    record.getProcessId(), pdfBytes.length, documentUrl);

            service.markGenerated(record, documentUrl);

            String topic = properties.getKafka().getTopics().getCompleted();
            publisher.publish(topic, buildEvent(record, documentUrl));

            service.markPublished(record);

            log.debug("[{}] Event published and record marked PUBLISHED: processId={}",
                    LogConstants.LOG_SCHEDULER_PUBLISHED, record.getProcessId());

            return true;

        } catch (Exception e) {
            log.error("[{}] Record processing failed: processId={}, error={}",
                    LogConstants.LOG_SCHEDULER_RECORD_ERROR, record.getProcessId(), e.getMessage(), e);
            service.markError(record, e.getMessage());
            return false;
        } finally {
            MDC.remove(LogConstants.MDC_PROCESS_ID);
            MDC.remove(LogConstants.MDC_IDEMPOTENCY_ID);
            MDC.remove(LogConstants.MDC_TEMPLATE_NAME);
        }
    }

    private Map<String, Object> deserializeData(String data) {
        return Optional.ofNullable(data)
                .filter(d -> !d.isBlank())
                .map(d -> {
                    try {
                        return objectMapper.<Map<String, Object>>readValue(d, new TypeReference<>() {});
                    } catch (Exception e) {
                        throw new DocumentGenerationException(
                                "Failed to deserialize 'data' field as JSON: " + e.getMessage(), e);
                    }
                })
                .orElse(Collections.emptyMap());
    }

    private DocumentGenerationCompletedEvent buildEvent(RawData record, String documentUrl) {
        return DocumentGenerationCompletedEvent.builder()
                .idempotencyId(record.getIdempotencyId())
                .domain(record.getType())
                .templateName(record.getTemplateName())
                .documentUrl(documentUrl)
                .status("GENERATED")
                .processedAt(record.getProcessedAt())
                .build();
    }
}
