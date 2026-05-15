package ar.com.laboratory.exception;

public class DocumentGenerationException extends RuntimeException {

    private final Long processId;

    public DocumentGenerationException(Long processId, String message) {
        super(buildMessage(processId, message));
        this.processId = processId;
    }

    public DocumentGenerationException(Long processId, String message, Throwable cause) {
        super(buildMessage(processId, message), cause);
        this.processId = processId;
    }

    public DocumentGenerationException(String message) {
        super(message);
        this.processId = null;
    }

    public DocumentGenerationException(String message, Throwable cause) {
        super(message, cause);
        this.processId = null;
    }

    public Long getProcessId() {
        return processId;
    }

    private static String buildMessage(Long processId, String message) {
        return processId != null
                ? String.format("[processId=%d] %s", processId, message)
                : message;
    }
}
