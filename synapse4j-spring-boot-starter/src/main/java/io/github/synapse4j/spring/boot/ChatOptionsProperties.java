package io.github.synapse4j.spring.boot;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import io.github.synapse4j.data.ChatOptions;
import io.github.synapse4j.data.ChatResponseFormat;

import lombok.Getter;
import lombok.Setter;

/**
 * The {@code synapse4j.chat-options.*} defaults every auto-configured client hands to its calls, so
 * a standing model, temperature or response format need not be restated per request.
 *
 * <p>
 * A mirror of {@link ChatOptions} rather than the library type itself, because the library type
 * cannot be bound: its nested response format and HTTP options are types from another jar, which
 * the metadata processor does not recurse into, and its provider-extras bag has no shape Spring can
 * write into. Only the bindable fields are restated; {@link #toChatOptions()} rebuilds the library
 * type from them.
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
     * The shape the answer should take. The marker is what makes the metadata processor recurse
     * into the type from another jar; without it its keys would silently vanish from the metadata.
     *
     * <p>
     * Only the five members modelled on {@link ChatResponseFormat} — {@code type}, {@code name},
     * {@code description}, {@code schema} and {@code strict} — are bindable here. Its
     * {@code extras} bag has a getter but no setter, so a {@code response-format.extras.*} key binds
     * nowhere and does nothing: it is absent from the metadata, and the binder does not refuse it.
     */
    @NestedConfigurationProperty
    private final ChatResponseFormat responseFormat = new ChatResponseFormat();

    /** Headers for every call's HTTP request. */
    private final Map<String, String> headers = new LinkedHashMap<>();

    /** Provider-specific fields to merge into every call's payload, by raw wire name. */
    private final Map<String, Object> extras = new LinkedHashMap<>();

    /**
     * Builds the library options these properties stand for.
     *
     * @return a new {@link ChatOptions} holding the bound values; never {@code null}
     */
    public ChatOptions toChatOptions() {
        ChatOptions options = new ChatOptions();
        options.setModel(model);
        options.setTemperature(temperature);
        options.setMaxOutputTokens(maxOutputTokens);
        options.setTopP(topP);
        options.setReasoningEffort(reasoningEffort);
        options.setToolChoice(toolChoice);
        options.setToolChoiceName(toolChoiceName);
        options.setResponseFormat(responseFormat);
        options.getHeaders().putAll(headers);
        extras.forEach(options.getExtras()::putRaw);
        return options;
    }

}
