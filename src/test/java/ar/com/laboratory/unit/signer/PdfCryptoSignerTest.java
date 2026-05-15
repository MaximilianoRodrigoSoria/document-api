package ar.com.laboratory.unit.signer;

import ar.com.laboratory.config.SigningProperties;
import ar.com.laboratory.exception.DocumentGenerationException;
import ar.com.laboratory.signer.PdfCryptoSigner;
import ar.com.laboratory.unit.base.BaseUnitTest;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests unitarios para {@link PdfCryptoSigner}.
 *
 * <p>El test keystore ({@code /signing/test-keystore.p12}) está embebido en
 * {@code src/test/resources} y se referencia con el prefijo {@code classpath:}
 * para que funcione tanto en IDE como en Gradle sin rutas absolutas.
 *
 * <p>Alias: {@code test-signer} — Password: {@code testpass}
 */
@DisplayName("PdfCryptoSigner")
class PdfCryptoSignerTest extends BaseUnitTest {

    private static final String TEST_KEYSTORE = "classpath:/signing/test-keystore.p12";
    private static final String TEST_ALIAS    = "test-signer";
    private static final String TEST_PASSWORD = "testpass";
    private static final String TEST_SIGNER   = "Test Signer";
    private static final String TEST_REASON   = "Documento de prueba";

    private PdfCryptoSigner sut;

    @BeforeEach
    void setUp() {
        sut = buildSigner(TEST_ALIAS, TEST_SIGNER, TEST_REASON);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Crea un PDF mínimo válido en memoria usando PDFBox (una página A4 en blanco).
     */
    private byte[] createMinimalPdf() throws Exception {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage(PDRectangle.A4));
            doc.save(out);
            return out.toByteArray();
        }
    }

    /**
     * Construye e inicializa un {@code PdfCryptoSigner} con el keystore de test
     * y los parámetros indicados. Llama a {@code init()} manualmente para simular
     * el ciclo {@code @PostConstruct}.
     */
    private PdfCryptoSigner buildSigner(String alias, String signerName, String reason) {
        SigningProperties props = new SigningProperties();
        props.setEnabled(true);
        props.setKeystorePath(TEST_KEYSTORE);
        props.setKeystorePassword(TEST_PASSWORD);
        props.setAlias(alias);
        props.setSignerName(signerName);
        props.setReason(reason);
        props.setLocation("Buenos Aires");
        PdfCryptoSigner signer = new PdfCryptoSigner(props);
        signer.init();
        return signer;
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Firma exitosa del documento")
    class FirmaExitosa {

        @Test
        @DisplayName("retorna bytes no vacíos con tamaño mayor que el PDF original")
        void retornaBytesNoVacios() throws Exception {
            byte[] original = createMinimalPdf();

            byte[] signed = sut.sign(original, "doc-001");

            assertThat(signed).isNotEmpty();
            assertThat(signed.length).isGreaterThan(original.length);
        }

        @Test
        @DisplayName("el resultado comienza con la cabecera %%PDF")
        void resultadoEsUnPdfValido() throws Exception {
            byte[] signed = sut.sign(createMinimalPdf(), "doc-002");

            assertThat(new String(signed, 0, 4)).isEqualTo("%PDF");
        }

        @Test
        @DisplayName("el PDF firmado contiene exactamente una firma digital")
        void contieneUnaFirmaDigital() throws Exception {
            byte[] signed = sut.sign(createMinimalPdf(), "doc-003");

            try (PDDocument doc = Loader.loadPDF(signed)) {
                List<PDSignature> signatures = doc.getSignatureDictionaries();
                assertThat(signatures).hasSize(1);
            }
        }

        @Test
        @DisplayName("la firma embebida tiene el nombre del firmante configurado")
        void firmaTieneNombreCorrecto() throws Exception {
            byte[] signed = sut.sign(createMinimalPdf(), "doc-004");

            try (PDDocument doc = Loader.loadPDF(signed)) {
                PDSignature sig = doc.getSignatureDictionaries().get(0);
                assertThat(sig.getName()).isEqualTo(TEST_SIGNER);
            }
        }

        @Test
        @DisplayName("la firma embebida tiene la razón configurada")
        void firmaTieneRazonCorrecta() throws Exception {
            byte[] signed = sut.sign(createMinimalPdf(), "doc-005");

            try (PDDocument doc = Loader.loadPDF(signed)) {
                PDSignature sig = doc.getSignatureDictionaries().get(0);
                assertThat(sig.getReason()).isEqualTo(TEST_REASON);
            }
        }

        @Test
        @DisplayName("la firma usa el sub-filtro PKCS#7 detached (adbe.pkcs7.detached)")
        void firmaTieneSubfiltroPkcs7Detached() throws Exception {
            byte[] signed = sut.sign(createMinimalPdf(), "doc-006");

            try (PDDocument doc = Loader.loadPDF(signed)) {
                PDSignature sig = doc.getSignatureDictionaries().get(0);
                assertThat(sig.getSubFilter()).isEqualTo("adbe.pkcs7.detached");
            }
        }

        @Test
        @DisplayName("la firma tiene fecha de firma (SignDate) no nula")
        void firmaTieneFechaNoNula() throws Exception {
            byte[] signed = sut.sign(createMinimalPdf(), "doc-007");

            try (PDDocument doc = Loader.loadPDF(signed)) {
                PDSignature sig = doc.getSignatureDictionaries().get(0);
                assertThat(sig.getSignDate()).isNotNull();
            }
        }

        @Test
        @DisplayName("el contenido PKCS#7 tiene longitud mayor a cero")
        void contenidoPkcs7TieneLongitudPositiva() throws Exception {
            byte[] signed = sut.sign(createMinimalPdf(), "doc-008");

            try (PDDocument doc = Loader.loadPDF(signed)) {
                PDSignature sig = doc.getSignatureDictionaries().get(0);
                byte[] contents = sig.getContents(signed);
                assertThat(contents).isNotNull();
                assertThat(contents.length).isGreaterThan(0);
            }
        }
    }

    @Nested
    @DisplayName("Manejo de errores")
    class ManejoDeErrores {

        @Test
        @DisplayName("lanza DocumentGenerationException para bytes que no son un PDF válido")
        void lanzaExcepcionParaBytesInvalidos() {
            byte[] notAPdf = "esto no es un pdf".getBytes();

            assertThatThrownBy(() -> sut.sign(notAPdf, "doc-err-001"))
                    .isInstanceOf(DocumentGenerationException.class)
                    .hasMessageContaining("PDF signing failed");
        }

        @Test
        @DisplayName("el mensaje de la excepción incluye el documentId para trazabilidad")
        void excepcionIncluyeDocumentId() {
            byte[] notAPdf = "bytes inválidos".getBytes();
            String documentId = "doc-trazabilidad-xyz";

            assertThatThrownBy(() -> sut.sign(notAPdf, documentId))
                    .isInstanceOf(DocumentGenerationException.class)
                    .hasMessageContaining(documentId);
        }

        @Test
        @DisplayName("lanza DocumentGenerationException si el alias del keystore no existe")
        void lanzaExcepcionSiAliasNoExiste() throws Exception {
            PdfCryptoSigner signerConAliasErroneo = buildSigner("alias-inexistente", "X", "X");
            byte[] pdf = createMinimalPdf();

            assertThatThrownBy(() -> signerConAliasErroneo.sign(pdf, "doc-err-003"))
                    .isInstanceOf(DocumentGenerationException.class)
                    .hasMessageContaining("PDF signing failed");
        }
    }
}
