package ar.com.laboratory.model.dto.storage;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FileUploadResponse {
    private final String gcsKey;
    private final String bucket;
    private final long size;
    private final String contentType;
    private final String uploadedAt;
    private final String gcsUri;
}
