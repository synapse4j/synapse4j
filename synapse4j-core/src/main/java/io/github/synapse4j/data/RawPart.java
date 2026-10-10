package io.github.synapse4j.data;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import lombok.NonNull;

/**
 * A content part this library does not model — a server tool's call, a search result, a payload of
 * a kind of its own — kept whole as a part of the turn.
 *
 * <p>
 * The shared model has no shape for it, but its words have to arrive, travel with the conversation
 * when it continues, and go back out as the element they came in as. Holding it as a part is what
 * makes that round trip structural: the part sits in the list where the element sat in the content
 * array, so a writer spells it back at the same place without knowing what it is — whether the
 * element was read whole or assembled from a stream.
 *
 * <p>
 * Its members are the element exactly as it was read, keyed by the protocol's own names, and they
 * are the whole of it: there are no fields beside them, so writing the {@link #getExtras() extras}
 * back writes the part. A JSON object names its members with strings, so the keys are strings and
 * nothing else — what this part holds is what the protocol spelled, and nothing may be added behind
 * its back.
 *
 * <p>
 * A part is a value: once built it never changes, so it is safe to share across calls and threads.
 * The members are copied shallowly, so a value the caller still holds and later changes is seen
 * through the part too.
 */
public class RawPart extends ContentPart {

    /**
     * A part kept whole.
     *
     * @param members the captured element, keyed by the protocol's own member names; never
     *                    {@code null}
     */
    public RawPart(@NonNull Map<String, Object> members) {
        this(toExtras(members));
    }

    /**
     * A part kept whole, from the members already gathered in the extras this part carries — for a
     * caller that read the element into an instance rather than a map, and has no reason to spell it
     * out again.
     *
     * @param members the captured element, keyed by the protocol's own member names; never
     *                    {@code null}
     */
    public RawPart(@NonNull ProviderExtras members) {
        super(members);
    }

    /**
     * A part kept whole, from an element read as a plain map — the shape a JSON reader answers for an
     * object. A member name is always a string, so a key that is not one is refused here rather than
     * written back under a name the protocol never spelled.
     *
     * @param members the captured element; never {@code null}, always a JSON object
     * @return the part; never {@code null}
     * @throws IllegalArgumentException if a key is not a {@code String}
     */
    public static RawPart of(@NonNull Map<?, ?> members) {
        return new RawPart(toExtras(members));
    }

    private static ProviderExtras toExtras(Map<?, ?> members) {
        ProviderExtras extras = new ProviderExtras();
        members.forEach((name, value) -> extras.put(memberName(name), value));
        return extras;
    }

    /** The given key as the member name it has to be. */
    private static String memberName(@Nullable Object key) {
        if (key instanceof String name) {
            return name;
        }
        throw new IllegalArgumentException("a content member is named by a string, not by " + key);
    }

    /**
     * The element this part holds, keyed by the protocol's own member names — the extras as the
     * object they stand for.
     *
     * @return the members; never {@code null}, empty when the element carried none
     */
    public Map<String, Object> members() {
        ProviderExtras extras = getExtras();
        return extras == null ? Map.of() : extras.nestedMap();
    }

}
