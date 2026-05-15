package ar.com.laboratory.cache;

import ar.com.laboratory.constants.LogConstants;
import ar.com.laboratory.exception.TemplateNotFoundException;
import ar.com.laboratory.model.entity.DocumentTemplate;
import ar.com.laboratory.model.enums.DocumentTemplateStatus;
import ar.com.laboratory.repository.DocumentTemplateRepository;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class TemplateCache {

    private final DocumentTemplateRepository templateRepository;

    private static final MustacheFactory MUSTACHE_FACTORY = new DefaultMustacheFactory();

    private final Map<String, DocumentTemplate> entityCache   = new ConcurrentHashMap<>();
    private final Map<String, Mustache>         compiledCache = new ConcurrentHashMap<>();

    public Mustache getCompiledTemplate(String templateName) {
        Objects.requireNonNull(templateName, "templateName must not be null");
        return compiledCache.computeIfAbsent(templateName, name -> {
            DocumentTemplate entity = entityCache.computeIfAbsent(name, this::loadEntity);
            String html = entity.getContent();
            Mustache compiled = MUSTACHE_FACTORY.compile(new StringReader(html), name);
            log.debug("[{}] Template compiled and cached: template={}, size={} chars",
                    LogConstants.LOG_CACHE_MISS, name, html.length());
            return compiled;
        });
    }

    public DocumentTemplate getEntity(String templateName) {
        Objects.requireNonNull(templateName, "templateName must not be null");
        return entityCache.computeIfAbsent(templateName, this::loadEntity);
    }

    public void invalidate(String templateName) {
        Objects.requireNonNull(templateName, "templateName must not be null");
        entityCache.remove(templateName);
        compiledCache.remove(templateName);
        log.info("Cache invalidated for template: {}", templateName);
    }

    private DocumentTemplate loadEntity(String templateName) {
        return templateRepository
                .findByNameAndStatus(templateName, DocumentTemplateStatus.ACTIVE)
                .orElseThrow(() -> new TemplateNotFoundException(templateName));
    }
}
