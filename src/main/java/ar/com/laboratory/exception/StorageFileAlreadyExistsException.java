package ar.com.laboratory.exception;

public class StorageFileAlreadyExistsException extends RuntimeException {

    private final String gcsKey;

    public StorageFileAlreadyExistsException(String gcsKey) {
        super("File already exists in GCS: '" + gcsKey + "'. Use overwrite=true to replace it.");
        this.gcsKey = gcsKey;
    }

    public String getGcsKey() {
        return gcsKey;
    }
}
