package io.github.synapse4j.data;

import org.jspecify.annotations.Nullable;

/**
 * A value that answers a copy of itself and takes what another instance of its declared type states:
 * what the receiver states stands, and the base fills in what it leaves unset. What counts as unset is
 * the type's own to define — typically {@code null}, an empty collection, or something it cannot
 * identify.
 *
 * <p>
 * The operation is an instance method rather than a static helper on the type because the kind has to
 * survive: a caller holds these typed as the declared type and cannot tell or build the subclass the
 * caller actually passed — only the value knows its own kind. A static
 * {@code ChatOptions.effective(a, b)} would answer a plain {@code ChatOptions} and drop whatever a
 * subclass carried. So the type parameter is the declaring type, {@link #fillFrom} takes it too, and a
 * subclass carrying state of its own overrides {@link #copy()} and {@link #fillFrom} to carry that
 * state: {@code copy()} answers its own kind, and {@code fillFrom} takes what a base of that kind
 * states after {@code super.fillFrom(base)} has taken the fields they share.
 */
public interface Effective<T extends Effective<T>> {

    /**
     * A new instance of this kind holding the values this one holds.
     *
     * @return the copy; never {@code null}
     */
    T copy();

    /**
     * Takes into this instance what {@code base} states and this one leaves unset, in place. A value
     * this instance already states stands.
     *
     * @param base the side to fill the gaps from; never {@code null}
     */
    void fillFrom(T base);

    /**
     * A new instance of this kind holding what this one holds, with {@code base}'s values filling in
     * what this one leaves unset. A {@code null} base is the plain case: a copy of this instance.
     *
     * @param base the side to fill the gaps from, or {@code null} for none
     * @return the filled instance; never {@code null}
     */
    default T effective(@Nullable T base) {
        T filled = copy();
        if (base != null) {
            filled.fillFrom(base);
        }
        return filled;
    }
}
