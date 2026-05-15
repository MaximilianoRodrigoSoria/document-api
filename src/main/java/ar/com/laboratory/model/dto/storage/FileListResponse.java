package ar.com.laboratory.model.dto.storage;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FileListResponse {
    private final String folder;
    private final int pageSize;
    private final String nextPageToken;
    private final List<FileMetadataResponse> files;
}
