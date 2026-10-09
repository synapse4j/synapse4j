package io.github.synapse4j.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import lombok.Getter;
import lombok.NonNull;

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
 * A part prints as its kind and the fields its subclass states, then, when it carries any, its
 * extras: a subclass with fields of its own answers {@link #toString()} by handing them to
 * {@link #toString(Map)}, stating the ones it has and leaving out the ones it does not.
 */
@Getter
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
     * @param extras provider-specific fields, {@code null} or empty for none; frozen on the way in
     */
    protected ContentPart(@Nullable ProviderExtras extras) {
        this.extras = extras == null || extras.isEmpty() ? null : extras.freeze();
    }

    /**
     * The printout a subclass answers {@link #toString()} with: this part's kind, the fields the
     * subclass states, and last, when the part carries any, its extras. The kind is the subclass's
     * own name, so nothing has to repeat itself, and a field the subclass leaves out stays out.
     *
     * @param members the subclass's own fields, named as they are, in the order to print them; never
     *                    {@code null}
     * @return the printout; never {@code null}
     */
    protected String toString(@NonNull Map<String, String> members) {
        List<String> stated = new ArrayList<>();
        members.forEach((name, value) -> stated.add(name + "=" + value));
        if (extras != null) {
            stated.add("extras=" + extras);
        }
        return getClass().getSimpleName() + "(" + String.join(", ", stated) + ")";
    }

    @Override
    public String toString() {
        return toString(Map.of());
    }

}
