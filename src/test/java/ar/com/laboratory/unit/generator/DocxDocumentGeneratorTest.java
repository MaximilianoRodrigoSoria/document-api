package ar.com.laboratory.unit.generator;

import ar.com.laboratory.exception.DocumentGenerationException;
import ar.com.laboratory.generator.DocxDocumentGenerator;
import ar.com.laboratory.unit.base.BaseUnitTest;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DocxDocumentGenerator")
class DocxDocumentGeneratorTest extends BaseUnitTest {

    private DocxDocumentGenerator sut;

    @BeforeEach
    void setUp() {
        sut = new DocxDocumentGenerator();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Crea un documento DOCX en memoria con un único párrafo que contiene
     * el texto indicado en un único run.
     */
    private byte[] createDocxWithParagraph(String paragraphText) throws Exception {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph para = doc.createParagraph();
            XWPFRun run = para.createRun();
            run.setText(paragraphText);
            doc.write(out);
            return out.toByteArray();
        }
    }

    /**
     * Crea un DOCX con el texto del marcador repartido en múltiples runs
     * (simula el fenómeno de run-splitting que hace Word).
     */
    private byte[] createDocxWithSplitRuns(String part1, String part2, String part3)
            throws Exception {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph para = doc.createParagraph();
            para.createRun().setText(part1);
            para.createRun().setText(part2);
            para.createRun().setText(part3);
            doc.write(out);
            return out.toByteArray();
        }
    }

    /** Extrae el texto completo del primer párrafo del documento DOCX dado. */
    private String readFirstParagraph(byte[] docBytes) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docBytes))) {
            return doc.getParagraphs().get(0).getText();
        }
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Reemplazo simple en un único run")
    class SimpleReplacement {

        @Test
        @DisplayName("reemplaza un marcador por su valor del mapa")
        void reemplazaMarcadorSimple() throws Exception {
            byte[] template = createDocxWithParagraph("Hola, {{nombre}}!");
            Map<String, Object> fields = Map.of("nombre", "Maximiliano");

            byte[] result = sut.generate(template, fields);

            assertThat(readFirstParagraph(result)).isEqualTo("Hola, Maximiliano!");
        }

        @Test
        @DisplayName("reemplaza múltiples marcadores distintos en el mismo párrafo")
        void reemplazaMultiplesMarcadores() throws Exception {
            byte[] template = createDocxWithParagraph("{{nombre}} — versión {{version}}");
            Map<String, Object> fields = Map.of("nombre", "Reporte Q1", "version", "2.0.0");

            byte[] result = sut.generate(template, fields);

            assertThat(readFirstParagraph(result)).isEqualTo("Reporte Q1 — versión 2.0.0");
        }

        @Test
        @DisplayName("el mismo marcador apareciendo dos veces se reemplaza en ambas ocurrencias")
        void reemplazaMarcadorRepetido() throws Exception {
            byte[] template = createDocxWithParagraph("{{empresa}} — {{empresa}}");
            Map<String, Object> fields = Map.of("empresa", "Max Lab");

            byte[] result = sut.generate(template, fields);

            assertThat(readFirstParagraph(result)).isEqualTo("Max Lab — Max Lab");
        }

        @Test
        @DisplayName("marcador con clave ausente en el mapa produce cadena vacía")
        void marcadorAusenteProduceCadenaVacia() throws Exception {
            byte[] template = createDocxWithParagraph("Valor: {{inexistente}}");
            Map<String, Object> fields = new HashMap<>();

            byte[] result = sut.generate(template, fields);

            assertThat(readFirstParagraph(result)).isEqualTo("Valor: ");
        }

        @Test
        @DisplayName("valor null en el mapa produce cadena vacía")
        void valorNullProduceCadenaVacia() throws Exception {
            byte[] template = createDocxWithParagraph("Campo: {{campo}}");
            Map<String, Object> fields = new HashMap<>();
            fields.put("campo", null);

            byte[] result = sut.generate(template, fields);

            assertThat(readFirstParagraph(result)).isEqualTo("Campo: ");
        }

        @Test
        @DisplayName("párrafo sin marcadores no se modifica")
        void parrafoSinMarcadoresNoSeModi() throws Exception {
            byte[] template = createDocxWithParagraph("Texto estático sin variables");
            Map<String, Object> fields = Map.of("cualquiera", "valor");

            byte[] result = sut.generate(template, fields);

            assertThat(readFirstParagraph(result)).isEqualTo("Texto estático sin variables");
        }
    }

    @Nested
    @DisplayName("Run-splitting (marcador fragmentado en varios runs)")
    class SplitRunReplacement {

        @Test
        @DisplayName("reemplaza marcador {{variable}} fragmentado en tres runs")
        void reemplazaMarcadorSplitEnTresRuns() throws Exception {
            // Simula que Word guardó "{{" + "nombre" + "}}" en runs separados
            byte[] template = createDocxWithSplitRuns("{{", "nombre", "}}");
            Map<String, Object> fields = Map.of("nombre", "Ana García");

            byte[] result = sut.generate(template, fields);

            assertThat(readFirstParagraph(result)).isEqualTo("Ana García");
        }

        @Test
        @DisplayName("reemplaza marcador rodeado de texto con runs fragmentados")
        void reemplazaConTextoAlrededor() throws Exception {
            byte[] template = createDocxWithSplitRuns("Estimado ", "{{cliente}}", ",");
            Map<String, Object> fields = Map.of("cliente", "Dr. Rodríguez");

            byte[] result = sut.generate(template, fields);

            assertThat(readFirstParagraph(result)).isEqualTo("Estimado Dr. Rodríguez,");
        }
    }

    @Nested
    @DisplayName("Generación del documento de salida")
    class OutputDocument {

        @Test
        @DisplayName("el resultado es un DOCX válido (contiene firma ZIP PK)")
        void resultadoEsDocxValido() throws Exception {
            byte[] template = createDocxWithParagraph("{{titulo}}");
            byte[] result = sut.generate(template, Map.of("titulo", "Test"));

            // Los archivos ZIP (y DOCX) comienzan con la firma "PK" (0x50 0x4B)
            assertThat(result).hasSizeGreaterThan(0);
            assertThat(result[0]).isEqualTo((byte) 0x50);
            assertThat(result[1]).isEqualTo((byte) 0x4B);
        }

        @Test
        @DisplayName("el documento resultante contiene el texto reemplazado")
        void documentoResultanteContieneTextoReemplazado() throws Exception {
            byte[] template = createDocxWithParagraph("Documento: {{titulo}} — Versión {{version}}");
            Map<String, Object> fields = Map.of("titulo", "Manual Técnico", "version", "3.1.0");

            byte[] result = sut.generate(template, fields);

            try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(result))) {
                String body = doc.getParagraphs().stream()
                        .map(XWPFParagraph::getText)
                        .reduce("", String::concat);
                assertThat(body).contains("Manual Técnico");
                assertThat(body).contains("3.1.0");
                assertThat(body).doesNotContain("{{titulo}}");
                assertThat(body).doesNotContain("{{version}}");
            }
        }
    }

    @Nested
    @DisplayName("Validaciones de entrada")
    class InputValidation {

        @Test
        @DisplayName("lanza NullPointerException cuando templateBytes es null")
        void lanzaNPECuandoTemplateBytesEsNull() {
            assertThatThrownBy(() -> sut.generate(null, Map.of()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("templateBytes");
        }

        @Test
        @DisplayName("lanza NullPointerException cuando fields es null")
        void lanzaNPECuandoFieldsEsNull() throws Exception {
            byte[] template = createDocxWithParagraph("{{x}}");
            assertThatThrownBy(() -> sut.generate(template, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("fields");
        }

        @Test
        @DisplayName("lanza DocumentGenerationException para bytes que no son un DOCX válido")
        void lanzaExcepcionParaBytesInvalidos() {
            byte[] notADocx = "esto no es un docx".getBytes();
            assertThatThrownBy(() -> sut.generate(notADocx, Map.of()))
                    .isInstanceOf(DocumentGenerationException.class)
                    .hasMessageContaining("Failed to generate DOCX");
        }
    }
}
