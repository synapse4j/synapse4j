package io.github.synapse4j.data;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import lombok.Getter;
import lombok.NonNull;

/**
 * A part that holds a group of other parts, in order.
 *
 * <p>
 * It is structure and nothing more: nothing here says what the group means, so that stays the
 * protocol's or the application's to decide.
 *
 * <p>
 * A part is a value: once built it never changes, so it is safe to share across calls and threads.
 * Its parts and its {@link ProviderExtras} bag are frozen on the way in.
 */
@Getter
public class ListPart extends ContentPart {

    /** The parts this one holds, in order. Never {@code null}; empty is allowed. */
    private final List<ContentPart> subParts;

    /**
     * A group of the given parts, carrying no provider-specific fields.
     *
     * @param subParts the parts this one holds; none at all is allowed
     */
    public ListPart(ContentPart... subParts) {
        this(List.of(subParts), null);
    }

    /**
     * A group of the given parts, carrying no provider-specific fields.
     *
     * @param subParts the parts this one holds; never {@code null}, empty allowed
     */
    public ListPart(@NonNull List<ContentPart> subParts) {
        this(subParts, null);
    }

    /**
     * Everything a group can carry. Hand-written, because Lombok cannot generate a constructor that
     * calls a super constructor with arguments.
     *
     * @param subParts the parts this one holds; never {@code null}, empty allowed
     * @param extras   provider-specific fields, {@code null} for none; frozen on the way in
     */
    public ListPart(@NonNull List<ContentPart> subParts, @Nullable ProviderExtras extras) {
        super(extras);
        this.subParts = List.copyOf(subParts);
    }

    @Override
    public String toString() {
        return toString(Map.of("subParts", subParts.toString()));
    }

}
