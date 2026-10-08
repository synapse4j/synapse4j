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
    void encodeSchemaDemandsWrittenProperties() {
        assertEquals(List.of("count", "optional", "plain"),
                SchemaFixtures.required(generate(Both.class, SchemaFixtures.MAPPER)));
    }

    @Test
    void omittingValuesLeavesPrimitiveRequired() {
        // A primitive can be neither null nor empty, so the property is still always written out.
        assertEquals(List.of("count"),
                SchemaFixtures.required(generate(Both.class, omitting(JsonInclude.Include.NON_NULL))));
        assertEquals(List.of("count"),
                SchemaFixtures.required(generate(Both.class, omitting(JsonInclude.Include.NON_ABSENT))));
        assertEquals(List.of("count"),
                SchemaFixtures.required(generate(Both.class, omitting(JsonInclude.Include.NON_EMPTY))));
    }

    @Test
    void omittingDefaultsAlsoOmitsPrimitive() {
        assertEquals(List.of(),
                SchemaFixtures.required(generate(Both.class, omitting(JsonInclude.Include.NON_DEFAULT))));
    }

    @Test
    void propertyInclusionBeatsMapperDefault() {
        // The mapper writes everything by default, but the annotation omits nulls on one property, so
        // the schema must not demand what writing leaves out.
        assertEquals(List.of("always"),
                SchemaFixtures.required(generate(PerProperty.class, SchemaFixtures.MAPPER)));
    }

    @Test
    void classInclusionKeepsPrimitiveOnly() {
        // The setting is the class's, and it omits the one property writing can leave out.
        assertEquals(List.of("count"),
                SchemaFixtures.required(generate(OmittingNulls.class, SchemaFixtures.MAPPER)));
    }

    @Test
    void useDefaultsFallsToMapper() {
        // USE_DEFAULTS names no inclusion of its own, so the mapper's answer is the one that stands.
        assertEquals(List.of("count"),
                SchemaFixtures.required(generate(Deferring.class, omitting(JsonInclude.Include.NON_NULL))));
    }

    /** A record whose first property carries its own inclusion and whose second carries none. */
    record PerProperty(@JsonInclude(JsonInclude.Include.NON_NULL) String maybeNull, String always) {
    }

    /** A class that omits nulls for every property it carries. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record OmittingNulls(String maybeNull, int count) {
    }

    /** A property that asks for the mapper's answer rather than naming an inclusion of its own. */
    record Deferring(@JsonInclude(JsonInclude.Include.USE_DEFAULTS) String maybeNull, int count) {
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
