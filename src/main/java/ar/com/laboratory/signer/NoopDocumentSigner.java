package ar.com.laboratory.signer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Implementación pass-through de {@link DocumentSigner}.
 *
 * <p>Activa por defecto ({@code signing.enabled=false} o propiedad ausente).
 * Retorna los bytes del documento sin modificación. Es el signer usado para:
 * <ul>
 *   <li>Documentos DOCX (la firma criptográfica solo aplica a PDF).</li>
 *   <li>Entornos donde la firma no está habilitada (local sin config de keystore).</li>
 *   <li>Tests unitarios que no quieren levantar el contexto de firma.</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "signing.enabled", havingValue = "false", matchIfMissing = true)
public class NoopDocumentSigner implements DocumentSigner {

    @Override
    public byte[] sign(byte[] docBytes, String documentId) {
        log.debug("Signing disabled — returning document as-is: documentId={}, size={} bytes",
                documentId, docBytes.length);
        return docBytes;
    }
}
