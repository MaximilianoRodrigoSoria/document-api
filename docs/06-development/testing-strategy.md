# Estrategia de Testing

## Principios

El proyecto sigue una piramide de testing clasica: muchos tests unitarios rapidos en la base, tests de integracion selectivos en el medio, y ningun test end-to-end automatizado (el trigger manual via curl cumple ese rol en desarrollo).

Los tests de integracion requieren Docker y estan marcados con `@Tag("integration")` para excluirlos del ciclo de build normal.

---

## Capas de test

### 1. Tests unitarios

Ejecutan sin Docker ni bases de datos reales. Usan Mockito para colaboradores y AssertJ para aserciones.

**Herramientas:** JUnit 5, Mockito, AssertJ, Spring Boot Test (slice tests `@WebMvcTest`)

**Scope:** toda la logica de negocio en `scheduler`, `service`, `generator`, `controller`, `storage`.

**Convencion de nombre:** `*Test.java`

#### Clases cubiertas actualmente

| Clase | Test | Casos cubiertos |
|-------|------|-----------------|
| `DocumentGenerationScheduler` | `DocumentGenerationSchedulerTest` | ciclo completo, fallo en PDF/GCS/Kafka, data nulo, ciclo mixto |
| `DocumentGenerationService` | `DocumentGenerationServiceTest` | recover, claim, markGenerated, markPublished, markError (retry/failed) |
| `HtmlPdfDocumentGenerator` | `HtmlPdfDocumentGeneratorTest` | render Mustache + conversion Flying Saucer, inputs invalidos |
| `StorageService` | `StorageServiceTest` | upload, list, metadata, download, contentType, delete + validaciones folder |
| `TemplateService` | `TemplateServiceTest` | getTemplateDetail, updateTemplate, updateStatus |
| `TemplateController` | `TemplateControllerTest` | 8 endpoints con `@WebMvcTest` |

#### Ejemplo — DocumentGenerationScheduler

```java
@ExtendWith(MockitoExtension.class)
class DocumentGenerationSchedulerTest {

    @Mock private DocumentGenerationService           service;
    @Mock private TemplateCache                       templateCache;
    @Mock private HtmlPdfDocumentGenerator            htmlPdfDocumentGenerator;
    @Mock private GcsStorageClient                    gcsStorageClient;
    @Mock private DocumentGenerationCompletedPublisher publisher;
    @Mock private ObjectMapper                        objectMapper;

    @Test
    void executesFullPipeline() throws Exception {
        // given: record reclamado, template compilado, PDF generado, GCS ok
        // when: scheduler.process()
        // then: markGenerated + publish + markPublished; nunca markError
    }
}
```

#### Ejemplo — TemplateController (WebMvcTest)

```java
@WebMvcTest(TemplateController.class)
class TemplateControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean  private TemplateService templateService;

    @Test
    void returns200WithHtml() throws Exception {
        when(templateService.getTemplateContent(NAME)).thenReturn(CONTENT);

        mockMvc.perform(get("/v1/templates/{name}/preview", NAME))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
    }
}
```

**Casos a cubrir por scheduler:**
- Ciclo vacio: `claimed` vacio → retorno inmediato, ningun colaborador invocado
- Exito completo: `getCompiledTemplate` → `generate` → `uploadDocument` → `markGenerated` → `publish` → `markPublished`
- Fallo en PDF: `DocumentGenerationException` → `markError`, no llega a GCS ni Kafka
- Fallo en GCS: excepcion → `markError`, no llega a Kafka
- Fallo en Kafka: `markGenerated` ya ocurrio; `markError`, no `markPublished`
- `data` nulo o en blanco: `ObjectMapper` no invocado, `Collections.emptyMap()` a `generate`
- Ciclo mixto: continua procesando aunque un registro falle

---

### 2. Tests de contexto (smoke tests)

Verifican que el contexto de Spring arranca correctamente con la configuracion minima.

**Herramienta:** `@SpringBootTest` con H2 + `@EmbeddedKafka`

**Convencion de nombre:** `*ContextTest.java` o `*ApplicationTest.java`

```java
@SpringBootTest
@AutoConfigureTestDatabase(replace = Replace.ANY)
class BatchDocumentGeneratorApplicationTest {
    @Test
    void contextLoads() { }
}
```

---

### 3. Tests de integracion

Requieren Docker. Usan Testcontainers para levantar instancias reales de PostgreSQL, Kafka y `fake-gcs-server`.

**Herramientas:** Testcontainers, `fake-gcs-server` via `GenericContainer`

**Convencion de nombre:** `*IT.java`

**Para ejecutar:**
```bash
./gradlew test -Dgroups=integration
```

**Casos tipicos:**
- El consumer persiste correctamente un evento Kafka como `PENDING`
- El consumer hace ACK sin persistir ante `idempotency_id` duplicado
- El scheduler completa el ciclo: `PENDING -> GENERATED -> PUBLISHED`
- `recoverStuckProcessing` resetea registros `PROCESSING` colgados
- `GcsStorageClient` sube y descarga correctamente un objeto

#### Configuracion de Testcontainers

```java
@Container
static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.2")
        .withDatabaseName("document_generator_db");

@Container
static KafkaContainer kafka = new KafkaContainer(
        DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

@Container
static GenericContainer<?> fakeGcs = new GenericContainer<>("fsouza/fake-gcs-server:1.47.8")
        .withExposedPorts(4443)
        .withCommand("-scheme", "http");
```

---

## Configuracion Gradle

```groovy
test {
    useJUnitPlatform {
        if (!System.getProperty("groups", "").contains("integration")) {
            excludeTags "integration"
        }
    }
    maxHeapSize '512m'
    finalizedBy jacocoTestReport
}
```

Para correr solo integracion:
```bash
./gradlew test -Dgroups=integration
```

Para correr todos:
```bash
./gradlew test -Dgroups=unit,integration
```

---

## Cobertura

JaCoCo genera reportes en `build/reports/jacoco/test/`:
- HTML: `index.html`
- XML: `jacocoTestReport.xml` (para SonarQube / CI)

El umbral minimo configurado es **80%** de cobertura global.

```bash
# Generar reporte
./gradlew test jacocoTestReport

# Verificar umbral
./gradlew jacocoTestCoverageVerification
```

---

## Patrones recomendados

### Verificacion de MDC limpiado tras el proceso

```java
// El scheduler limpia el MDC en el bloque finally de processOne()
// Verificar que no queden entradas residuales:
assertThat(MDC.get(LogConstants.MDC_PROCESS_ID)).isNull();
assertThat(MDC.get(LogConstants.MDC_IDEMPOTENCY_ID)).isNull();
```

### Test de data nulo en el scheduler

```java
@Test
void usesEmptyMapWhenDataIsNull() throws Exception {
    RawData record = buildRecord(null);
    // ...setup...
    scheduler.process();
    verify(objectMapper, never()).readValue(anyString(), any(TypeReference.class));
    verify(htmlPdfDocumentGenerator).generate(any(), eq(Collections.emptyMap()));
}
```

### Test de idempotencia en consumer

```java
@Test
void duplicateEventIsIgnored() {
    when(rawDataRepository.findByIdempotencyId(any()))
            .thenReturn(Optional.of(existingRecord));
    consumer.consume(event, ack);
    verify(rawDataRepository, never()).save(any());
    verify(ack).acknowledge();
}
```

### Test de TemplateController con WebMvcTest

```java
@Test
void returns400WhenVersionInvalid() throws Exception {
    mockMvc.perform(put("/v1/templates/{name}", NAME)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"domain\":\"MISIONES\",\"version\":\"v2\",\"content\":\"html\"}"))
            .andExpect(status().isBadRequest());
}
```

---

## Que NO testear en unitarios

- La configuracion de Spring (`@Bean`, `@Configuration`) — lo cubre el smoke test.
- La serializacion/deserializacion de Kafka — cubierto por los tests de integracion.
- Las queries JPA complejas — cubierto por tests de repositorio con H2 o Testcontainers.
- El flujo completo end-to-end — cubierto por tests de integracion.
