package io.github.synapse4j.jackson;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.synapse4j.jackson.SchemaFixtures.Both;
import io.github.synapse4j.jackson.SchemaFixtures.Settable;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins what {@link DecodeRequiredPropertiesModule} decides on its own, applied to a plain victools
 * configuration.
 *
 * <p>
 * The configuration deliberately keeps {@code Option.FLATTENED_OPTIONALS} — which {@code PLAIN_JSON}
 * ships and {@link FlattenedOptionalModule} turns off — so that a rule reading the effective type instead
 * of the declared one fails here.
 */
class DecodeRequiredPropertiesModuleTest {

    @Test
    void theDecodeSchemaRequiresEveryPropertyExceptTheOptionalOnes() {
        assertEquals(List.of("count", "plain"), SchemaFixtures.required(generate(Both.class, SchemaFixtures.MAPPER)));
        assertEquals(List.of("number", "text"),
                SchemaFixtures.required(generate(Settable.class, SchemaFixtures.MAPPER)));
    }

    @Test
    void aMapperRefusingMissingCreatorsHasEveryPropertyRequired() {
        JsonMapper refusing = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .build();

        assertEquals(List.of("count", "optional", "plain"), SchemaFixtures.required(generate(Both.class, refusing)));
    }

    private static JsonNode generate(Class<?> type, JsonMapper jsonMapper) {
        return SchemaFixtures.generator(jsonMapper, new DecodeRequiredPropertiesModule(jsonMapper))
                .generateSchema(type);
    }

}
