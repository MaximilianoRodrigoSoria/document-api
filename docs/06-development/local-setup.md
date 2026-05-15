# Setup local

## Requisitos

| Herramienta | Versión mínima | Notas |
|-------------|---------------|-------|
| Java | 17 | Se recomienda 17 LTS |
| Docker | 24+ | — |
| Docker Compose | 2.x | — |
| Gradle | 8.7 | Incluido en el wrapper; no requiere instalación manual |

---

## Levantar la infraestructura

```bash
docker-compose up -d
```

Esto inicia:

- **PostgreSQL 15.2** en `localhost:5432` — base de datos `files`, schema `data`
- **Kafka** en `localhost:9092` — modo KRaft (sin Zookeeper)
- **fake-gcs-server** en `localhost:4443` — emula Google Cloud Storage para desarrollo local
- **Redis 7** en `localhost:6379`

> No hay Zookeeper. El servicio usa Kafka en modo KRaft.
> No hay LocalStack ni AWS. El emulador GCS es `fsouza/fake-gcs-server`.

---

## Ejecutar la aplicación

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

O desde IntelliJ con el perfil `local` activo.

El perfil `local` activa `application-local.yml`, que apunta a `localhost:5432/files`, `localhost:9092` para Kafka y `localhost:4443` para fake-gcs-server.

---

## Primera ejecución — inicializar la BD

Al arrancar la app por primera vez, Flyway ejecuta automáticamente:

- **V1** — crea el schema `data`, las tablas `raw_data` y `document_templates`, índices y constraints
- **V2** — inserta el template inicial `compra-y-gana-empresas-prueba-002`

Si el schema `data` ya existe (por ejecuciones previas) pero no tiene historial de Flyway:

```bash
docker exec -it document-generator-api-postgres psql -U user -d files \
  -c "DROP SCHEMA data CASCADE; CREATE SCHEMA data;"
```

Luego reiniciá la app.

---

## Subir el template inicial a GCS (local)

El template `compra-y-gana-empresas-prueba-002.pdf` ya está en `gcs/data/document-generator-bucket/templates/` del repo. El fake-gcs-server lo sirve directamente al montar el volumen.

Para subir un template nuevo via la API:

```bash
curl -X POST "http://localhost:8080/document-generator-api/v1/templates" \
  -F "file=@docs/assets/compra-y-gana-empresas-prueba-002.pdf" \
  -F "name=compra-y-gana-empresas-prueba-002" \
  -F "domain=MISIONES" \
  -F "version=1.0.0"
```

---

## Endpoints disponibles (local)

| Método | Endpoint | Descripción |
|--------|----------|-------------|
| `GET` | `http://localhost:8080/document-generator-api/actuator/health` | Health check |
| `GET` | `http://localhost:8080/document-generator-api/swagger-ui.html` | Swagger UI |
| `GET` | `http://localhost:8080/document-generator-api/v3/api-docs` | OpenAPI spec |
| `POST` | `http://localhost:8080/document-generator-api/v1/templates` | Registrar template PDF |
| `GET` | `http://localhost:8080/document-generator-api/v1/templates` | Listar templates |
| `GET` | `http://localhost:8080/document-generator-api/v1/templates/{name}/fields` | Campos AcroForm |
| `GET` | `http://localhost:8080/document-generator-api/v1/storage/files` | Listar archivos en GCS |
| `GET` | `http://localhost:8080/document-generator-api/v1/storage/files/content?gcsKey=...` | Descargar archivo |

---

## Ejecutar los tests

```bash
# Unit + integration tests (H2 + EmbeddedKafka, no requieren Docker)
./gradlew test
```

Los tests usan H2 en modo PostgreSQL y EmbeddedKafka. No requieren Docker.

---

## Conectarse a la BD local

```bash
docker exec -it document-generator-api-postgres psql -U user -d files
```

Consultas rápidas:

```sql
-- Estado de los registros
SELECT status, count(*) FROM data.raw_data GROUP BY status;

-- Templates registrados
SELECT name, domain, version, status FROM data.document_templates;

-- Historial Flyway
SELECT version, description, installed_on, success
FROM data.flyway_schema_history ORDER BY installed_rank;
```

---

## Producir un evento de prueba manualmente

```bash
docker exec -it document-generator-api-kafka kafka-console-producer \
  --broker-list localhost:9092 \
  --topic document.generation.requested \
  --property "parse.key=true" \
  --property "key.separator=:"
```

Payload de ejemplo (pegar en la consola del producer):

```
550e8400-e29b-41d4-a716-446655440000:{"idempotencyId":"550e8400-e29b-41d4-a716-446655440000","domain":"MISIONES","templateName":"compra-y-gana-empresas-prueba-002","data":{"nombre":"Juan Pérez","rut":"12345678-9","monto":"50000","fecha":"2026-05-09"}}
```

Luego verificar que el registro fue insertado:

```sql
SELECT process_id, idempotency_id, template_name, status
FROM data.raw_data
ORDER BY created_at DESC
LIMIT 5;
```

El scheduler corre cada 30 segundos en local (`*/30 * * * * *`). Después de una ejecución, el registro debería pasar a `PUBLISHED`.

---

## Variables de entorno (ambientes no-locales)

| Variable | Descripción | Ejemplo |
|----------|-------------|---------|
| `SPRING_DATASOURCE_URL` | JDBC URL de PostgreSQL | `jdbc:postgresql://host:5432/files` |
| `SPRING_DATASOURCE_USERNAME` | Usuario de la BD | `user` |
| `SPRING_DATASOURCE_PASSWORD` | Contraseña de la BD | — |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | Brokers de Kafka | `kafka:9092` |
| `GCS_BUCKET_NAME` | Bucket GCS | `document-generator-bucket` |
| `GCS_EMULATOR_HOST` | Host del emulador (solo local) | `http://localhost:4443` |
| `DOCUMENT_GENERATION_SCHEDULE_CRON` | Cron del scheduler | `0 */5 * * * *` |
| `DOCUMENT_GENERATION_SCHEDULE_BATCH_SIZE` | Registros por ciclo | `100` |
| `DOCUMENT_GENERATION_SCHEDULE_RECOVERY_TIMEOUT_MINUTES` | Timeout recovery | `30` |
