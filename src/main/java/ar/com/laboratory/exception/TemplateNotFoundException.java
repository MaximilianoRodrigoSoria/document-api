package ar.com.laboratory.exception;

public class TemplateNotFoundException extends RuntimeException {

    private final String templateName;

    public TemplateNotFoundException(String templateName) {
        super(String.format("Active template not found for name '%s'", templateName));
        this.templateName = templateName;
    }

    public String getTemplateName() {
        return templateName;
    }
}
