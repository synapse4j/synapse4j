package io.github.synapse4j.data;

import org.jspecify.annotations.Nullable;

import lombok.RequiredArgsConstructor;

/**
 * The roles this library supports, as values an adapter can reason about rather than the strings
 * {@link ChatMessage#getRole()} carries.
 *
 * <p>
 * The set is closed — it holds the roles this library defines and nothing else — because a role here
 * is a meaning the library places, not a protocol's spelling. A role of the application's or a
 * protocol's own travels in {@link ChatMessage#getRole()} as the string it is, and an adapter
 * translates its protocol's roles by that protocol's own definition rather than through
 * {@link #from(String)}, which answers for this set alone.
 *
 * <p>
 * The model answers under a single role, {@link #ASSISTANT}, so every other role belongs to the
 * caller's side of the conversation.
 */
@RequiredArgsConstructor
public enum ChatRole {

    /** The end user's messages. */
    USER("user"),

    /** The model's messages — the one role the model answers under. */
    ASSISTANT("assistant"),

    /** The application's own messages: the agent speaking for itself, apart from the end user. */
    DEVELOPER("developer");

    private final String value;

    /**
     * The string this role is carried as in {@link ChatMessage#getRole()}.
     *
     * @return the role's string form
     */
    public String value() {
        return value;
    }

    /**
     * The role the given text names, or {@code null} when it names none of these. A role of the
     * application's or a protocol's own is not refused — it is simply not one of these.
     *
     * @param value the role as {@link ChatMessage#getRole()} carries it
     * @return the role the text names, or {@code null} when it names none of these
     */
    public static @Nullable ChatRole from(@Nullable String value) {
        for (ChatRole role : values()) {
            if (role.value.equals(value)) {
                return role;
            }
        }
        return null;
    }
}
