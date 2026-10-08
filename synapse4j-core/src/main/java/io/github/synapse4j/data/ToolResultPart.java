package io.github.synapse4j.data;

import java.util.List;

import org.jspecify.annotations.Nullable;

import lombok.Getter;
import lombok.NonNull;

/**
 * The application's answer to a {@link ToolCallPart}.
 *
 * <p>
 * The result is a list of parts rather than a single string because the protocols this library
 * targets accept either form, and a tool may legitimately return an image or a document. Modelling
 * only the string form would push an adapter into flattening the rest, and flattening loses content
 * silently instead of failing loudly.
 *
 * <p>
 * A result is a value: once built it never changes, so it is safe to share across calls and threads.
 * Its parts and its {@link ProviderExtras} bag are frozen on the way in.
 */
@Getter
public class ToolResultPart extends ContentPart {

    /** Identifier of the {@link ToolCallPart} being answered. */
    private final @Nullable String callId;

    /** Name of the tool that produced the result; not every protocol carries it. */
    private final @Nullable String name;

    /** The result itself. Never {@code null}; empty is allowed. */
    private final List<ContentPart> parts;

    /**
     * Whether the tool failed. A protocol with a member for it — Anthropic's {@code is_error} —
     * carries the failure there; a protocol without one leaves it unsent, and the result reaches the
     * model as an ordinary result.
     */
    private final boolean error;

    /**
     * A result answering the given call, carrying the content it is given and not marked as a
     * failure.
     *
     * @param callId the id of the call being answered; may be {@code null}
     * @param name   the name of the tool that produced the result; may be {@code null}
     * @param parts  the result itself; none at all is allowed
     */
    public ToolResultPart(@Nullable String callId, @Nullable String name, ContentPart... parts) {
        this(callId, name, List.of(parts), false, null);
    }

    /**
     * A result with content and a failure flag, carrying no provider-specific fields.
     *
     * @param callId the id of the call being answered; may be {@code null}
     * @param name   the name of the tool that produced the result; may be {@code null}
     * @param parts  the result itself; never {@code null}, empty allowed
     * @param error  whether the tool failed
     */
    public ToolResultPart(@Nullable String callId, @Nullable String name, @NonNull List<ContentPart> parts,
            boolean error) {
        this(callId, name, parts, error, null);
    }

    /**
     * Everything this result can carry. Hand-written, because Lombok cannot generate a constructor
     * that calls a super constructor with arguments.
     *
     * @param callId the id of the call being answered; may be {@code null}
     * @param name   the name of the tool that produced the result; may be {@code null}
     * @param parts  the result itself; never {@code null}, empty allowed
     * @param error  whether the tool failed
     * @param extras provider-specific fields, {@code null} for none; frozen on the way in
     */
    public ToolResultPart(@Nullable String callId, @Nullable String name, @NonNull List<ContentPart> parts,
            boolean error, @Nullable ProviderExtras extras) {
        super(extras);
        this.callId = callId;
        this.name = name;
        this.parts = List.copyOf(parts);
        this.error = error;
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
