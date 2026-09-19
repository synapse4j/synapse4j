package io.github.synapse4j.jackson;

import java.util.Objects;

import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonOption;
import com.github.victools.jsonschema.module.jackson.JacksonSchemaModule;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the victools configuration and generators this module works with, one per direction, and
 * holds all of the option policy behind them.
 *
 * <p>
 * Every choice about keywords — the draft, the option preset, the Jackson module, what to do about a
 * naming strategy — is made here and nowhere else. {@link JacksonJsonCodec} takes built generators and
 * knows none of it, so a caller who wants other choices builds their own (through the customizers
 * below, or with victools directly) and hands them over.
 *
 * <p>
 * Both directions are configured from the {@link JsonMapper} they are built with, on purpose: the
 * mapper is what tells the generator which properties exist and what they are called, and a schema
 * generated from different settings than the ones that move the JSON describes something nobody moves.
 *
 * <p>
 * The two directions differ in which Jackson introspection answers that question — what the mapper
 * writes, or what it reads — and therefore sometimes differ in the schema they produce. That is not an
 * accident to be papered over: see {@link JacksonPropertyDiscovery}.
 *
 * <p>
 * There are two shapes. The generator factories are the one-call shape. The config-builder methods are
 * the two-step shape a framework integration wants: take the builder with the defaults already on it,
 * run the {@link SchemaGeneratorConfigBuilderCustomizer}s the framework collects over it, then build a
 * generator from the result.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class JacksonSchemaGenerators {

    /**
     * Builds the generator for the schema of the JSON this codec writes.
     *
     * @param jsonMapper  the mapper whose introspection the generator should use; must not be
     *                        {@code null}
     * @param customizers what to change about the defaults, applied in the order given; may be empty
     * @return the generator; never {@code null}
     */
    public static SchemaGenerator encodeSchemaGenerator(JsonMapper jsonMapper,
            SchemaGeneratorConfigBuilderCustomizer... customizers) {
        return new SchemaGenerator(config(encodeSchemaConfigBuilder(jsonMapper), customizers));
    }

    /**
     * Builds the generator for the schema of the JSON this codec reads.
     *
     * @param jsonMapper  the mapper whose introspection the generator should use; must not be
     *                        {@code null}
     * @param customizers what to change about the defaults, applied in the order given; may be empty
     * @return the generator; never {@code null}
     */
    public static SchemaGenerator decodeSchemaGenerator(JsonMapper jsonMapper,
            SchemaGeneratorConfigBuilderCustomizer... customizers) {
        return new SchemaGenerator(config(decodeSchemaConfigBuilder(jsonMapper), customizers));
    }

    /**
     * Creates the configuration builder for the schema of the JSON this codec writes, with this
     * module's defaults already applied and no {@link SchemaGeneratorConfigBuilderCustomizer} run yet.
     *
     * <p>
     * Running customizers and building a generator from the result is the caller's part. A framework
     * that manages beans collects customizers and wants the step in between to be its own; this method
     * is that step, so the defaults a caller starts from are exactly the ones this module ships.
     *
     * @param jsonMapper the mapper whose introspection the generator should use; must not be
     *                       {@code null}
     * @return the configuration builder; never {@code null}
     */
    public static SchemaGeneratorConfigBuilder encodeSchemaConfigBuilder(JsonMapper jsonMapper) {
        return configBuilder(jsonMapper, true);
    }

    /**
     * Creates the configuration builder for the schema of the JSON this codec reads, with this
     * module's defaults already applied and no {@link SchemaGeneratorConfigBuilderCustomizer} run yet.
     *
     * <p>
     * Running customizers and building a generator from the result is the caller's part; see
     * {@link #encodeSchemaConfigBuilder(JsonMapper)} for why this method exists.
     *
     * @param jsonMapper the mapper whose introspection the generator should use; must not be
     *                       {@code null}
     * @return the configuration builder; never {@code null}
     */
    public static SchemaGeneratorConfigBuilder decodeSchemaConfigBuilder(JsonMapper jsonMapper) {
        return configBuilder(jsonMapper, false);
    }

    /**
     * Assembles the configuration this module starts from, before any customizer has seen it.
     *
     * <p>
     * {@code PLAIN_JSON} with an explicit draft decides the vocabulary; {@link JacksonPropertyDiscovery}
     * then imposes the mapper's own answer to which properties exist, what they are called and in what
     * order; and what is left are two choices about meaning — {@code additionalProperties: false}
     * written out on every object rather than left implicit, because silence is read differently
     * depending on who reads the document, and {@code MAP_VALUES_AS_ADDITIONAL_PROPERTIES}, so a map
     * describes its value type rather than degrading to a bare object — and one about noise: no
     * {@code $schema} indicator, since the draft is already implied by the keywords that are written
     * and the marker only tells a validator which dialect it is reading.
     *
     * @param jsonMapper the mapper whose introspection the generator should use
     * @param encoding   whether the schema describes JSON this codec writes
     * @return the configuration builder; never {@code null}
     */
    private static SchemaGeneratorConfigBuilder configBuilder(JsonMapper jsonMapper, boolean encoding) {
        Objects.requireNonNull(jsonMapper, "jsonMapper must not be null");
        SchemaGeneratorConfigBuilder configBuilder = new SchemaGeneratorConfigBuilder(jsonMapper,
                SchemaVersion.DRAFT_2020_12,
                OptionPreset.PLAIN_JSON)
                .with(new JacksonSchemaModule(JacksonOption.RESPECT_JSONPROPERTY_REQUIRED))
                .with(Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT, Option.MAP_VALUES_AS_ADDITIONAL_PROPERTIES)
                .without(Option.SCHEMA_VERSION_INDICATOR);
        // What Jackson considers a property decides membership, names and order before anything a caller adds.
        new JacksonPropertyDiscovery(jsonMapper, encoding).customize(configBuilder);
        return configBuilder;
    }

    /**
     * Runs the given customizers over the builder, in order, and builds the configuration.
     *
     * @param configBuilder the builder to customize and build; must not be {@code null}
     * @param customizers   what to change about the defaults, applied in the order given
     * @return the configuration; never {@code null}
     */
    private static SchemaGeneratorConfig config(SchemaGeneratorConfigBuilder configBuilder,
            SchemaGeneratorConfigBuilderCustomizer... customizers) {
        Objects.requireNonNull(configBuilder, "configBuilder must not be null");
        Objects.requireNonNull(customizers, "customizers must not be null");
        for (SchemaGeneratorConfigBuilderCustomizer customizer : customizers) {
            Objects.requireNonNull(customizer, "customizer must not be null").customize(configBuilder);
        }
        return configBuilder.build();
    }

}
