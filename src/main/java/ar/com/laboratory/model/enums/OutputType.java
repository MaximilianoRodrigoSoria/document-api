package ar.com.laboratory.model.enums;

/**
 * Tipo de documento de salida que produce el generador para un template dado.
 *
 * <ul>
 *   <li>{@link #PDF}  – El template es HTML Mustache; se renderiza con Flying Saucer.</li>
 *   <li>{@link #DOCX} – El template es un archivo .docx almacenado en GCS;
 *       se procesa con Apache POI reemplazando marcadores {@code {{variable}}}.</li>
 * </ul>
 */
public enum OutputType {
    PDF,
    DOCX
}
