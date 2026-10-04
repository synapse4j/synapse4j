package io.github.synapse4j.jackson;

import static io.github.synapse4j.jackson.SchemaFixtures.LIST_OF_OPTIONAL_STRING;
import static io.github.synapse4j.jackson.SchemaFixtures.MAP_OF_OBJECT;
import static io.github.synapse4j.jackson.SchemaFixtures.MAP_OF_OPTIONAL_STRING;
import static io.github.synapse4j.jackson.SchemaFixtures.MAP_OF_STRING;
import static io.github.synapse4j.jackson.SchemaFixtures.OPTIONAL_STRING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.victools.jsonschema.generator.SchemaGenerator;

import io.github.synapse4j.jackson.SchemaFixtures.Nested;
import io.github.synapse4j.jackson.SchemaFixtures.Settable;
import io.github.synapse4j.json.JsonSchema;
import io.github.synapse4j.json.JsonSchemaBuilder;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins what {@link JacksonSchemaConfigBuilders} decides about a generated schema, through the codec that
 * hands one out: whatever the schema allows, binding accepts, and binding produces only what the schema
 * allows. Where a schema is stricter than the binder — the decode direction states what the types ask
 * for rather than everything a lenient binder tolerates — that is the contract, not a mistake.
 */
class JacksonSchemaConfigBuildersTest {

    /** Every kind of property a type can declare, so one schema shows how each is described. */
    record Kitchen(String text, int number, Integer boxed, Optional<String> optional, List<String> list,
            List<Optional<String>> optionals, Map<String, String> map, Map<String, Object> open,
            Object anything, Nested nested) {
    }

    /**
     * The kinds the kitchen does not carry: an enum, the other primitives, dates and a byte array.
     *
     * <p>
     * The enum is written twice — once alone and once in a list — so it is described through
     * {@code $defs} and a reference, the way a type used more than once is; the {@code byte[]} is the
     * base64 string the mapper writes. Both are pinned here rather than in a class of their own, since
     * what each is described as is the same kind of decision.
     */
    record Variety(Color color, List<Color> palette, boolean flag, double ratio, long count, BigDecimal amount,
            byte[] data, LocalDate date, LocalDateTime time) {
    }

    /** An enum, so the schema shows how one is described: a definition others reference. */
    enum Color {

        RED, GREEN, BLUE
    }

    /** A type that contains itself with nothing in the way, so the schema has to close the cycle. */
    record Link(String name, Link next) {
    }

    /**
     * One member asking, by annotation, for what the type alone would not demand — and one asking
     * for the opposite, which is not an annotation's to ask.
     */
    record Annotated(String plain, @JsonProperty(required = true) Optional<String> optional,
            @JsonProperty(required = false) String loosened) {
    }

    /** A document with every property present, the nullable one as null. */
    private static final String EVERYTHING = "{\"text\":\"x\",\"number\":1,\"boxed\":2,\"optional\":null,"
            + "\"list\":[\"a\"],\"optionals\":[null,\"a\"],\"map\":{\"k\":\"v\"},\"open\":{\"k\":1},"
            + "\"anything\":{},\"nested\":{\"value\":\"v\"}}";

    /** The same document with the one property the schema leaves out of {@code required}. */
    private static final String WITHOUT_OPTIONAL = "{\"text\":\"x\",\"number\":1,\"boxed\":2,"
            + "\"list\":[\"a\"],\"optionals\":[null,\"a\"],\"map\":{\"k\":\"v\"},\"open\":{\"k\":1},"
            + "\"anything\":{},\"nested\":{\"value\":\"v\"}}";

    private final JsonMapper jsonMapper = SchemaFixtures.MAPPER;

    private final JacksonJsonCodec codec = new JacksonJsonCodec(jsonMapper.rebuild());

    @Test
    void theDecodeSchemaRequiresEveryPropertyTheTypeDoesNotMakeOptional() {
        JsonSchema schema = codec.generateDecodeSchema(Kitchen.class);

        assertEquals(List.of("text", "number", "boxed", "list", "optionals", "map", "open", "anything",
                "nested"), List.copyOf(schema.getRequired()));
    }

    @Test
    void onlyAnAnnotationThatDemandsHasAnEffect() {
        JsonSchema schema = codec.generateDecodeSchema(Annotated.class);

        // optional is demanded by its annotation alone, the type making it optional; loosened is
        // demanded by its type alone, no annotation being able to take it out again — the checks are
        // one union, and none of them can veto another.
        assertEquals(List.of("plain", "optional", "loosened"), List.copyOf(schema.getRequired()));
    }

    @Test
    void theDecodeSchemaAllowsNullExactlyWhereTheTypeDoes() {
        JsonSchema schema = codec.generateDecodeSchema(Kitchen.class);

        assertTrue(allowsNull(schema, schema.getProperties().get("optional")));
        assertFalse(allowsNull(schema, schema.getProperties().get("text")));
        assertFalse(allowsNull(schema, schema.getProperties().get("boxed")));
        assertFalse(allowsNull(schema, schema.getProperties().get("optionals")));
        assertFalse(allowsNull(schema, schema.getProperties().get("map")));
    }

    @Test
    void optionalIsTreatedAlikeInEveryPosition() {
        JsonSchema root = codec.generateDecodeSchema(OPTIONAL_STRING);
        JsonSchema kitchen = codec.generateDecodeSchema(Kitchen.class);
        JsonSchema list = codec.generateDecodeSchema(LIST_OF_OPTIONAL_STRING);

        assertTrue(allowsNull(root, root));
        assertTrue(allowsNull(kitchen, kitchen.getProperties().get("optional")));
        assertTrue(allowsNull(list, list.getItems()));
        // A map's value schema travels in the open part rather than as a field, so that position is
        // pinned by what reading it does instead.
        assertEquals(Map.of("k", Optional.empty()), codec.decode("{\"k\":null}", MAP_OF_OPTIONAL_STRING));
    }

    @Test
    void aMapKeepsItsFreeKeysAndItsValueType() {
        JsonSchema map = codec.generateDecodeSchema(MAP_OF_STRING);

        assertEquals(List.of("object"), map.getType());
        assertEquals(new JsonSchemaBuilder().setType("string").build(), map.getAdditionalProperties());
        assertEquals(Map.of("k", "v"), codec.decode("{\"k\":\"v\"}", MAP_OF_STRING));

        JsonSchema open = codec.generateDecodeSchema(MAP_OF_OBJECT);

        assertEquals(List.of("object"), open.getType());
        assertNull(open.getAdditionalProperties());
        assertEquals(Map.of("k", Map.of("nested", List.of(1))),
                codec.decode("{\"k\":{\"nested\":[1]}}", MAP_OF_OBJECT));

        // A root that is not an object at all is described too.
        assertNull(codec.generateDecodeSchema(Object.class).getType());
        assertEquals(List.of("string"), codec.generateDecodeSchema(String.class).getType());
        assertEquals(List.of("integer"), codec.generateDecodeSchema(int.class).getType());
    }

    @Test
    void theScalarKindsAreDescribedByTheirType() {
        JsonSchema schema = codec.generateDecodeSchema(Variety.class);
        Map<String, JsonSchema> properties = schema.getProperties();

        // An enum is a definition others reference, and the definition carries the values.
        assertEquals("#/$defs/Color", properties.get("color").getRef());
        assertEquals(List.of("string"), schema.getDefs().get("Color").getType());
        assertEquals(List.of("RED", "GREEN", "BLUE"), schema.getDefs().get("Color").get("enum", List.class));

        assertEquals(List.of("boolean"), properties.get("flag").getType());
        assertEquals(List.of("number"), properties.get("ratio").getType());
        assertEquals(List.of("integer"), properties.get("count").getType());
        assertEquals(List.of("number"), properties.get("amount").getType());
        // A byte[] is the base64 string the mapper writes, not the array of strings victools assumes.
        assertEquals(List.of("string"), properties.get("data").getType());
        assertEquals("base64", properties.get("data").get("contentEncoding", String.class));
        assertEquals(List.of("string"), properties.get("date").getType());
        assertEquals("date", properties.get("date").get("format", String.class));
        assertEquals(List.of("string"), properties.get("time").getType());
    }

    @Test
    void aSelfReferencingTypeStaysFinite() {
        JsonSchema schema = codec.generateDecodeSchema(Link.class);

        assertEquals(List.of("string"), schema.getProperties().get("name").getType());
        assertEquals("#", schema.getProperties().get("next").getRef());
    }

    @Test
    void aTypeUsedOnceIsDescribedInPlace() {
        JsonSchema schema = codec.generateDecodeSchema(Kitchen.class);

        // Nothing is swept into $defs: every type here is written once, so each is described where it
        // is written rather than by reference.
        assertNull(schema.getDefs());
        assertEquals(List.of("string"), schema.getProperties().get("nested").getProperties().get("value").getType());
    }

    @Test
    void theDecodeSchemaAcceptsEveryDocumentItAllows() {
        assertEquals(sampleKitchen(), codec.decode(EVERYTHING, Kitchen.class));
        assertEquals(sampleKitchen(), codec.decode(WITHOUT_OPTIONAL, Kitchen.class));
        // A settable property may be left out even though the schema demands it: being stricter than
        // the binder is the direction this promise runs in, and the binder is what would answer.
        Settable settable = codec.decode("{\"text\":\"x\"}", Settable.class);
        assertEquals("x", settable.text);
    }

    @Test
    void theEncodeSchemaDescribesWhatWritingProduces() {
        JsonSchema schema = codec.generateEncodeSchema(Kitchen.class);

        assertEquals(properties(schema), keysOf(codec.encode(sampleKitchen())));
        assertEquals(List.copyOf(schema.getProperties().keySet()), List.copyOf(schema.getRequired()));
        // The container values are described as they are in the read direction: an optional value made
        // nullable, a map's value type under additionalProperties.
        assertTrue(allowsNull(schema, schema.getProperties().get("optional")));
        assertEquals(new JsonSchemaBuilder().setType("string").build(),
                schema.getProperties().get("map").getAdditionalProperties());
    }

    @Test
    void thePromiseHoldsUnderOtherMapperSettings() {
        for (JsonMapper mapper : otherMappers()) {
            JacksonJsonCodec other = new JacksonJsonCodec(mapper.rebuild());

            assertEquals(sampleKitchen(), other.decode(EVERYTHING, Kitchen.class), mapper.toString());

            JsonSchema schema = other.generateEncodeSchema(Kitchen.class);
            List<String> written = keysOf(other.encode(sampleKitchenWithNullOptional()));
            assertTrue(schema.getProperties().keySet().containsAll(written), written.toString());
            schema.getRequired().forEach(required -> assertTrue(written.contains(required), required));
        }

        // An inclusion setting that may leave a value out takes that property out of required.
        JacksonJsonCodec omittingNulls = new JacksonJsonCodec(JsonMapper.builder()
                .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL)));
        assertFalse(omittingNulls.generateEncodeSchema(Kitchen.class).getRequired().contains("optional"));
    }

    @Test
    void nullSettingsApplyNoneOfTheChoices() {
        JsonSchema schema = codecWith(null).generateEncodeSchema(Kitchen.class);

        // None of the option sets, module flags or Jackson options are applied: no additionalProperties,
        // the preset's own $schema, and no required list.
        assertNull(schema.getAdditionalProperties());
        assertTrue(schema.keys().contains("$schema"));
        assertNull(schema.getRequired());
    }

    @Test
    void theOptionSetsAreTheOnesInTheSettings() {
        JacksonSchemaSettings settings = new JacksonSchemaSettings();
        settings.getOptions().clear();
        settings.getSuppressedOptions().clear();

        JsonSchema schema = codecWith(settings).generateEncodeSchema(Kitchen.class);

        assertNull(schema.getAdditionalProperties());
        assertTrue(schema.keys().contains("$schema"));
    }

    @Test
    void theModuleFlagsAreTheOnesInTheSettings() {
        JacksonSchemaSettings settings = new JacksonSchemaSettings();
        settings.setRequiredProperties(false);
        settings.setFlattenOptionals(false);

        assertNull(codecWith(settings).generateDecodeSchema(Kitchen.class).getRequired());
        // The preset's own FLATTENED_OPTIONALS answers now: the value type, without the null branch.
        JsonSchema optional = codecWith(settings).generateDecodeSchema(OPTIONAL_STRING);
        assertFalse(allowsNull(optional, optional));
    }

    @Test
    void theJacksonOptionsAreTheOnesInTheSettings() {
        JacksonSchemaSettings settings = new JacksonSchemaSettings();
        settings.getJacksonOptions().clear();

        // With the Jackson module gone, the annotation can no longer demand what the type makes optional.
        assertFalse(codecWith(settings).generateDecodeSchema(Annotated.class).getRequired().contains("optional"));
    }

    @Test
    void theByteArrayIsAStringOnlyWhileTheChoiceIsOn() {
        JacksonSchemaSettings settings = new JacksonSchemaSettings();
        settings.setBase64Bytes(false);

        // Off leaves victools' own description — an array of strings, which the mapper never writes.
        assertEquals(List.of("array"), codecWith(settings).generateDecodeSchema(Variety.class)
                .getProperties().get("data").getType());
    }

    /** A codec over the given choices, so a test can see what turning one off changes. */
    private JacksonJsonCodec codecWith(JacksonSchemaSettings settings) {
        return new JacksonJsonCodec(jsonMapper.rebuild(),
                new SchemaGenerator(
                        JacksonSchemaConfigBuilders.encodeSchemaConfigBuilder(jsonMapper, settings).build()),
                new SchemaGenerator(
                        JacksonSchemaConfigBuilders.decodeSchemaConfigBuilder(jsonMapper, settings).build()));
    }

    /** The mapper settings a generated schema has to stay honest under. */
    private static List<JsonMapper> otherMappers() {
        return List.of(
                JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build(),
                JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).build(),
                JsonMapper.builder()
                        .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
                        .build());
    }

    private static Kitchen sampleKitchen() {
        return new Kitchen("x", 1, 2, Optional.empty(), List.of("a"),
                List.of(Optional.empty(), Optional.of("a")), Map.of("k", "v"), Map.of("k", 1), Map.of(),
                new Nested("v"));
    }

    /** The same sample with a null where the type allows one, so an inclusion setting can omit it. */
    private static Kitchen sampleKitchenWithNullOptional() {
        return new Kitchen("x", 1, 2, null, List.of("a"), List.of(Optional.empty(), Optional.of("a")),
                Map.of("k", "v"), Map.of("k", 1), Map.of(), new Nested("v"));
    }

    private static List<String> properties(JsonSchema schema) {
        return List.copyOf(schema.getProperties().keySet());
    }

    /**
     * Whether a schema admits a null value, spelled the way this module spells one: a null branch
     * beside the value's own schema. A property may hold a reference to the definition instead, which
     * is why the root schema is asked for it.
     */
    private static boolean allowsNull(JsonSchema root, JsonSchema schema) {
        JsonSchema definition = schema.getRef() == null
                ? schema
                : root.getDefs().get(schema.getRef().substring(schema.getRef().lastIndexOf('/') + 1));
        Object anyOf = definition.get("anyOf");
        if (!(anyOf instanceof List<?> branches)) {
            return false;
        }
        return branches.stream().anyMatch(branch -> {
            List<String> types = branch instanceof JsonSchema subSchema ? subSchema.getType() : null;
            return types != null && types.contains("null");
        });
    }

    private List<String> keysOf(String json) {
        Map<?, ?> map = jsonMapper.readValue(json, Map.class);
        List<String> keys = new ArrayList<>();
        map.keySet().forEach(key -> keys.add(String.valueOf(key)));
        return keys;
    }

}
