package io.github.synapse4j.jackson;

import org.jspecify.annotations.Nullable;

import com.github.victools.jsonschema.generator.Module;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonOption;
import com.github.victools.jsonschema.module.jackson.JacksonSchemaModule;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the victools configuration this module works with, one per direction, and composes the policy
 * behind it.
 *
 * <p>
 * The choices are made here and in the modules it applies — {@link PropertyDiscoveryModule} for which
 * properties exist, {@link FlattenedOptionalModule} for how an optional value is described,
 * {@link DecodeRequiredPropertiesModule} or {@link EncodeRequiredPropertiesModule} for which properties
 * are demanded — and nowhere else. Which of them a caller wants is a {@link JacksonSchemaSettings},
 * whose defaults are the choices this module recommends; {@code null} applies none of them, leaving the
 * bare preset for a caller who wants to configure everything themselves. {@link JacksonJsonCodec} takes
 * built generators and knows none of it, so a caller who wants other choices builds their own (through
 * the settings and the modules below, or with victools directly) and hands them over.
 *
 * <p>
 * Both directions are configured from the {@link JsonMapper} they are built with, on purpose: the
 * mapper is what tells the generator which properties exist and what they are called, and a schema
 * generated from different settings than the ones that move the JSON describes something nobody moves.
 *
 * <p>
 * The two directions differ in which Jackson introspection answers that question — what the mapper
 * writes, or what it reads — and therefore sometimes differ in the schema they produce. That is not an
 * accident to be papered over: see {@link PropertyDiscoveryModule}.
 *
 * <p>
 * The shape is two steps: take the builder with the choices already on it, apply the {@link Module}s the
 * caller collects to it, then build a {@code SchemaGenerator} from the result.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class JacksonSchemaConfigBuilders {

    /**
     * Creates the configuration builder for the schema of the JSON this codec writes, with the given
     * choices already applied and no {@link Module} added yet.
     *
     * <p>
     * Applying modules and building a generator from the result is the caller's part, so the builder a
     * caller starts from carries exactly the choices this module ships.
     *
     * @param jsonMapper the mapper whose introspection the generator should use; must not be
     *                       {@code null}
     * @param settings   which of this module's choices to apply, or {@code null} to apply none of them
     * @return the configuration builder; never {@code null}
     */
    public static SchemaGeneratorConfigBuilder encodeSchemaConfigBuilder(JsonMapper jsonMapper,
            @Nullable JacksonSchemaSettings settings) {
        return configBuilder(jsonMapper, true, settings);
    }

    /**
     * Creates the configuration builder for the schema of the JSON this codec reads, with the given
     * choices already applied and no {@link Module} added yet.
     *
     * <p>
     * Applying modules and building a generator from the result is the caller's part; see
     * {@link #encodeSchemaConfigBuilder(JsonMapper, JacksonSchemaSettings)} for why this method exists.
     *
     * @param jsonMapper the mapper whose introspection the generator should use; must not be
     *                       {@code null}
     * @param settings   which of this module's choices to apply, or {@code null} to apply none of them
     * @return the configuration builder; never {@code null}
     */
    public static SchemaGeneratorConfigBuilder decodeSchemaConfigBuilder(JsonMapper jsonMapper,
            @Nullable JacksonSchemaSettings settings) {
        return configBuilder(jsonMapper, false, settings);
    }

    /**
     * Assembles the configuration the given choices describe, before any module a caller adds has seen
     * it.
     *
     * <p>
     * {@code PLAIN_JSON} with an explicit draft decides the vocabulary; then each choice in the settings
     * is applied — the Jackson module and its options, {@link PropertyDiscoveryModule},
     * {@link FlattenedOptionalModule}, the required module for the direction, and the two option sets.
     * What is applied is applied in the order written here, which is the order the modules expect. A
     * {@code null} settings applies none of that, leaving the preset alone.
     *
     * @param jsonMapper the mapper whose introspection the generator should use
     * @param encoding   whether the schema describes JSON this codec writes
     * @param settings   which choices to apply; may be {@code null}
     * @return the configuration builder; never {@code null}
     */
    private static SchemaGeneratorConfigBuilder configBuilder(@NonNull JsonMapper jsonMapper, boolean encoding,
            @Nullable JacksonSchemaSettings settings) {
        SchemaGeneratorConfigBuilder configBuilder = new SchemaGeneratorConfigBuilder(jsonMapper,
                SchemaVersion.DRAFT_2020_12,
                OptionPreset.PLAIN_JSON);
        if (settings == null) {
            return configBuilder;
        }
        if (!settings.getJacksonOptions().isEmpty()) {
            configBuilder.with(new JacksonSchemaModule(settings.getJacksonOptions().toArray(JacksonOption[]::new)));
        }
        if (settings.isDiscoverProperties()) {
            configBuilder.with(new PropertyDiscoveryModule(jsonMapper, encoding));
        }
        if (settings.isFlattenOptionals()) {
            configBuilder.with(new FlattenedOptionalModule());
        }
        if (settings.isRequiredProperties()) {
            configBuilder.with(encoding
                    ? new EncodeRequiredPropertiesModule(jsonMapper)
                    : new DecodeRequiredPropertiesModule(jsonMapper));
        }
        for (Option option : settings.getOptions()) {
            configBuilder.with(option);
        }
        for (Option option : settings.getSuppressedOptions()) {
            configBuilder.without(option);
        }
        return configBuilder;
    }

}
