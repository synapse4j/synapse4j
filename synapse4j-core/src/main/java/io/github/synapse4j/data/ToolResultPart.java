package io.github.synapse4j.data;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.Setter;

/**
 * The application's answer to a {@link ToolCallPart}.
 *
 * <p>
 * The result is a list of parts rather than a single string because the protocols this library
 * targets accept either form, and a tool may legitimately return an image or a document. Modelling
 * only the string form would push an adapter into flattening the rest, and flattening loses content
 * silently instead of failing loudly.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
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

    /**
     * The parts are counted rather than printed: they carry the tool's answer, and a printout that
     * carried it would be as long as the result.
     *
     * @return this result, in brief
     */
    @Override
    public String toString() {
        return "ToolResultPart(super=" + super.toString() + ", callId=" + callId + ", name=" + name + ", parts="
                + parts.size() + ", error=" + error + ')';
    }

}
