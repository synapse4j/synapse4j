package io.github.synapse4j.spring.boot;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.github.synapse4j.data.ChatOptions;
import io.github.synapse4j.data.ChatResponseFormat;
import io.github.synapse4j.json.JsonCodec;
import io.github.synapse4j.json.JsonSchema;

import lombok.Getter;
import lombok.Setter;

/**
 * The {@code synapse4j.chat.options.*} defaults every auto-configured client hands to its calls, so
 * a standing model, temperature or response format need not be restated per request.
 *
 * <p>
 * A mirror of {@link ChatOptions} rather than the library type itself, because the library type
 * cannot be bound: its nested HTTP options are types from another jar, which the metadata processor
 * does not recurse into, its response format carries a schema no configuration source can spell, and
 * its provider-extras bag has no shape Spring can write into. Only the bindable fields are restated;
 * {@link #toChatOptions(JsonCodec)} rebuilds the library type from them.
 *
 * <p>
 * {@code extras} is the escape hatch and binds raw keys: a key is the provider's own wire name, a
 * dotted key addressing a nested member. Values take their type from the configuration source — a
 * YAML document keeps booleans, numbers and nested objects, while a {@code .properties} file yields
 * a string for every one of them, so a non-string extra belongs in YAML. A list cannot be
 * expressed: YAML flattens it into indexed keys, which bind back as an object rather than an array.
 */
@Getter
@Setter
public class ChatOptionsProperties {

    /** Identifier of the model to call. */
    private @Nullable String model;

    /** Sampling temperature. */
    private @Nullable Double temperature;

    /** Upper bound on the tokens generated. */
    private @Nullable Integer maxOutputTokens;

    /** Nucleus sampling threshold. */
    private @Nullable Double topP;

    /** How much the model should reason before it answers. */
    private @Nullable String reasoningEffort;

    /** Which tools the model may call. */
    private @Nullable String toolChoice;

    /** The tool {@link #toolChoice} names, and no other. */
    private @Nullable String toolChoiceName;

    /**
     * The shape the answer should take. A mirror of {@link ChatResponseFormat}: the library type's
     * schema is a {@link JsonSchema}, which no configuration source can spell, so it is bound as the
     * JSON text it is and read into a schema when the options are built.
     */
    private final ResponseFormatProperties responseFormat = new ResponseFormatProperties();

    /** Headers for every call's HTTP request. */
    private final Map<String, String> headers = new LinkedHashMap<>();

    /** Provider-specific fields to merge into every call's payload, by raw wire name. */
    private final Map<String, Object> extras = new LinkedHashMap<>();

    /**
     * Builds the library options these properties stand for.
     *
     * @param codec the codec that reads a bound schema text into a {@link JsonSchema}; never
     *                  {@code null}
     * @return a new {@link ChatOptions} holding the bound values; never {@code null}
     */
    public ChatOptions toChatOptions(JsonCodec codec) {
        ChatOptions options = new ChatOptions();
        options.setModel(model);
        options.setTemperature(temperature);
        options.setMaxOutputTokens(maxOutputTokens);
        options.setTopP(topP);
        options.setReasoningEffort(reasoningEffort);
        options.setToolChoice(toolChoice);
        options.setToolChoiceName(toolChoiceName);
        options.setResponseFormat(responseFormat.toChatResponseFormat(codec));
        options.getHeaders().putAll(headers);
        extras.forEach(options.getExtras()::putRaw);
        return options;
    }

    /**
     * The bindable form of {@link ChatResponseFormat}: the same members, with the schema as the JSON
     * text a configuration source can carry.
     */
    @Getter
    @Setter
    public static class ResponseFormatProperties {

        /** One of the {@code TYPE_*} constants of {@link ChatResponseFormat}, or a provider's own. */
        private @Nullable String type;

        /** Name of the schema; the protocol that requires a name needs one. */
        private @Nullable String name;

        /** What the schema describes, for the model to read. */
        private @Nullable String description;

        /** The schema, as JSON Schema text. */
        private @Nullable String schema;

        /** Whether the provider has to enforce the schema. */
        private @Nullable Boolean strict;

        /**
         * Builds the library format these properties stand for.
         *
         * @param codec the codec that reads {@link #schema} into a schema; never {@code null}
         * @return the format; never {@code null}
         */
        ChatResponseFormat toChatResponseFormat(JsonCodec codec) {
            ChatResponseFormat format = new ChatResponseFormat();
            format.setType(type);
            format.setName(name);
            format.setDescription(description);
            format.setSchema(schema == null ? null : codec.decode(schema, JsonSchema.class));
            format.setStrict(strict);
            return format;
        }
    }

}
