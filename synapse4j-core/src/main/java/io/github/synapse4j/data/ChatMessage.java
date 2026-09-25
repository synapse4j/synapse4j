package io.github.synapse4j.data;

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One turn of a conversation: who is speaking, and what they contribute.
 *
 * <p>
 * The role is an open value — a plain string, with the well-known ones in {@link ChatRole} — and the
 * parts are the open family described by {@link ContentPart}. Both are open for the same reason: the
 * model must not need a change to express something a protocol or an application already does.
 *
 * <p>
 * A message owns its {@link ProviderExtras} bag for the same reason a part does — a provider-specific
 * field belongs to the thing it applies to, not to the whole request. Each class declares its own bag
 * instead of inheriting one from a common base: no library surveyed models a universal node base
 * class, and the cost of not having one is a single field per class.
 */
@Getter
@Setter
@NoArgsConstructor
public class ChatMessage {

    /** Who contributes this message: a {@link ChatRole} constant, or any other value. */
    private String role;

    /** What the message contributes. Never {@code null}; empty is allowed. */
    private final List<ContentPart> parts = new ArrayList<>();

    /**
     * Provider-specific fields to merge into this message when the request is sent. Absent until one
     * is set: a message nobody configures carries no bag at all.
     */
    private ProviderExtras extras;

    /**
     * The extras bag, created on first use — never {@code null}, unlike {@code getExtras()}.
     * Only a node about to record something allocates; a message nobody configures still
     * carries no bag until this is called.
     *
     * @return this message's extras, existing or fresh; never {@code null}
     */
    public ProviderExtras getOrCreateExtras() {
        if (extras == null) {
            extras = new ProviderExtras();
        }
        return extras;
    }

    /**
     * A message from the given speaker, with nothing said yet.
     *
     * @param role the {@link ChatRole} constant, or any other value
     */
    public ChatMessage(String role) {
        this.role = role;
    }

    /**
     * Adds a part to what this message contributes.
     *
     * @param part the part to add
     * @return this message
     */
    public ChatMessage addPart(ContentPart part) {
        parts.add(part);
        return this;
    }

    /**
     * The parts are counted rather than printed: they carry the content, and a printout that carried
     * it would be as long as the message.
     *
     * @return this message, in brief
     */
    @Override
    public String toString() {
        return "ChatMessage(role=" + role + ", parts=" + parts.size() + ", extras=" + extras + ')';
    }

}
