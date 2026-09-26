package io.github.synapse4j.data;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

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
    private @Nullable String callId;

    /** Name of the tool that produced the result; not every protocol carries it. */
    private @Nullable String name;

    /** The result itself. Never {@code null}; empty is allowed. */
    private final List<ContentPart> parts = new ArrayList<>();

    /** Whether the tool failed. Some protocols report it out of band rather than in the result. */
    private boolean error;

    /**
     * A result answering the given call, not marked as a failure: the two things a result is usually
     * built from, with the content added through {@link #addText(String)} or {@link #addPart}.
     *
     * @param callId the id of the call being answered; may be {@code null}
     * @param name   the name of the tool that produced the result; may be {@code null}
     */
    public ToolResultPart(@Nullable String callId, @Nullable String name) {
        this.callId = callId;
        this.name = name;
    }

    /**
     * Adds a part to the result.
     *
     * @param part the part to add
     * @return this result
     */
    public ToolResultPart addPart(@NonNull ContentPart part) {
        parts.add(part);
        return this;
    }

    /**
     * Adds a text part to the result — the shorthand for the one kind of part nearly every result
     * carries.
     *
     * @param text the text to add
     * @return this result
     */
    public ToolResultPart addText(@NonNull String text) {
        return addPart(new TextPart(text));
    }

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
