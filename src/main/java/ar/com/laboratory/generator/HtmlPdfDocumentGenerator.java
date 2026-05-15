package ar.com.laboratory.generator;

import ar.com.laboratory.exception.DocumentGenerationException;
import com.github.mustachejava.Mustache;
import com.lowagie.text.DocumentException;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfStamper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.xhtmlrenderer.pdf.ITextRenderer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Component
public class HtmlPdfDocumentGenerator {

    public byte[] generate(Mustache compiledTemplate, Map<String, Object> fields) {
        Objects.requireNonNull(compiledTemplate, "compiledTemplate must not be null");
        Objects.requireNonNull(fields, "fields must not be null");
        String html = renderHtml(compiledTemplate, fields);
        return convertToPdf(html);
    }

    private String renderHtml(Mustache template, Map<String, Object> fields) {
        try {
            StringWriter writer = new StringWriter();
            template.execute(writer, fields).flush();
            String html = writer.toString();
            log.debug("Mustache rendered: outputLength={} chars", html.length());
            return html;
        } catch (Exception e) {
            throw new DocumentGenerationException("Failed to render Mustache template: " + e.getMessage(), e);
        }
    }

    private byte[] convertToPdf(String html) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ITextRenderer renderer = new ITextRenderer();
            renderer.setDocumentFromString(html);
            renderer.layout();
            renderer.createPDF(baos);
            byte[] raw = baos.toByteArray();
            log.debug("PDF generated via Flying Saucer: size={} bytes", raw.length);
            return compressPdf(raw);
        } catch (DocumentException e) {
            throw new DocumentGenerationException("Flying Saucer PDF conversion failed: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new DocumentGenerationException("Unexpected error during HTML-to-PDF conversion", e);
        }
    }

    /**
     * Re-escribe el PDF habilitando full compression (PDF 1.5 object streams).
     * Reduce el tamaño entre un 20–40% sin afectar el contenido visual.
     * Si la compresión falla por cualquier motivo, retorna el PDF original sin interrumpir el flujo.
     */
    private byte[] compressPdf(byte[] raw) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(raw);
             ByteArrayOutputStream compressedBaos = new ByteArrayOutputStream()) {
            PdfReader reader = new PdfReader(bais);
            PdfStamper stamper = new PdfStamper(reader, compressedBaos);
            stamper.setFullCompression();
            stamper.close();
            reader.close();
            byte[] compressed = compressedBaos.toByteArray();
            log.debug("PDF compressed: {}B → {}B ({}% reduction)",
                    raw.length, compressed.length,
                    (int) (100 - (compressed.length * 100.0 / raw.length)));
            return compressed;
        } catch (Exception e) {
            log.warn("PDF compression failed, returning original PDF: {}", e.getMessage());
            return raw;
        }
    }
}
