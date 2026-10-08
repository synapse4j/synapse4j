package io.github.synapse4j.anthropic;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.github.synapse4j.data.ContentPart;
import io.github.synapse4j.data.ProviderExtras;
import lombok.Getter;
import lombok.NonNull;

/**
 * A content block this module does not model — a server tool's call, a search result — kept whole
 * as a part of the turn.
 *
 * <p>
 * The shared model has no shape for it, but its words have to arrive, travel with the conversation
 * when it continues, and go back out as the block they came in as. Holding it as a part is what
 * makes that round trip structural: the part sits in the list where the block sat in the content
 * array, so the writer spells it back at the same place without knowing what it is — whether the
 * block was read whole or assembled from a stream.
 *
 * <p>
 * The members are the block exactly as it was read, keyed by the protocol's own names: this type
 * adds nothing to them and nothing may be added behind the protocol's back.
 *
 * <p>
 * A block is a value: once built it never changes, so it is safe to share across calls and threads.
 * Its members and its {@link ProviderExtras} bag are frozen on the way in; the members are copied
 * shallowly, so a value the caller still holds and later changes is seen through the block too.
 */
@Getter
public class RawContentBlock extends ContentPart {

    /** The block as it came off the wire, keyed by the protocol's own member names. */
    private final Map<String, Object> members;

    /**
     * A block kept whole.
     *
     * @param block the captured block; never {@code null}, always a JSON object
     */
    public RawContentBlock(@NonNull Map<?, ?> block) {
        this(block, null);
    }

    /**
     * A block kept whole, with provider-specific fields.
     *
     * @param block  the captured block; never {@code null}, always a JSON object
     * @param extras provider-specific fields, {@code null} for none; frozen on the way in
     */
    public RawContentBlock(@NonNull Map<?, ?> block, @Nullable ProviderExtras extras) {
        super(extras);
        // A copy, not the caller's map: the members are frozen with the block, and the map the
        // reader handed over belongs to the reader.
        Map<String, Object> copy = new LinkedHashMap<>();
        block.forEach((key, value) -> copy.put(String.valueOf(key), value));
        this.members = Collections.unmodifiableMap(copy);
    }

    /**
     * The block printed by its kind rather than its content — the content can be a whole search
     * result, and a printout of it would be the answer rather than a line about it.
     *
     * @return this block, named by its kind
     */
    @Override
    public String toString() {
        Object type = members.get("type");
        return "RawContentBlock[" + (type instanceof String kind ? kind : "?") + "]";
    }

}
