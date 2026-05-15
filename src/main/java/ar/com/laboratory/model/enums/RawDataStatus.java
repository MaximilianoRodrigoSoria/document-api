package ar.com.laboratory.model.enums;

import java.util.EnumSet;
import java.util.Set;

public enum RawDataStatus {

    PENDING,
    PROCESSING,
    GENERATED,
    PUBLISHED,
    ERROR,
    FAILED;

    private static final Set<RawDataStatus> RETRYABLE = EnumSet.of(ERROR);
    private static final Set<RawDataStatus> TERMINAL  = EnumSet.of(PUBLISHED, FAILED);

    public boolean isRetryable() {
        return RETRYABLE.contains(this);
    }

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
