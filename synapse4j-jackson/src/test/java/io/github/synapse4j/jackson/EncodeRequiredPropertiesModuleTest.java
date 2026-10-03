package io.github.synapse4j.jackson;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.synapse4j.jackson.SchemaFixtures.Both;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins what {@link EncodeRequiredPropertiesModule} decides on its own, applied to a plain victools
 * configuration.
 */
class EncodeRequiredPropertiesModuleTest {

    @Test
    void theEncodeSchemaDemandsEveryPropertyWhenWritingProducesEveryProperty() {
        assertEquals(List.of("count", "optional", "plain"),
                SchemaFixtures.required(generate(Both.class, SchemaFixtures.MAPPER)));
    }

    @Test
    void aSettingThatOmitsValuesLeavesAPrimitiveDemanded() {
        // A primitive can be neither null nor empty, so the property is still always written out.
        assertEquals(List.of("count"),
                SchemaFixtures.required(generate(Both.class, omitting(JsonInclude.Include.NON_NULL))));
        assertEquals(List.of("count"),
                SchemaFixtures.required(generate(Both.class, omitting(JsonInclude.Include.NON_ABSENT))));
        assertEquals(List.of("count"),
                SchemaFixtures.required(generate(Both.class, omitting(JsonInclude.Include.NON_EMPTY))));
    }

    @Test
    void aSettingThatOmitsDefaultsTakesAPrimitiveWithIt() {
        assertEquals(List.of(),
                SchemaFixtures.required(generate(Both.class, omitting(JsonInclude.Include.NON_DEFAULT))));
    }

    private static JsonMapper omitting(JsonInclude.Include inclusion) {
        return JsonMapper.builder()
                .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(inclusion))
                .build();
    }

    private static JsonNode generate(Class<?> type, JsonMapper jsonMapper) {
        return SchemaFixtures.generator(jsonMapper, new EncodeRequiredPropertiesModule(jsonMapper))
                .generateSchema(type);
    }

}
