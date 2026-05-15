package ar.com.laboratory.signer;

import ar.com.laboratory.config.SigningProperties;
import ar.com.laboratory.exception.DocumentGenerationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.CMSTypedData;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

/**
 * Firma criptográfica de documentos PDF mediante PKCS#7 detached (PAdES-B-B).
 *
 * <p>Algoritmo de firma: SHA-256 with RSA. El certificado y la clave privada se
 * cargan desde un keystore PKCS#12 ({@code .p12}) cuya ruta y contraseña se
 * configuran en {@link SigningProperties}.
 *
 * <p>El proceso utiliza archivos temporales para garantizar compatibilidad con la API
 * de guardado incremental de PDFBox, que requiere acceso aleatorio al documento
 * original. Los temporales se eliminan en el bloque {@code finally} de cada llamada.
 *
 * <p>Solo activo cuando {@code signing.enabled=true}. De lo contrario, Spring inyecta
 * {@link NoopDocumentSigner} automáticamente por la condición
 * {@link ConditionalOnProperty}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "signing.enabled", havingValue = "true")
public class PdfCryptoSigner implements DocumentSigner {

    private final SigningProperties signingProperties;

    @PostConstruct
    public void init() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
            log.info("BouncyCastle security provider registered");
        }
        log.info("PdfCryptoSigner active: signer='{}', alias='{}'",
                signingProperties.getSignerName(), signingProperties.getAlias());
    }

    /**
     * Firma el PDF y retorna el PDF con la firma PKCS#7 embebida de forma incremental.
     * La firma es verificable en Adobe Reader, Foxit, Chrome y cualquier validador PDF.
     *
     * @param docBytes   bytes del PDF a firmar
     * @param documentId identificador del documento (para logging y trazabilidad)
     * @return bytes del PDF firmado (original + update incremental con la firma)
     * @throws DocumentGenerationException si falla la carga del keystore, la generación
     *         de la firma o la escritura del PDF
     */
    @Override
    public byte[] sign(byte[] docBytes, String documentId) {
        Path tempIn  = null;
        Path tempOut = null;
        try {
            tempIn  = Files.createTempFile("pdf-sign-in-",  ".pdf");
            tempOut = Files.createTempFile("pdf-sign-out-", ".pdf");
            Files.write(tempIn, docBytes);

            KeyStore keyStore = loadKeyStore();

            try (PDDocument document = Loader.loadPDF(tempIn.toFile())) {
                PDSignature signature = buildSignature(documentId);

                SignatureOptions options = new SignatureOptions();
                options.setPreferredSignatureSize(SignatureOptions.DEFAULT_SIGNATURE_SIZE * 2);

                document.addSignature(signature, content -> signContent(content, keyStore), options);

                try (FileOutputStream fos = new FileOutputStream(tempOut.toFile())) {
                    document.saveIncremental(fos);
                }
            }

            byte[] signed = Files.readAllBytes(tempOut);
            log.debug("[pdf.signed] PDF signed successfully: documentId={}, original={} bytes, signed={} bytes",
                    documentId, docBytes.length, signed.length);
            return signed;

        } catch (DocumentGenerationException e) {
            throw e;
        } catch (Exception e) {
            throw new DocumentGenerationException(
                    "PDF signing failed for documentId='" + documentId + "': " + e.getMessage(), e);
        } finally {
            deleteSilently(tempIn);
            deleteSilently(tempOut);
        }
    }

    // ── Construcción de la firma ──────────────────────────────────────────────

    private PDSignature buildSignature(String documentId) {
        PDSignature sig = new PDSignature();
        sig.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
        sig.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
        sig.setName(signingProperties.getSignerName());
        sig.setReason(signingProperties.getReason());
        if (!signingProperties.getLocation().isBlank()) {
            sig.setLocation(signingProperties.getLocation());
        }
        sig.setSignDate(Calendar.getInstance());
        return sig;
    }

    /**
     * Implementa {@code SignatureInterface}: toma el contenido a firmar (stream del PDF)
     * y produce el bloque PKCS#7 CMS detached que PDFBox embebe en el documento.
     */
    private byte[] signContent(InputStream content, KeyStore keyStore) throws IOException {
        try {
            String alias    = signingProperties.getAlias();
            char[] password = signingProperties.getKeystorePassword().toCharArray();

            PrivateKey   privateKey = (PrivateKey) keyStore.getKey(alias, password);
            Certificate[] chain    = keyStore.getCertificateChain(alias);

            if (privateKey == null) {
                throw new DocumentGenerationException(
                        "Private key not found in keystore for alias: " + alias);
            }

            List<Certificate> certList = Arrays.asList(chain);
            JcaCertStore certStore = new JcaCertStore(certList);

            CMSSignedDataGenerator generator = new CMSSignedDataGenerator();
            X509Certificate cert = (X509Certificate) chain[0];

            ContentSigner sha256Signer = new JcaContentSignerBuilder("SHA256WithRSA")
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .build(privateKey);

            generator.addSignerInfoGenerator(
                    new JcaSignerInfoGeneratorBuilder(
                            new JcaDigestCalculatorProviderBuilder()
                                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                                    .build())
                            .build(sha256Signer, cert));

            generator.addCertificates(certStore);

            byte[] contentBytes  = content.readAllBytes();
            CMSTypedData cmsData = new CMSProcessableByteArray(contentBytes);
            CMSSignedData signedData = generator.generate(cmsData, false);
            return signedData.getEncoded();

        } catch (DocumentGenerationException e) {
            throw new IOException(e);
        } catch (Exception e) {
            throw new IOException("PKCS#7 CMS signature generation failed: " + e.getMessage(), e);
        }
    }

    // ── Carga del keystore ────────────────────────────────────────────────────

    private KeyStore loadKeyStore() throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        String path = signingProperties.getKeystorePath();
        char[] pass = signingProperties.getKeystorePassword().toCharArray();
        try (InputStream is = resolveInputStream(path)) {
            ks.load(is, pass);
        }
        return ks;
    }

    /**
     * Resuelve el stream del keystore soportando tanto rutas absolutas como
     * el prefijo {@code classpath:} (útil para tests y entornos containerizados
     * donde el .p12 va embebido en el classpath).
     */
    private InputStream resolveInputStream(String path) throws IOException {
        if (path.startsWith("classpath:")) {
            String resource = path.substring("classpath:".length());
            InputStream is = getClass().getResourceAsStream(resource);
            if (is == null) {
                throw new IOException("Keystore not found in classpath: " + resource);
            }
            return is;
        }
        return new FileInputStream(path);
    }

    // ── Utilidades ────────────────────────────────────────────────────────────

    private void deleteSilently(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // Non-critical: the OS will clean up temp files eventually
            }
        }
    }
}
