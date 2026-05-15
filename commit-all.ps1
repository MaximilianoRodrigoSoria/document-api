<#
.SYNOPSIS
    Commits all pending changes in logical groups.
    Run once from PowerShell in the repo root.
    Safe to run with IntelliJ open — closes git processes first.
#>

Set-Location $PSScriptRoot
$env:GIT_OPTIONAL_LOCKS = "0"

function Commit([string]$msg, [string[]]$files) {
    Write-Host "`n>>> $($msg.Split("`n")[0])" -ForegroundColor Cyan
    foreach ($f in $files) { git add -- $f }
    git commit -m $msg
    if ($LASTEXITCODE -ne 0) { Write-Host "COMMIT FAILED — stopping." -ForegroundColor Red; exit 1 }
}

# ────────────────────────────────────────────────────────────────────────────
# COMMIT 1 — Fix: Kafka deserialization + @EnableScheduling
# ────────────────────────────────────────────────────────────────────────────
Commit @"
fix: resolve two bugs that blocked end-to-end document generation

1. Add @JsonProperty to DocumentGenerationRequestedEvent so Jackson
   correctly maps camelCase fields (idempotencyId, templateName) from
   the Kafka payload even though the global ObjectMapper uses SNAKE_CASE.
   Without this, all fields were null and only 1 of N messages was stored
   because findByIdempotencyId(null) matched every subsequent event.

2. Add @EnableScheduling to Application so the @Scheduled method in
   DocumentGenerationScheduler is actually registered. Without it the
   scheduler never ran and records stayed in PENDING indefinitely.
"@ @(
    "src/main/java/cl/tenpo/document/generator/api/Application.java",
    "src/main/java/cl/tenpo/document/generator/api/model/event/DocumentGenerationRequestedEvent.java"
)

# ────────────────────────────────────────────────────────────────────────────
# COMMIT 2 — Chore: migrate base project files to Java 21 / Spring Boot 3.3.3
# ────────────────────────────────────────────────────────────────────────────
Commit @"
chore: migrate base project to Java 21 and Spring Boot 3.3.3

- build.gradle: Java 21, Spring Boot 3.3.3, added flyway-database-postgresql,
  sourceSets exclusion for legacy placeholder package (document.generator.api)
- gradle.properties / gradle-wrapper: updated wrapper to latest
- settings.gradle: updated project name
- Dockerfile: updated base image to Java 21
- application*.yml: virtual threads, updated profiles, OTEL config
- logback-spring.xml: MDC fields, INFO level for local/test profiles
- test resources: updated application.yml for test context
- wiremock: updated example mapping
- catalog-info.yaml, .gitlab-ci.yml, .sonar-project.properties,
  .pre-commit-config.yaml, tenposervice-*.yaml: updated service references
- Legacy placeholder files removed: TestDTO, TestHandler, ObjectMapperFactory
"@ @(
    "build.gradle",
    "gradle.properties",
    "gradle/wrapper/gradle-wrapper.jar",
    "gradle/wrapper/gradle-wrapper.properties",
    "settings.gradle",
    "Dockerfile",
    "catalog-info.yaml",
    ".gitlab-ci.yml",
    ".pre-commit-config.yaml",
    ".sonar-project.properties",
    "tenposervice-private-values.yaml",
    "tenposervice-values.yaml",
    "wiremock/mappings/get-example.json",
    "src/main/resources/application.yml",
    "src/main/resources/application-local.yml",
    "src/main/resources/application-dev.yml",
    "src/main/resources/application-uat.yml",
    "src/main/resources/application-prod.yml",
    "src/main/resources/logback-spring.xml",
    "src/test/resources/application.yml",
    "src/main/java/cl/tenpo/document.generator.api/",
    "src/test/java/cl/tenpo/document.generator.api/"
)

# ────────────────────────────────────────────────────────────────────────────
# COMMIT 3 — Chore: local dev infrastructure (docker-compose, env, GCS data)
# ────────────────────────────────────────────────────────────────────────────
Commit @"
chore: add local development infrastructure

- docker-compose.yml: postgres 15.2, kafka 7.6.0 KRaft, fake-gcs-server,
  redis 7-alpine, wiremock — all on a shared bridge network
- .env.local: local env vars for DB, Kafka, GCS (gitignored)
- gcs/data/.gitkeep: placeholder so the GCS volume mounts correctly
- gcs/data/document-generator-bucket/.gitkeep: pre-creates the bucket
  directory so fake-gcs-server recognises it on a clean clone
"@ @(
    "docker-compose.yml",
    ".env.local",
    "gcs/"
)

# ────────────────────────────────────────────────────────────────────────────
# COMMIT 4 — Chore: update Kubernetes kustomization for all environments
# ────────────────────────────────────────────────────────────────────────────
Commit @"
chore: update kustomization for base, uat, stage and production

- base/deployment.yaml: correct secret key names for DB, Kafka and Redis
  credentials aligned with payment-loyalty conventions; OTEL namespace;
  health check path includes context-path
- base/service.yaml: fix release-namespace annotation
- base/kustomization.yaml: updated image and resource references
- uat/stage/production: namespace updated to tenpo-document-generator-api,
  env files updated with correct Spring profiles and REDIS_SENTINELS placeholder
- hpa.yaml and resources.yaml updated per environment
"@ @(
    "kustomization/"
)

# ────────────────────────────────────────────────────────────────────────────
# COMMIT 5 — Feat: domain model (entities, enums, events, DTOs)
# ────────────────────────────────────────────────────────────────────────────
Commit @"
feat: add domain model — entities, enums, events and DTOs

Entities:
- RawData: tracks the lifecycle of each document generation request
  (PENDING -> PROCESSING -> GENERATED -> PUBLISHED | ERROR -> FAILED)
- DocumentTemplate: catalog of JasperReports templates stored in GCS

Enums:
- RawDataStatus, DocumentTemplateStatus, DocumentDomain

Events (Kafka payloads):
- DocumentGenerationRequestedEvent (inbound, camelCase)
- DocumentGenerationCompletedEvent (outbound)

DTOs:
- template/: TemplateResponse, TemplateDetailResponse, TemplateUploadResponse,
             TemplateUpdateRequest, TemplateStatusRequest
- storage/: FileListResponse, FileMetadataResponse, FileUploadResponse
- ResponseErrorDto: standard error envelope
"@ @(
    "src/main/java/cl/tenpo/document/generator/api/model/entity/",
    "src/main/java/cl/tenpo/document/generator/api/model/enums/",
    "src/main/java/cl/tenpo/document/generator/api/model/event/DocumentGenerationCompletedEvent.java",
    "src/main/java/cl/tenpo/document/generator/api/model/dto/",
    "src/main/java/cl/tenpo/document/generator/api/model/ResponseErrorDto.java"
)

# ────────────────────────────────────────────────────────────────────────────
# COMMIT 6 — Feat: constants and exceptions
# ────────────────────────────────────────────────────────────────────────────
Commit @"
feat: add constants, domain exceptions and global error handler

Constants:
- AppConstants: max retry count, batch defaults
- KafkaTopicConstants: topic names and consumer group id
- LogConstants: MDC keys and structured log event names

Exceptions:
- DocumentGenerationException, TemplateNotFoundException,
  TemplateDownloadException, TemplateException,
  IdempotencyConflictException, StorageFileNotFoundException,
  StorageFileAlreadyExistsException, StorageInvalidFolderException

GlobalControllerAdvice: maps each exception to the correct HTTP status
"@ @(
    "src/main/java/cl/tenpo/document/generator/api/constants/",
    "src/main/java/cl/tenpo/document/generator/api/exception/"
)

# ────────────────────────────────────────────────────────────────────────────
# COMMIT 7 — Feat: Spring configuration beans
# ────────────────────────────────────────────────────────────────────────────
Commit @"
feat: add Spring configuration beans

- DocumentGenerationProperties: typed @ConfigurationProperties for
  schedule (fixedDelay, batchSize, recoveryTimeoutMinutes) and kafka topics
- KafkaConfig: producer with idempotence, consumer with MANUAL_IMMEDIATE ack,
  StringDeserializer (payload parsed manually for full control)
- GcsConfig: Storage bean supporting local emulator (NoCredentials + auto-
  create bucket), service-account JSON and ADC; ensureBucketExists() prevents
  first-upload failures on a clean environment
- SwaggerConfig, WebClientConfig, WebClientFilter
"@ @(
    "src/main/java/cl/tenpo/document/generator/api/config/"
)

# ────────────────────────────────────────────────────────────────────────────
# COMMIT 8 — Feat: Flyway migrations
# ────────────────────────────────────────────────────────────────────────────
Commit @"
feat: add Flyway database migrations

V1__init.sql: create schema data, tables raw_data and document_templates
  with indexes optimised for the scheduler query pattern
  (status + retry_count + template_name + created_at)
V2__seed_templates.sql: seed initial document_templates entries
"@ @(
    "src/main/resources/db/"
)

# ────────────────────────────────────────────────────────────────────────────
# COMMIT 9 — Feat: repositories and services
# ────────────────────────────────────────────────────────────────────────────
Commit @"
feat: add repositories and application services

Repositories:
- RawDataRepository: findPendingForProcessing with FOR UPDATE SKIP LOCKED,
  findStuckProcessing, findByIdempotencyId, countPendingToProcess
- DocumentTemplateRepository: findByNameAndStatus, findByDomain

Services:
- DocumentGenerationService: recoverStuckProcessing, claimPendingRecords,
  markGenerated, markPublished, markError with truncation
- TemplateService: upload, list, get, update status, delete
- StorageService: upload, download, list, delete files in GCS
"@ @(
    "src/main/java/cl/tenpo/document/generator/api/repository/",
    "src/main/java/cl/tenpo/document/generator/api/service/"
)

# ────────────────────────────────────────────────────────────────────────────
# COMMIT 10 — Feat: GCS storage client and template cache
# ────────────────────────────────────────────────────────────────────────────
Commit @"
feat: add GCS storage client and per-cycle template cache

GcsStorageClient: uploadDocument, downloadTemplate, uploadFile, downloadFile,
  getBlob, fileExists, listFiles, deleteFile with structured logging

TemplateCache: @Component scoped to the scheduler cycle that downloads each
  JasperReport template from GCS exactly once per processing batch,
  eliminating N+1 GCS calls when thousands of records share the same template
"@ @(
    "src/main/java/cl/tenpo/document/generator/api/storage/",
    "src/main/java/cl/tenpo/document/generator/api/cache/",
    "src/main/java/cl/tenpo/document/generator/api/util/"
)

# ────────────────────────────────────────────────────────────────────────────
# COMMIT 11 — Feat: Kafka consumer and publisher
# ────────────────────────────────────────────────────────────────────────────
Commit @"
feat: add Kafka consumer and completed-event publisher

DocumentGenerationRequestedConsumer:
  - Listens on document.generation.requested with MANUAL_IMMEDIATE ack
  - Three-branch idempotency: new -> PENDING, FAILED -> reset to PENDING,
    duplicate -> skip (no reprocessing of in-flight records)
  - Deserializes payload manually; ACK only after successful persistence

DocumentGenerationCompletedPublisher:
  - Publishes DocumentGenerationCompletedEvent to document.generation.completed
  - Uses KafkaTemplate<String, Object> with idempotent producer config
"@ @(
    "src/main/java/cl/tenpo/document/generator/api/consumer/",
    "src/main/java/cl/tenpo/document/generator/api/publisher/"
)

# ────────────────────────────────────────────────────────────────────────────
# COMMIT 12 — Feat: document generation scheduler and PDF generator
# ────────────────────────────────────────────────────────────────────────────
Commit @"
feat: add document generation scheduler and HTML/PDF generator

DocumentGenerationScheduler (@Scheduled fixedDelay):
  1. recoverStuckProcessing: resets PROCESSING records past timeout to PENDING
  2. claimPendingRecords: FOR UPDATE SKIP LOCKED batch claim
  3. For each record: getCompiledTemplate (cached) -> generate PDF ->
     uploadDocument -> markGenerated -> publish completed event -> markPublished
  - Errors are caught per-record; failed records increment retry_count
    and transition to ERROR (or FAILED after max retries)

HtmlPdfDocumentGenerator: renders HTML templates to PDF via Flying Saucer
"@ @(
    "src/main/java/cl/tenpo/document/generator/api/scheduler/",
    "src/main/java/cl/tenpo/document/generator/api/generator/"
)

# ────────────────────────────────────────────────────────────────────────────
# COMMIT 13 — Feat: REST controllers
# ────────────────────────────────────────────────────────────────────────────
Commit @"
feat: add REST controllers for templates, storage and health

TemplateController  POST/GET/PATCH/DELETE /v1/templates
StorageController   GET/POST/DELETE       /v1/storage/files
HealthController    GET                   /actuator/health (custom detail)
"@ @(
    "src/main/java/cl/tenpo/document/generator/api/controller/"
)

# ────────────────────────────────────────────────────────────────────────────
# COMMIT 14 — Docs: MkDocs site with Material theme and TTS player
# ────────────────────────────────────────────────────────────────────────────
Commit @"
docs: add MkDocs documentation site with Material theme and TTS player

- mkdocs.yml: Material theme (deep purple/amber, light/dark), navigation tabs,
  breadcrumbs, Mermaid, code copy, search-es, mike versioning
- requirements.txt: mkdocs, mkdocs-material, pymdown-extensions, mike
- docs/index.md: hero banner, cards-grid for all 8 sections, quick start
- docs/includes/abbreviations.md: project-specific glossary
- docs/context/onboarding.md: 5-min onboarding narration optimised for TTS
- docs/context/generar-mp3.py: exports onboarding text to MP3 via gTTS
- docs/stylesheets/extra.css + components.css: hero, cards, callouts
- docs/javascripts/tts-player.js: floating speaker button (Web Speech API)
- docs/imgs/: logo.svg, favicon.svg
- docs/overrides/.gitkeep
"@ @(
    "mkdocs.yml",
    "requirements.txt",
    "docs/index.md",
    "docs/includes/",
    "docs/context/",
    "docs/stylesheets/",
    "docs/javascripts/",
    "docs/imgs/",
    "docs/overrides/"
)

# ────────────────────────────────────────────────────────────────────────────
# COMMIT 15 — Feat: PowerShell Kafka publisher script
# ────────────────────────────────────────────────────────────────────────────
Commit @"
feat: add PowerShell Kafka event publisher for local testing

docs/scripts/publish_missions.ps1 replaces the Python kafka-python script
with a dependency-free PowerShell version that publishes via docker exec.

Features:
- 4 template types: compra-y-gana-personas-v1, compra-y-gana-empresas-v1,
  cashback-mensual-v1, programa-referidos-v1
- Random data generation (personas, empresas, meses, referidos)
- Interactive menu and CLI params: -Template, -Count, -Mision, -List, -DryRun
- Publishes JSON lines via kafka-console-producer inside the Kafka container
- No external dependencies beyond Docker and PowerShell 5+
"@ @(
    "docs/scripts/publish_missions.ps1"
)

# ────────────────────────────────────────────────────────────────────────────
# COMMIT 16 — Chore: update README
# ────────────────────────────────────────────────────────────────────────────
Commit @"
chore: update README with Java 21, Spring Boot 3.3.3 and current architecture

- Badges: Java 17 -> 21, Spring Boot 3.x -> 3.3.3, PDFBox -> JasperReports
- Architecture diagram updated to reflect scheduler-based pipeline
- Stack table updated (JasperReports, Redis, virtual threads)
- Quick start updated: PS1 publisher script instead of raw kafka-console-producer
- Diagnostic SQL queries preserved
"@ @(
    "README.md"
)

Write-Host "`n=== All commits done ===" -ForegroundColor Green
git log --oneline -20
