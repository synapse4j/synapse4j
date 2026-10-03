package io.github.synapse4j.jackson;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

import com.github.victools.jsonschema.generator.Module;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The values the schema tests describe, and the little they build a generator with or read back.
 *
 * <p>
 * Shared rather than repeated per test class: a type two tests describe is one fixture, and a copy that
 * drifts between them describes nothing in particular. What is one test's own shape stays with that test;
 * this class holds what more than one of them needs.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class SchemaFixtures {

    /** The mapper the schema tests build from, unless they have a reason to configure one. */
    static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** A type with a property, for describing one nested inside another. */
    record Nested(String value) {
    }

    /** A type whose properties can be set rather than only constructed. */
    static class Settable {

        public String text;

        public int number;

        public Optional<String> optional;
    }

    /** One property of each kind the required answer differs by: a reference, a primitive and a wrapper. */
    record Both(String plain, int count, Optional<String> optional) {
    }

    static final Type OPTIONAL_STRING = new TypeReference<Optional<String>>() {
    }.getType();

    static final Type OPTIONAL_INT = new TypeReference<OptionalInt>() {
    }.getType();

    static final Type OPTIONAL_LONG = new TypeReference<OptionalLong>() {
    }.getType();

    static final Type OPTIONAL_DOUBLE = new TypeReference<OptionalDouble>() {
    }.getType();

    static final Type LIST_OF_OPTIONAL_STRING = new TypeReference<List<Optional<String>>>() {
    }.getType();

    static final Type MAP_OF_OPTIONAL_STRING = new TypeReference<Map<String, Optional<String>>>() {
    }.getType();

    static final Type MAP_OF_STRING = new TypeReference<Map<String, String>>() {
    }.getType();

    static final Type MAP_OF_OBJECT = new TypeReference<Map<String, Object>>() {
    }.getType();

    /** A generator over a plain victools configuration carrying the given modules. */
    static SchemaGenerator generator(Module... modules) {
        return generator(MAPPER, modules);
    }

    /** The same over a mapper configured for the test asking, whose settings the modules then answer. */
    static SchemaGenerator generator(JsonMapper jsonMapper, Module... modules) {
        SchemaGeneratorConfigBuilder configBuilder = new SchemaGeneratorConfigBuilder(jsonMapper,
                SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON);
        for (Module module : modules) {
            configBuilder.with(module);
        }
        return new SchemaGenerator(configBuilder.build());
    }

    /** A map described with its value type rather than as a bare object, the way victools opts into it. */
    static Module mapValues() {
        return configBuilder -> configBuilder.with(Option.MAP_VALUES_AS_ADDITIONAL_PROPERTIES);
    }

    /** The required property names of a schema, sorted: their order is the generator's own answer. */
    static List<String> required(JsonNode schema) {
        List<String> names = new ArrayList<>();
        schema.at("/required").forEach(name -> names.add(name.asString()));
        names.sort(null);
        return names;
    }

}