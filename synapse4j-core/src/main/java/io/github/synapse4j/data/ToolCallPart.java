package io.github.synapse4j.data;

import org.jspecify.annotations.Nullable;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/**
 * The model asking for a tool to be run.
 *
 * <p>
 * The arguments stay JSON text instead of a parsed tree: this library does not bind a JSON library,
 * so turning them into the application's own type is the job of the codec the application chose.
 */
@Getter
@ToString(callSuper = true)
@AllArgsConstructor
public class ToolCallPart extends ContentPart {

    /** Identifier that ties this call to the {@link ToolResultPart} answering it. */
    private final @Nullable String callId;

    /** Name of the tool being called; the application resolves it against its own registry. */
    private final @Nullable String name;

    /** The arguments as JSON text, exactly as they arrived from the provider. */
    private final @Nullable String argumentsJson;

    /**
     * Everything this part can carry, extras included. Hand-written, because Lombok cannot generate a
     * constructor that calls a super constructor with arguments.
     *
     * @param callId        the id tying this call to the result answering it
     * @param name          the name of the tool being called
     * @param argumentsJson the arguments as JSON text
     * @param extras        provider-specific fields, {@code null} for none; frozen on the way in
     */
    public ToolCallPart(@Nullable String callId, @Nullable String name, @Nullable String argumentsJson,
            @Nullable ProviderExtras extras) {
        super(extras);
        this.callId = callId;
        this.name = name;
        this.argumentsJson = argumentsJson;
    }

}
