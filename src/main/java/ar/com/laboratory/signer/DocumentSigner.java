package ar.com.laboratory.signer;

/**
 * Contrato para la firma de documentos generados.
 *
 * <p>Dos implementaciones disponibles:
 * <ul>
 *   <li>{@code NoopDocumentSigner} — pass-through, activo cuando {@code signing.enabled=false}
 *       (default). Usado en DOCX y en tests.</li>
 *   <li>{@code PdfCryptoSigner}    — firma criptográfica PKCS#7/PAdES sobre PDF,
 *       activo cuando {@code signing.enabled=true}.</li>
 * </ul>
 *
 * <p>El scheduler llama a {@link #sign} solo sobre bytes PDF, antes del upload a GCS.
 */
public interface DocumentSigner {

    /**
     * Firma los bytes del documento y retorna el documento firmado.
     *
     * @param docBytes   bytes del documento original (no se modifica)
     * @param documentId identificador único del documento (usado para logging y trazabilidad)
     * @return bytes del documento firmado; puede ser idéntico al input (Noop)
     */
    byte[] sign(byte[] docBytes, String documentId);
}
