package ar.com.laboratory.exception;

public class StorageInvalidFolderException extends RuntimeException {

    private final String folder;

    public StorageInvalidFolderException(String folder, String reason) {
        super("Invalid folder '" + folder + "': " + reason);
        this.folder = folder;
    }

    public String getFolder() {
        return folder;
    }
}
