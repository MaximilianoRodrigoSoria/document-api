package ar.com.laboratory.integration.base;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Clase base para tests de integración.
 *
 * <p>Levanta un contexto Spring Boot completo con:
 * <ul>
 *   <li>PostgreSQL real via Testcontainers (mismas migraciones Flyway que producción).</li>
 *   <li>Kafka embebido via {@code @EmbeddedKafka} (sin broker externo).</li>
 * </ul>
 *
 * <p>Los tests de esta clase están marcados con {@code @Tag("integration")} y se
 * excluyen del ciclo {@code gradle test} por defecto. Para ejecutarlos:
 * <pre>./gradlew test -Pgroups=integration</pre>
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@EmbeddedKafka(
        partitions = 1,
        topics = {"document-generation-completed"},
        brokerProperties = {"listeners=PLAINTEXT://localhost:${kafka.port:0}", "port=0"})
public class BaseIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("testdb")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Datasource
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Flyway usa su propio datasource config
        registry.add("spring.flyway.url",      postgres::getJdbcUrl);
        registry.add("spring.flyway.user",     postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        // GCS: desactivar cliente real en tests de integración
        registry.add("gcs.emulator-host", () -> "http://localhost:4443");
    }
}
