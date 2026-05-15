package ar.com.laboratory.cache;

import ar.com.laboratory.constants.LogConstants;
import ar.com.laboratory.exception.TemplateNotFoundException;
import ar.com.laboratory.model.entity.DocumentTemplate;
import ar.com.laboratory.model.enums.DocumentTemplateStatus;
import ar.com.laboratory.repository.DocumentTemplateRepository;
import ar.com.laboratory.storage.GcsStorageClient;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache singleton de templates activos.
 *
 * <p>Soporta dos modalidades de template:
 * <ul>
 *   <li><b>PDF (HTML Mustache)</b>: el contenido HTML se almacena en la columna
 *       {@code content} de la tabla {@code data.document_templates}. El primer
 *       acceso compila el template con Mustache y lo guarda en {@link #compiledCache}.</li>
 *   <li><b>DOCX (Apache POI)</b>: el binario {@code .docx} se almacena en GCS,
 *       referenciado por la columna {@code gcs_key}. El primer acceso descarga los
 *       bytes y los guarda en {@link #docxBytesCache}.</li>
 * </ul>
 *
 * <p>El cache es un singleton Spring ({@code @Component}) — vive durante toda la JVM.
 * Para forzar la recarga de un template (tras una actualización en DB o GCS) se
 * puede invocar {@link #invalidate(String)}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TemplateCache {

    private final DocumentTemplateRepository templateRepository;
    private final GcsStorageClient           gcsStorageClient;

    private static final MustacheFactory MUSTACHE_FACTORY = new DefaultMustacheFactory();

    private final Map<String, DocumentTemplate> entityCache    = new ConcurrentHashMap<>();
    private final Map<String, Mustache>         compiledCache  = new ConcurrentHashMap<>();
    private final Map<String, byte[]>           docxBytesCache = new ConcurrentHashMap<>();

    /**
     * Retorna el template Mustache compilado para un template de tipo PDF.
     * Primer acceso: carga la entidad desde DB y compila el HTML.
     * Accesos posteriores: retorna desde el cache en memoria.
     */
    public Mustache getCompiledTemplate(String templateName) {
        Objects.requireNonNull(templateName, "templateName must not be null");
        return compiledCache.computeIfAbsent(templateName, name -> {
            DocumentTemplate entity = entityCache.computeIfAbsent(name, this::loadEntity);
            String html = entity.getContent();
            if (html == null || html.isBlank()) {
                throw new TemplateNotFoundException(
                        templateName + " (content is empty — expected HTML for PDF template)");
            }
            Mustache compiled = MUSTACHE_FACTORY.compile(new StringReader(html), name);
            log.debug("[{}] PDF template compiled and cached: template={}, size={} chars",
                    LogConstants.LOG_CACHE_MISS, name, html.length());
            return compiled;
        });
    }

    /**
     * Retorna los bytes del archivo {@code .docx} template para un template de tipo DOCX.
     * Primer acceso: carga la entidad desde DB y descarga el binario desde GCS.
     * Accesos posteriores: retorna desde el cache en memoria.
     */
    public byte[] getDocxTemplateBytes(String templateName) {
        Objects.requireNonNull(templateName, "templateName must not be null");
        return docxBytesCache.computeIfAbsent(templateName, name -> {
            DocumentTemplate entity = entityCache.computeIfAbsent(name, this::loadEntity);
            String gcsKey = entity.getGcsKey();
            if (gcsKey == null || gcsKey.isBlank()) {
                throw new TemplateNotFoundException(
                        templateName + " (gcs_key is empty — expected GCS path for DOCX template)");
            }
            byte[] bytes = gcsStorageClient.downloadTemplate(gcsKey);
            log.debug("[{}] DOCX template downloaded and cached: template={}, gcsKey={}, size={} bytes",
                    LogConstants.LOG_CACHE_MISS, name, gcsKey, bytes.length);
            return bytes;
        });
    }

    /**
     * Retorna la entidad {@link DocumentTemplate} para el template indicado.
     * Útil para leer metadatos (ej. {@code outputType}) sin pasar por el generador.
     */
    public DocumentTemplate getEntity(String templateName) {
        Objects.requireNonNull(templateName, "templateName must not be null");
        return entityCache.computeIfAbsent(templateName, this::loadEntity);
    }

    /**
     * Invalida todas las entradas del cache para el template indicado.
     * El próximo acceso recargará la entidad desde DB y re-descargará el binario desde GCS.
     */
    public void invalidate(String templateName) {
        Objects.requireNonNull(templateName, "templateName must not be null");
        entityCache.remove(templateName);
        compiledCache.remove(templateName);
        docxBytesCache.remove(templateName);
        log.info("Cache invalidated for template: {}", templateName);
    }

    private DocumentTemplate loadEntity(String templateName) {
        return templateRepository
                .findByNameAndStatus(templateName, DocumentTemplateStatus.ACTIVE)
                .orElseThrow(() -> new TemplateNotFoundException(templateName));
    }
}
