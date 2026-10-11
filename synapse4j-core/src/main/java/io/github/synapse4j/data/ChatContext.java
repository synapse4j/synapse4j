package io.github.synapse4j.data;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import lombok.Getter;
import lombok.Setter;

/**
 * What travels alongside a call to tie it to a conversation: who the conversation is, and —
 * for the length of one exchange — where that exchange stands.
 *
 * <p>
 * The library writes every member here except the attributes, and only the current exchange is its
 * business: no history accumulates.
 *
 * <p>
 * An instance carries one exchange at a time: the library's fields are overwritten in place,
 * never guarded, so two calls in flight on one context let them race. The library keeps none
 * of this between calls — a context rides in with the request and is handed back with the
 * answer. Holding one across rounds is the application's affair.
 */
@Getter
@Setter
public class ChatContext {

    /**
     * The conversation's identifier, opaque to this library; {@code null} until one is known.
     * The application sets it, and the library writes it too — a provider's id is adopted
     * during the exchange, but only while this one is still empty, so the caller's wins.
     */
    private @Nullable String sessionId;

    /**
     * Which interaction of the current round this exchange is on, counting from 1; {@code 0}
     * until something counts. Written only by the tool-calling loop, which resets it at each
     * round's start; a {@code ChatClient} never touches it, so on a bare call whatever the
     * application set stands — trusted as it is.
     */
    private int turn;

    /**
     * The request as it went out — the one after every customizer ran — recorded by the client
     * before sending and overwritten on every call; never a history of what was sent.
     */
    private @Nullable ChatRequest request;

    /**
     * The most recent response, recorded by the client when it arrives and overwritten on every
     * call.
     */
    private @Nullable ChatResponse response;

    /**
     * The application's own in-process entries for this conversation, for data that must not go out.
     * Never {@code null}; the library never reads or writes it, and nothing in it ever reaches the
     * wire.
     */
    private final Map<String, Object> attributes = new LinkedHashMap<>();

    /**
     * The session id, the turn and the attribute count are printed; the attributes themselves
     * and the exchange's request and response are not.
     *
     * @return this context, in brief
     */
    @Override
    public String toString() {
        return "ChatContext(sessionId=" + sessionId + ", turn=" + turn + ", attributes=" + attributes.size() + ')';
    }

}
