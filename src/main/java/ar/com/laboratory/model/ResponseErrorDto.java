package ar.com.laboratory.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.*;

@Getter
@ToString
@Builder
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@NoArgsConstructor
public class ResponseErrorDto {
    private String code;
    private String message;

    public ResponseErrorDto(String message) {
        this.message = message;
    }
}
