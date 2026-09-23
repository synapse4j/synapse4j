package io.github.synapse4j.data;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * The model asking for a tool to be run.
 *
 * <p>
 * The arguments stay JSON text instead of a parsed tree: this library does not bind a JSON library,
 * so turning them into the application's own type is the job of the codec the application chose.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(callSuper = true)
public class ToolCallPart extends ContentPart {

    /** Identifier that ties this call to the {@link ToolResultPart} answering it. */
    private String callId;

    /** Name of the tool being called; the application resolves it against its own registry. */
    private String name;

    /** The arguments as JSON text, exactly as they arrived from the provider. */
    private String argumentsJson;

}
