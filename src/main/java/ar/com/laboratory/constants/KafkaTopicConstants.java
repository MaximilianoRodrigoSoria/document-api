package ar.com.laboratory.constants;

public final class KafkaTopicConstants {

    private KafkaTopicConstants() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** Topic de entrada: publicado por payment-loyalty, consumido por este servicio. */
    public static final String LOYALTY_DOCUMENT_GENERATION_REQUEST = "document.generation.requested";

    /** Topic de salida: publicado por este servicio al terminar la generación del PDF. */
    public static final String LOYALTY_DOCUMENT_GENERATION_COMPLETED = "document.generation.completed";

    /** Consumer group del servicio. */
    public static final String CONSUMER_GROUP_ID = "document-generator-api";
}
