package ar.com.laboratory.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@Configuration
@ConfigurationProperties(prefix = "document-generation")
public class DocumentGenerationProperties {

    @Valid
    @NotNull
    private Schedule schedule = new Schedule();

    @Valid
    @NotNull
    private Kafka kafka = new Kafka();

    @Getter
    @Setter
    public static class Schedule {

        @Min(1000)
        private long fixedDelay = 5_000L;

        @Min(1)
        private int batchSize = 500;

        @Min(1)
        private int recoveryTimeoutMinutes = 30;
    }

    @Getter
    @Setter
    public static class Kafka {

        @Valid
        @NotNull
        private Topics topics = new Topics();

        @NotBlank
        private String consumerGroupId = "document-generator-api";

        @Getter
        @Setter
        public static class Topics {

            @NotBlank
            private String requested = "document.generation.requested";

            @NotBlank
            private String completed = "document.generation.completed";
        }
    }
}
