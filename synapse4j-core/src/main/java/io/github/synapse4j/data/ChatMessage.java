package io.github.synapse4j.data;

import java.util.List;
import java.util.function.UnaryOperator;

import org.jspecify.annotations.Nullable;

import lombok.Builder;
import lombok.Generated;
import lombok.Getter;
import lombok.NonNull;
import lombok.Singular;

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
 *
 * <p>
 * A message is a value: once built it never changes, so it is safe to share across calls and threads.
 * Its parts and its {@link ProviderExtras} bag are frozen on the way in, and its own fields are
 * nullable the way a part's are, because the shared model assumes nothing about what a protocol
 * requires.
 */
@Getter
@Builder(builderClassName = "Builder", toBuilder = true)
public class ChatMessage {

    /** Who contributes this message: a {@link ChatRole} constant, or any other value. */
    private final @Nullable String role;

    /**
     * An identifier for this message, owned by the application — whatever value its persistence
     * needs to tell one message from another across calls; {@code null} when it has none.
     */
    private final @Nullable String id;

    /** What the message contributes. Never {@code null}; empty is allowed. */
    @Singular
    private final List<ContentPart> parts;

    /**
     * Provider-specific fields to merge into this message when the request is sent, frozen; {@code null}
     * when the message carries none. A message nobody configures carries no bag at all.
     */
    private final @Nullable ProviderExtras extras;

    /**
     * A message from the given speaker, saying whatever the given parts say.
     *
     * @param role  the {@link ChatRole} constant, or any other value; {@code null} leaves the role unset
     * @param id    the application's identifier for this message, {@code null} when it has none
     * @param parts what the message contributes; none at all is allowed
     */
    public ChatMessage(@Nullable String role, @Nullable String id, ContentPart... parts) {
        this(role, id, List.of(parts), null);
    }

    /**
     * A message from the given speaker, saying what the given parts say.
     *
     * @param role  the {@link ChatRole} constant, or any other value; {@code null} leaves the role unset
     * @param id    the application's identifier for this message, {@code null} when it has none
     * @param parts what the message contributes; never {@code null}, empty allowed
     */
    public ChatMessage(@Nullable String role, @Nullable String id, @NonNull List<? extends ContentPart> parts) {
        this(role, id, parts, null);
    }

    /**
     * Everything a message can carry. Hand-written, because varargs cannot follow the extras.
     *
     * @param role   the {@link ChatRole} constant, or any other value; {@code null} leaves the role unset
     * @param id     the application's identifier for this message, {@code null} when it has none
     * @param parts  what the message contributes; never {@code null}, empty allowed
     * @param extras provider-specific fields, {@code null} or empty for none; frozen on the way in
     */
    public ChatMessage(@Nullable String role, @Nullable String id, @NonNull List<? extends ContentPart> parts,
            @Nullable ProviderExtras extras) {
        this.role = role;
        this.id = id;
        this.parts = List.copyOf(parts);
        this.extras = extras == null || extras.isEmpty() ? null : extras.freeze();
    }

    /**
     * A message from the system saying the given text — the instructions a conversation usually opens
     * with.
     *
     * @param text what the message says
     * @return the message
     */
    public static ChatMessage system(@NonNull String text) {
        return new ChatMessage(ChatRole.SYSTEM, null, new TextPart(text));
    }

    /**
     * A message from the user saying the given text — the shape most of a conversation is built from.
     *
     * @param text what the message says
     * @return the message
     */
    public static ChatMessage user(@NonNull String text) {
        return new ChatMessage(ChatRole.USER, null, new TextPart(text));
    }

    /**
     * A message from the assistant saying the given text: a model's turn written back by hand, for a
     * replayed exchange or the examples of a few-shot prompt.
     *
     * @param text what the message says
     * @return the message
     */
    public static ChatMessage assistant(@NonNull String text) {
        return new ChatMessage(ChatRole.ASSISTANT, null, new TextPart(text));
    }

    /**
     * A message from the given speaker, carrying the given parts.
     *
     * @param role  the {@link ChatRole} constant, or any other value; {@code null} leaves the role unset
     * @param parts what the message contributes; none at all is allowed
     * @return the message
     */
    public static ChatMessage of(@Nullable String role, ContentPart... parts) {
        return new ChatMessage(role, null, parts);
    }

    /**
     * The text this message says: every {@link TextPart}'s text, joined in the order the parts
     * appear, with the parts of every other kind left out.
     *
     * <p>
     * No separator is inserted — the parts are consecutive pieces of one piece of writing — and a
     * part whose text is {@code null} contributes nothing. A message that says nothing in text, a
     * tool-call-only turn for instance, answers the empty string rather than {@code null}.
     *
     * @return the message's text; never {@code null}, empty when it has none
     */
    public String getText() {
        StringBuilder text = new StringBuilder();
        for (ContentPart part : parts) {
            if (part instanceof TextPart textPart && textPart.getText() != null) {
                text.append(textPart.getText());
            }
        }
        return text.toString();
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

    /**
     * Assembles a message, or a changed copy of one.
     *
     * <p>
     * Declared here for one reason: to hold {@link #mapExtras}, which Lombok cannot add to a builder
     * it writes itself. Every other member is still Lombok's, so the class carries the marker Lombok
     * puts on a builder it writes whole — without it NullAway reads the generated no-argument
     * constructor as leaving every field uninitialized, since they are assigned through the setters
     * instead.
     */
    @Generated
    public static class Builder {

        /**
         * Hands this builder's extras bag to the given function and takes back the bag it returns, so
         * a caller adjusts a bag that is already there — the one a {@code toBuilder()} carried over,
         * say — instead of assembling a replacement by hand.
         *
         * <p>
         * The function receives a mutable bag: the one already here when it is mutable, otherwise a
         * fresh copy of it, or an empty bag when there is none. It may change that instance and return
         * it, or return one of its own. Whatever it returns becomes this builder's bag, so returning
         * {@code null} leaves the message with none.
         *
         * @param mutate the function to apply; must not be {@code null}
         * @return this builder
         */
        public Builder mapExtras(@NonNull UnaryOperator<ProviderExtras> mutate) {
            if (extras == null || extras.isFrozen()) {
                extras = ProviderExtras.merged(extras, null);
            }
            extras = mutate.apply(extras);
            return this;
        }

    }

}
