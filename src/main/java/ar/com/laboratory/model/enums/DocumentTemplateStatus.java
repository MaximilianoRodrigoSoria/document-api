package ar.com.laboratory.model.enums;

public enum DocumentTemplateStatus {

    ACTIVE,
    INACTIVE,
    DEPRECATED;

    public boolean isUsable() {
        return this == ACTIVE;
    }
}
