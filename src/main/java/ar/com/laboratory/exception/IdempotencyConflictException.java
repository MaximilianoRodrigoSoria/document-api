package ar.com.laboratory.exception;

public class IdempotencyConflictException extends RuntimeException {

    private final String idempotencyId;

    public IdempotencyConflictException(String idempotencyId) {
        super(String.format("Document request already exists for idempotencyId '%s'", idempotencyId));
        this.idempotencyId = idempotencyId;
    }

    public String getIdempotencyId() {
        return idempotencyId;
    }
}
