# ADR-002 — Migracion de AWS S3 a Google Cloud Storage

## Informacion General

| Campo | Valor |
|-------|-------|
| ID | ADR-002 |
| Titulo | Almacenamiento de documentos en Google Cloud Storage en lugar de AWS S3 |
| Estado | **Aceptado** |
| Fecha | 2026-05-08 |
| Dominio | Infraestructura / Storage |
| Autor | Maximiliano Soria |

---

## Contexto

El ADR-001 definio AWS S3 como almacenamiento de documentos PDF generados. Durante la implementacion se identifico que el entorno de infraestructura del proyecto ya opera sobre Google Cloud Platform (GKE, Workload Identity, IAM). Mantener una dependencia de AWS exclusivamente para este servicio agrega friction operativa innecesaria.

---

## Decision

Se reemplaza AWS S3 / LocalStack por **Google Cloud Storage** y **fake-gcs-server** (emulador local).

---

## Cambios respecto a ADR-001

| Aspecto | ADR-001 (S3) | ADR-002 (GCS) |
|---------|-------------|---------------|
| SDK | `software.amazon.awssdk:s3` | `com.google.cloud:google-cloud-storage` |
| Emulador local | LocalStack | fake-gcs-server |
| Configuracion de cliente | `S3Client` + endpoint override | `Storage` + `StorageOptions` con emulador host |
| Autenticacion en produccion | Rol IAM de AWS | Workload Identity de GCP |
| Config bean | `S3Config.java` | `GcsConfig.java` |
| Cliente de storage | `S3StorageClient.java` | `GcsStorageClient.java` |
| URL de documento | `s3://bucket/...` | `gs://bucket/...` |

---

## Motivacion

- El servidor de produccion corre en GKE con Workload Identity de GCP; no se requieren credenciales adicionales para GCS.
- Se elimina LocalStack del `docker-compose.yml` y se reemplaza por `fake-gcs-server`, mas liviano y especifico para GCS.
- La API de `google-cloud-storage` es mas idiomatica para el ecosistema GCP.
- Se evita mantener dos clouds (AWS + GCP) para un mismo servicio.

---

## Consecuencias

### Positivas
- Menor configuracion de credenciales en produccion (Workload Identity nativo de GCP).
- Emulador local mas ligero y preciso (fake-gcs-server vs LocalStack).
- Alineacion con el stack de infraestructura del proyecto.

### Negativas
- El ADR-001 y documentacion inicial referencian S3; deben actualizarse.
- Los tests de integracion que usaban LocalStack deben migrar a fake-gcs-server.

---

## Estructura del bucket GCS

```
gs://<bucket>/
    misiones/           <- Documentos generados del dominio MISIONES
    loans/              <- (futuro) Documentos del dominio LOANS
    <domain>/           <- Patron: carpeta = domain del registro en minusculas
```

La carpeta destino se deriva del campo `type` del registro: `item.getType().toLowerCase()`.
Si el campo es nulo o vacio, se usa `documents/` como fallback.
