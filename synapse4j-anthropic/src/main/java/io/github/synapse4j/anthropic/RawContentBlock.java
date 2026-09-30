package io.github.synapse4j.anthropic;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.synapse4j.data.ContentPart;

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
        // A copy, not the caller's map: a streamed block is completed later, member by member, and
        // the map the reader handed over belongs to the reader — one built on an immutable tree
        // would refuse that write.
        Map<String, Object> copy = new LinkedHashMap<>();
        block.forEach((key, value) -> copy.put(String.valueOf(key), value));
        this.members = copy;
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
