package com.shipyard.tracker.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;

/**
 * Jackson 3 turned on {@code FAIL_ON_NULL_FOR_PRIMITIVES} by default: a request body that leaves out a primitive
 * field (for example {@code BulkRequest.skipErrors}, a plain {@code boolean}) now fails to deserialize instead of
 * quietly getting the type's default value, as Jackson 2 did. Restoring the old behavior here is simpler than
 * boxing every such field just to tolerate a missing one.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public JsonMapperBuilderCustomizer missingPrimitivesGetDefaultsInsteadOfFailing() {
        return builder -> builder.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }
}
