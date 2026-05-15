package ar.com.laboratory.constants;

public final class LogConstants {

    private LogConstants() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static final String MDC_PROCESS_ID      = "processId";
    public static final String MDC_IDEMPOTENCY_ID  = "idempotencyId";
    public static final String MDC_TEMPLATE_NAME   = "templateName";

    public static final String LOG_CONSUMER_RECEIVED             = "consumer.event.received";
    public static final String LOG_CONSUMER_IDEMPOTENCY_SKIP     = "consumer.idempotency.skip";
    public static final String LOG_CONSUMER_IDEMPOTENCY_REPROCESS = "consumer.idempotency.reprocess";
    public static final String LOG_CONSUMER_PERSISTED            = "consumer.event.persisted";

    public static final String LOG_SCHEDULER_START      = "scheduler.run.start";
    public static final String LOG_SCHEDULER_DONE       = "scheduler.run.done";
    public static final String LOG_SCHEDULER_NO_RECORDS = "scheduler.run.no.records";
    public static final String LOG_SCHEDULER_GCS_UPLOADED = "scheduler.gcs.uploaded";
    public static final String LOG_SCHEDULER_PUBLISHED  = "scheduler.event.published";
    public static final String LOG_SCHEDULER_RECORD_ERROR = "scheduler.record.error";

    public static final String LOG_RECOVERY_STUCK_FOUND = "recovery.stuck.records.found";
    public static final String LOG_RECOVERY_RESET_DONE  = "recovery.reset.completed";

    public static final String LOG_CACHE_HIT  = "cache.template.hit";
    public static final String LOG_CACHE_MISS = "cache.template.miss";

    public static final String LOG_PROCESSOR_START         = "processor.start";
    public static final String LOG_PROCESSOR_PDF_GENERATED = "processor.pdf.generated";
    public static final String LOG_PROCESSOR_GCS_UPLOADED  = "processor.gcs.uploaded";
    public static final String LOG_PROCESSOR_ERROR         = "processor.error";

    public static final String LOG_WRITER_GENERATED  = "writer.generation.status.generated";
    public static final String LOG_WRITER_PUBLISHED  = "writer.publication.status.published";
    public static final String LOG_WRITER_ERROR      = "writer.status.error";

    public static final String LOG_PUBLICATION_EVENT_SENT = "publisher.event.sent";
}
