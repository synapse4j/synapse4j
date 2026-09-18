package io.github.synapse4j.data;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.ToString;

/**
 * The application's answer to a {@link ToolCallPart}.
 *
 * <p>
 * The result is a list of parts rather than a single string because the protocols this library
 * targets accept either form, and a tool may legitimately return an image or a document. Modelling
 * only the string form would push an adapter into flattening the rest, and flattening loses content
 * silently instead of failing loudly.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ToolResultPart extends ContentPart {

    /** Identifier of the {@link ToolCallPart} being answered. */
    private String callId;

    /** Name of the tool that produced the result; not every protocol carries it. */
    private String name;

    /** The result itself. Never {@code null}; empty is allowed. */
    @NonNull
    private List<ContentPart> parts = new ArrayList<>();

    /** Whether the tool failed. Some protocols report this out of band rather than in the result. */
    private boolean error;

}
