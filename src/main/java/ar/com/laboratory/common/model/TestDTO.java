package ar.com.laboratory.common.model;

import lombok.*;

@Getter
@ToString
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TestDTO {
    private String code;
    private String message;

    public TestDTO(String message) {
        this.message = message;
    }
}
