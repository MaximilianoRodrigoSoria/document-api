---
hide:
  - navigation
---

<div class="hero-banner" markdown>

<img src="assets/banner.gif" alt="document-generator-api" style="width:100%;border-radius:8px;margin-bottom:1.5rem;"/>

# 📄 document-generator-api

Microservicio de generación asíncrona de documentos PDF.  
Recibe solicitudes vía Kafka · persiste en PostgreSQL · llena plantillas HTML Mustache · sube a GCS · publica el resultado de vuelta a Kafka.

**Java 21 · Spring Boot 3.3.3 · Kafka · PostgreSQL 15 · GCS · Flying Saucer**

</div>

---

## ¿Qué encontrás en esta documentación?

<div class="cards-grid" markdown>

<div class="card" markdown>

### 🔭 Visión general

Qué hace el servicio, cómo está diseñado y por qué se tomaron las decisiones arquitectónicas más importantes.

[Ver overview →](01-overview/README.md)

</div>

<div class="card" markdown>

### 🧩 Dominio

Templates PDF, máquina de estados de `raw_data` y el esquema de los eventos Kafka que consume y publica.

[Ver dominio →](02-domain/templates.md)

</div>

<div class="card" markdown>

### ⚙️ Scheduler

El corazón del servicio: ciclo de recovery → claim → generate → publish, con control de concurrencia y reintentos.

[Ver scheduler →](03-batch/batch-job.md)

</div>

<div class="card" markdown>

### 🌐 API REST

Endpoints para registrar templates PDF, gestionar archivos en GCS y consultar el estado de los jobs.

[Ver API →](04-api/templates-api.md)

</div>

<div class="card" markdown>

### 🏛️ Infraestructura

Base de datos, configuración de Kafka, Google Cloud Storage y observabilidad con MDC estructurado.

[Ver infraestructura →](05-infrastructure/database.md)

</div>

<div class="card" markdown>

### 💻 Desarrollo

Setup local con Docker Compose, estructura de paquetes, estrategia de testing y guía para agregar un nuevo tipo de documento.

[Ver desarrollo →](06-development/local-setup.md)

</div>

<div class="card" markdown>

### 🚨 Operaciones

Runbook con los escenarios de fallo más frecuentes, gestión de templates en producción y trigger manual del scheduler.

[Ver operaciones →](07-operations/runbook.md)

</div>

</div>

---

## Arranque rápido

```bash
# 1. Infraestructura local
docker-compose up -d

# 2. Ejecutar con perfil local
./gradlew bootRun --args='--spring.profiles.active=local'

# 3. Verificar salud
curl http://localhost:8080/document-generator-api/actuator/health
```

La app levanta en `http://localhost:8080/document-generator-api`. Flyway ejecuta las migraciones automáticamente al iniciar.

!!! tip "Script de publicación"
    Para enviar eventos de prueba a Kafka sin salir de la terminal:
    ```powershell
    # Menú interactivo con 4 tipos de template
    .\docs\scripts\publish_missions.ps1

    # O en modo directo
    .\docs\scripts\publish_missions.ps1 -Template 1 -Count 10
    .\docs\scripts\publish_missions.ps1 -List
    ```

---

## Links locales

| Recurso | URL |
|---------|-----|
| Swagger UI | `http://localhost:8080/document-generator-api/swagger-ui.html` |
| Health check | `http://localhost:8080/document-generator-api/actuator/health` |
| Templates API | `http://localhost:8080/document-generator-api/v1/templates` |
| Storage API | `http://localhost:8080/document-generator-api/v1/storage/files` |
| Métricas | `http://localhost:8080/document-generator-api/actuator/metrics` |
