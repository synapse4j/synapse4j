package io.github.synapse4j.data;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

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
 * A message owns its {@link ProviderExtras} for the same reason a part does — a provider-specific
 * field belongs to the thing it applies to, not to the whole request. Each class declares its own
 * instead of inheriting one from a common base: no library surveyed models a universal node base
 * class, and the cost of not having one is a single field per class.
 *
 * <p>
 * A message is a value: once built it never changes, so it is safe to share across calls and threads.
 * Its parts and its {@link ProviderExtras} are frozen on the way in, and its own fields are
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
     * when the message carries none. A message nobody configures carries none at all.
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
     * A message from the user saying the given text — the shape most of a conversation is built from.
     *
     * @param text what the message says
     * @return the message
     */
    public static ChatMessage user(@NonNull String text) {
        return new ChatMessage(ChatRole.USER.value(), null, new TextPart(text));
    }

    /**
     * A message from the assistant saying the given text: a model's turn written back by hand, for a
     * replayed exchange or the examples of a few-shot prompt.
     *
     * @param text what the message says
     * @return the message
     */
    public static ChatMessage assistant(@NonNull String text) {
        return new ChatMessage(ChatRole.ASSISTANT.value(), null, new TextPart(text));
    }

    /**
     * A message from the application saying the given text — the agent speaking for itself, apart
     * from the end user.
     *
     * @param text what the message says
     * @return the message
     */
    public static ChatMessage developer(@NonNull String text) {
        return new ChatMessage(ChatRole.DEVELOPER.value(), null, new TextPart(text));
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
     * Walks this message's parts and every part below them, the message's own parts first, and
     * answers the parts the walk reaches.
     *
     * <p>
     * A part is always handed over; the predicate decides only whether the walk opens it, so a part
     * the predicate refuses appears without the parts it contains. Refusing a tool result, say,
     * reaches the message's own content and leaves the tool's answer behind.
     *
     * @param descend which parts to open; must not be {@code null}
     * @return the parts reached, in document order; never {@code null}
     */
    public Stream<ContentPart> walk(@NonNull Predicate<ContentPart> descend) {
        return parts.stream().flatMap(part -> walk(part, descend));
    }

    /**
     * Walks this message's parts and every part below them, opening every part.
     *
     * @return the parts reached, in document order; never {@code null}
     */
    public Stream<ContentPart> walk() {
        return walk(part -> true);
    }

    /**
     * Walks this message's parts and every part below them, opening every part, and answers the ones
     * of the given type.
     *
     * <p>
     * Every part is opened, as {@link #walk()}: a part of another type is passed over, not a place the
     * walk stops. The type may be any kind of part — one this library defines, or one a provider or an
     * application adds.
     *
     * @param type the type of part to answer; must not be {@code null}
     * @param <T>  that type
     * @return the parts of that type, in document order; never {@code null}
     */
    public <T extends ContentPart> Stream<T> walk(@NonNull Class<T> type) {
        return walk().filter(type::isInstance).map(type::cast);
    }

    private static Stream<ContentPart> walk(ContentPart part, Predicate<ContentPart> descend) {
        Stream<ContentPart> stream = Stream.of(part);
        if (!descend.test(part)) {
            return stream;
        }
        return Stream.concat(stream, part.getSubParts().stream().flatMap(sub -> walk(sub, descend)));
    }

    /**
     * The text this message says: every {@link TextPart}'s text at any depth, joined in the order
     * the parts appear, with the parts of every other kind left out.
     *
     * <p>
     * The walk opens every part, so text a part holds is included — a tool result's answer, for
     * instance. A caller that wants only what the message says itself walks with
     * {@link #walk(Predicate)} and reads the {@link TextPart}s it reaches.
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
        walk().forEach(part -> {
            if (part instanceof TextPart textPart && textPart.getText() != null) {
                text.append(textPart.getText());
            }
        });
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
         * Hands this builder's extras to the given function and takes back the instance it returns,
         * so a caller adjusts an instance that is already there — the one a {@code toBuilder()}
         * carried over, say — instead of assembling a replacement by hand.
         *
         * <p>
         * The function receives a mutable instance: the one already here when it is mutable, otherwise
         * a fresh copy of it, or an empty one when there is none. It may change that instance and
         * return it, or return one of its own. Whatever it returns becomes this builder's extras, so
         * returning {@code null} leaves the message with none.
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
