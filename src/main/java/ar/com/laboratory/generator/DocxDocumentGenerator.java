package ar.com.laboratory.generator;

import ar.com.laboratory.exception.DocumentGenerationException;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Genera documentos DOCX a partir de un template binario (.docx) almacenado en GCS.
 *
 * <p>El mecanismo de sustitución busca marcadores con la sintaxis {@code {{variable}}}
 * — idéntica a Mustache, lo que mantiene consistencia con los templates HTML/PDF del
 * mismo servicio. La resolución de marcadores es plana: no se soportan iteraciones
 * ni secciones condicionales; esas necesidades deben cubrirse generando múltiples
 * filas de tabla desde el código del caller antes de invocar este componente.
 *
 * <p>Estrategia de reemplazo de runs:
 * Word a veces fragmenta un marcador en varios {@code <w:r>} (runs) adyacentes
 * por razones internas (spell-check, cambio de idioma, etc.). Para manejar esto,
 * el generador concatena el texto de todos los runs de un párrafo, aplica los
 * reemplazos sobre el texto completo y luego escribe el resultado en el primer run
 * que contiene texto, limpiando el resto. El formateo a nivel de párrafo
 * (fuente, tamaño, negrita, alineación) se preserva; el formateo intra-run de los
 * caracteres del marcador se pierde, lo cual es el comportamiento esperado.
 */
@Slf4j
@Component
public class DocxDocumentGenerator {

    private static final Pattern PLACEHOLDER_PATTERN =
            Pattern.compile("\\{\\{([^}]+)\\}\\}");

    /**
     * Genera un documento DOCX a partir del template binario y el mapa de variables.
     *
     * @param templateBytes bytes del archivo .docx template (no se modifica)
     * @param fields        mapa plano de nombre de variable → valor a sustituir
     * @return bytes del documento .docx generado
     * @throws DocumentGenerationException si falla la apertura, el procesamiento o la escritura
     */
    public byte[] generate(byte[] templateBytes, Map<String, Object> fields) {
        Objects.requireNonNull(templateBytes, "templateBytes must not be null");
        Objects.requireNonNull(fields, "fields must not be null");

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(templateBytes));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            replaceParagraphs(document.getParagraphs(), fields);
            replaceTables(document.getTables(), fields);
            replaceHeadersAndFooters(document, fields);

            document.write(out);
            byte[] result = out.toByteArray();
            log.debug("DOCX generated via Apache POI: size={} bytes", result.length);
            return result;

        } catch (DocumentGenerationException e) {
            throw e;
        } catch (Exception e) {
            throw new DocumentGenerationException(
                    "Failed to generate DOCX document: " + e.getMessage(), e);
        }
    }

    // ── Traversal ─────────────────────────────────────────────────────────────

    private void replaceParagraphs(List<XWPFParagraph> paragraphs, Map<String, Object> fields) {
        for (XWPFParagraph paragraph : paragraphs) {
            replaceParagraph(paragraph, fields);
        }
    }

    private void replaceTables(List<XWPFTable> tables, Map<String, Object> fields) {
        for (XWPFTable table : tables) {
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    replaceParagraphs(cell.getParagraphs(), fields);
                    // Soporte para tablas anidadas
                    replaceTables(cell.getTables(), fields);
                }
            }
        }
    }

    private void replaceHeadersAndFooters(XWPFDocument document, Map<String, Object> fields) {
        for (XWPFHeader header : document.getHeaderList()) {
            replaceParagraphs(header.getParagraphs(), fields);
        }
        for (XWPFFooter footer : document.getFooterList()) {
            replaceParagraphs(footer.getParagraphs(), fields);
        }
    }

    // ── Reemplazo a nivel de párrafo ──────────────────────────────────────────

    /**
     * Aplica los reemplazos de marcadores en un párrafo.
     *
     * <p>Algoritmo:
     * <ol>
     *   <li>Concatena el texto de todos los runs del párrafo.</li>
     *   <li>Si no hay marcadores {@code {{...}}}, sale inmediatamente (fast path).</li>
     *   <li>Aplica todos los reemplazos sobre el texto completo.</li>
     *   <li>Escribe el texto resultante en el <em>primer</em> run con texto,
     *       y vacía el texto de los runs restantes.</li>
     * </ol>
     */
    private void replaceParagraph(XWPFParagraph paragraph, Map<String, Object> fields) {
        List<XWPFRun> runs = paragraph.getRuns();
        if (runs.isEmpty()) return;

        // Paso 1: concatenar texto de todos los runs
        StringBuilder sb = new StringBuilder();
        for (XWPFRun run : runs) {
            String text = run.getText(0);
            if (text != null) sb.append(text);
        }

        String fullText = sb.toString();

        // Fast path: sin marcadores, no hay nada que reemplazar
        if (!PLACEHOLDER_PATTERN.matcher(fullText).find()) return;

        // Paso 2: aplicar reemplazos
        String replaced = applyReplacements(fullText, fields);

        // Paso 3: escribir en el primer run con texto; limpiar el resto
        boolean firstWritten = false;
        for (XWPFRun run : runs) {
            if (run.getText(0) == null) continue;  // run sin texto (sólo formato), no tocar

            if (!firstWritten) {
                run.setText(replaced, 0);
                firstWritten = true;
            } else {
                run.setText("", 0);
            }
        }
    }

    // ── Sustitución de marcadores ─────────────────────────────────────────────

    /**
     * Sustituye todos los marcadores {@code {{variable}}} del texto por el valor
     * correspondiente en {@code fields}. Si la clave no existe en el mapa, el
     * marcador se elimina (reemplaza por cadena vacía).
     */
    private String applyReplacements(String text, Map<String, Object> fields) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String key         = matcher.group(1).trim();
            Object value       = fields.get(key);
            String replacement = (value != null) ? value.toString() : "";
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
