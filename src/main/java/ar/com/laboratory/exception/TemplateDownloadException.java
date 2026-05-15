package ar.com.laboratory.exception;

public class TemplateDownloadException extends RuntimeException {

    private final String gcsKey;

    public TemplateDownloadException(String gcsKey, Throwable cause) {
        super(String.format("Failed to download template from GCS key '%s'", gcsKey), cause);
        this.gcsKey = gcsKey;
    }

    public TemplateDownloadException(String gcsKey, String message) {
        super(String.format("Failed to download template from GCS key '%s': %s", gcsKey, message));
        this.gcsKey = gcsKey;
    }

    public String getGcsKey() {
        return gcsKey;
    }
}
