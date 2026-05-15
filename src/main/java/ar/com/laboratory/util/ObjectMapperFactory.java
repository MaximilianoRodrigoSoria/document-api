package ar.com.laboratory.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public class ObjectMapperFactory {
    private static ObjectMapper objectMapper;

    private ObjectMapperFactory() {
    }

    public static ObjectMapper getInstance() {
        if (objectMapper == null) {
            objectMapper = new ObjectMapper();
            objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
            objectMapper.registerModule(new JavaTimeModule());
        }
        return objectMapper;
    }
}
