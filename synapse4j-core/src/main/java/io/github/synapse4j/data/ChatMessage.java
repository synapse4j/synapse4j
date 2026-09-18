package io.github.synapse4j.data;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;

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
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    /** Who contributes this message: a {@link ChatRole} constant, or any other value. */
    private String role;

    /** What the message contributes. Never {@code null}; empty is allowed. */
    @NonNull
    private List<ContentPart> parts = new ArrayList<>();

    /** Provider-specific fields to merge into this message when the request is sent. */
    private final ProviderExtras extras = new ProviderExtras();

}
