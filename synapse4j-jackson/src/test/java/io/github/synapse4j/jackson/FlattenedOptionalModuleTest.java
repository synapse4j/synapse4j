package io.github.synapse4j.jackson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.github.victools.jsonschema.generator.Module;
import com.github.victools.jsonschema.generator.Option;

import io.github.synapse4j.jackson.SchemaFixtures.Nested;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

/**
 * Pins what {@link FlattenedOptionalModule} does on its own, applied to a plain victools configuration.
 *
 * <p>
 * The behaviour is asserted on the generated JSON rather than through this library's own model, so a
 * change in how a schema is read into {@link io.github.synapse4j.json.JsonSchema} cannot hide one in
 * what the module writes.
 */
class FlattenedOptionalModuleTest {

    /** Every position a wrapper can be written in, one property each. */
    record Holder(Optional<String> text, List<Optional<String>> list, Map<String, Optional<String>> map,
            Optional<Nested> nested) {
    }

    /** A type that contains itself, through the one member a schema must not inline. */
    record Node(String name, Optional<Node> next) {
    }

    record Leaf(Optional<String> value) {
    }

    /** A nested type in each position a property can hold one, each written once so it stays in place. */
    record Branch(Leaf leaf) {
    }

    record Grove(List<Leaf> leaves) {
    }

    record Orchard(Map<String, Leaf> map) {
    }

    private static final Type LIST_OF_LIST_OF_OPTIONAL_STRING = new TypeReference<List<List<Optional<String>>>>() {
    }.getType();

    private static final Type OPTIONAL_LIST_OF_STRING = new TypeReference<Optional<List<String>>>() {
    }.getType();

    @Test
    void optionalValueAndNullEverywhere() {
        JsonNode holder = generate(Holder.class, new FlattenedOptionalModule(), SchemaFixtures.mapValues());

        assertEquals("string", holder.at("/properties/text/anyOf/0/type").asString());
        assertEquals("null", holder.at("/properties/text/anyOf/1/type").asString());
        assertEquals("string", holder.at("/properties/list/items/anyOf/0/type").asString());
        assertEquals("null", holder.at("/properties/list/items/anyOf/1/type").asString());
        assertEquals("string", holder.at("/properties/map/additionalProperties/anyOf/0/type").asString());
        assertEquals("null", holder.at("/properties/map/additionalProperties/anyOf/1/type").asString());
        assertEquals("string",
                generate(SchemaFixtures.OPTIONAL_STRING, new FlattenedOptionalModule()).at("/anyOf/0/type").asString());
    }

    @Test
    void objectWrapperSchemaAndNull() {
        JsonNode holder = generate(Holder.class, new FlattenedOptionalModule());

        assertEquals("object", holder.at("/properties/nested/anyOf/0/type").asString());
        assertEquals("string", holder.at("/properties/nested/anyOf/0/properties/value/type").asString());
        assertEquals("null", holder.at("/properties/nested/anyOf/1/type").asString());
    }

    @Test
    void primitiveOptionalsBoxedAndNull() {
        assertEquals("integer", generate(SchemaFixtures.OPTIONAL_INT, new FlattenedOptionalModule())
                .at("/anyOf/0/type").asString());
        assertEquals("integer", generate(SchemaFixtures.OPTIONAL_LONG, new FlattenedOptionalModule())
                .at("/anyOf/0/type").asString());
        assertEquals("number", generate(SchemaFixtures.OPTIONAL_DOUBLE, new FlattenedOptionalModule())
                .at("/anyOf/0/type").asString());
        assertEquals("null",
                generate(SchemaFixtures.OPTIONAL_INT, new FlattenedOptionalModule()).at("/anyOf/1/type").asString());
    }

    @Test
    void containerWrapperSchemaAndNull() {
        JsonNode schema = generate(OPTIONAL_LIST_OF_STRING, new FlattenedOptionalModule());

        assertEquals("array", schema.at("/anyOf/0/type").asString());
        assertEquals("string", schema.at("/anyOf/0/items/type").asString());
        assertEquals("null", schema.at("/anyOf/1/type").asString());
    }

    @Test
    void nestedContainerWrapperStillNullable() {
        JsonNode schema = generate(LIST_OF_LIST_OF_OPTIONAL_STRING, new FlattenedOptionalModule());

        assertEquals("string", schema.at("/items/items/anyOf/0/type").asString());
        assertEquals("null", schema.at("/items/items/anyOf/1/type").asString());
    }

    @Test
    void ruleReachesIntoNestedType() {
        JsonNode branch = generate(Branch.class, new FlattenedOptionalModule());
        JsonNode grove = generate(Grove.class, new FlattenedOptionalModule());
        JsonNode orchard = generate(Orchard.class, new FlattenedOptionalModule(), SchemaFixtures.mapValues());

        // A property of a nested type, and one of a nested type inside a list, are described in place.
        assertEquals("string", branch.at("/properties/leaf/properties/value/anyOf/0/type").asString());
        assertEquals("null", branch.at("/properties/leaf/properties/value/anyOf/1/type").asString());
        assertEquals("string", grove.at("/properties/leaves/items/properties/value/anyOf/0/type").asString());
        assertEquals("null", grove.at("/properties/leaves/items/properties/value/anyOf/1/type").asString());
        // A map's value is a reference to the type's own definition, which is where the wrapper sits.
        assertEquals("#/$defs/Leaf", orchard.at("/properties/map/additionalProperties/$ref").asString());
        assertEquals("string", orchard.at("/$defs/Leaf/properties/value/anyOf/0/type").asString());
        assertEquals("null", orchard.at("/$defs/Leaf/properties/value/anyOf/1/type").asString());
    }

    @Test
    void recursiveOptionalStaysFinite() {
        JsonNode schema = generate(Node.class, new FlattenedOptionalModule());

        // The wrapped type is what recurses, so its reference is the one a cycle needs.
        assertEquals("#", schema.at("/properties/next/anyOf/0/$ref").asString());
        assertEquals("null", schema.at("/properties/next/anyOf/1/type").asString());
    }

    @Test
    void victoolsOptionalHandlingDisabled() {
        // PLAIN_JSON ships Option.FLATTENED_OPTIONALS, which answers for a member and for nothing else:
        // without this module a field of the same type comes out unlike the type itself.
        JsonNode bareField = generate(Holder.class, SchemaFixtures.mapValues()).at("/properties/text");
        JsonNode bareRoot = generate(SchemaFixtures.OPTIONAL_STRING);

        assertFalse(bareField.has("anyOf"), bareField.toString());
        assertEquals("object", bareRoot.get("type").asString());
    }

    @Test
    void otherTypesAreLeftAlone() {
        assertEquals(generate(Nested.class).toString(),
                generate(Nested.class, new FlattenedOptionalModule()).toString());
    }

    @Test
    void wrapperCarriesNoOwnAttributes() {
        JsonNode schema = generate(Holder.class, new FlattenedOptionalModule(),
                configBuilder -> configBuilder.with(Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT));

        // The keyword belongs on the object the value describes, not on the anyOf around it.
        assertTrue(schema.at("/properties/nested/anyOf/0").has("additionalProperties"), schema.toString());
        assertFalse(schema.at("/properties/nested").has("additionalProperties"), schema.toString());
        assertFalse(schema.at("/properties/text").has("additionalProperties"), schema.toString());
    }

    @Test
    void typeUsedTwiceStaysInline() {
        assertFalse(generate(Holder.class, new FlattenedOptionalModule()).has("$defs"));
    }

    private static JsonNode generate(Type type, Module... modules) {
        return SchemaFixtures.generator(modules).generateSchema(type);
    }

}