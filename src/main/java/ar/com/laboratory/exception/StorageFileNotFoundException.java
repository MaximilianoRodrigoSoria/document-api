package ar.com.laboratory.exception;

public class StorageFileNotFoundException extends RuntimeException {

    private final String gcsKey;

    public StorageFileNotFoundException(String gcsKey) {
        super("File not found in GCS: '" + gcsKey + "'");
        this.gcsKey = gcsKey;
    }

    public String getGcsKey() {
        return gcsKey;
    }
}
