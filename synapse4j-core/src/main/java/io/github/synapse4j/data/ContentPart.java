package io.github.synapse4j.data;

import org.jspecify.annotations.Nullable;

import lombok.Getter;
import lombok.ToString;

/**
 * One element of a message's content: a piece of text, the model's reasoning, a tool call, a tool
 * result or a media payload.
 *
 * <p>
 * This class is abstract, and deliberately neither {@code final} nor {@code sealed}: adding a part
 * type for content this library does not model is how a provider module — or an application —
 * extends the model without touching it.
 *
 * <p>
 * A part is a value: once built it never changes, so it is safe to share across calls and threads.
 * The {@link ProviderExtras} bag it carries, when it carries one, is frozen on the way in, so its
 * entries cannot change after the part is built; the values in them are held by reference, as
 * everywhere in this library, so a value the caller still holds and later changes is seen through
 * the part too.
 *
 * <p>
 * A subclass's own fields are nullable. The shared model cannot tell what a protocol requires, so it
 * assumes nothing: a field is non-null only where a protocol's own shape demands it, and a null one
 * is not the same as the part carrying nothing. {@link ReasoningPart} is the case in point — its
 * text is absent when the reasoning is opaque, while its extras bag holds the companion the provider
 * needs back. A part is read through its extras as much as through its fields.
 *
 * <p>
 * Subclasses must pass {@code callSuper = true} to {@code @ToString}, so a part's printout carries
 * the inherited {@code extras} along with its own fields.
 */
@Getter
@ToString
public abstract class ContentPart {

    /**
     * Provider-specific fields to merge into this part when the request is sent, frozen; {@code null}
     * when the part carries none. A part nobody configures carries no bag at all.
     */
    private final @Nullable ProviderExtras extras;

    /** A part carrying no provider-specific fields. */
    protected ContentPart() {
        this(null);
    }

    /**
     * @param extras provider-specific fields to merge into this part when the request is sent,
     *                   {@code null} or empty for none; frozen on the way in
     */
    protected ContentPart(@Nullable ProviderExtras extras) {
        this.extras = extras == null || extras.isEmpty() ? null : extras.freeze();
    }

}
