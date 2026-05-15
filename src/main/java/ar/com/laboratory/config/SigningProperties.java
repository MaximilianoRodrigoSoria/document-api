package ar.com.laboratory.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Propiedades de configuración para la firma criptográfica de documentos PDF.
 *
 * <p>La firma se aplica únicamente a documentos PDF cuando {@link #enabled} es {@code true}.
 * Los documentos DOCX no son firmados y el campo {@code enabled=false} activa el
 * {@code NoopDocumentSigner} que retorna los bytes sin modificación.
 *
 * <p>El keystore PKCS#12 ({@code .p12}) puede referenciarse de dos formas:
 * <ul>
 *   <li>Ruta absoluta al archivo: {@code /etc/secrets/signing-keystore.p12}</li>
 *   <li>Prefijo classpath para tests: {@code classpath:/signing/test-keystore.p12}</li>
 * </ul>
 *
 * <p>En producción, las credenciales del keystore deben inyectarse mediante variables
 * de entorno ({@code SIGNING_KS_PATH}, {@code SIGNING_KS_PASS}) y no hardcodearse
 * en el yml de producción.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "signing")
public class SigningProperties {

    /** Habilita la firma criptográfica. Default {@code false} (NoopSigner activo). */
    private boolean enabled = false;

    /** Ruta al archivo PKCS#12. Soporta prefijo {@code classpath:} para tests. */
    private String keystorePath = "config/signing-keystore.p12";

    /** Contraseña del keystore y de la clave privada. */
    private String keystorePassword = "changeit";

    /** Alias de la entrada en el keystore. */
    private String alias = "document-signer";

    /** Nombre del firmante embebido en la firma del PDF. */
    private String signerName = "Max Lab Document Signer";

    /** Razón de firma visible en el panel de firma del lector PDF. */
    private String reason = "Documento generado automáticamente";

    /** Localización opcional visible en el panel de firma. */
    private String location = "";
}
