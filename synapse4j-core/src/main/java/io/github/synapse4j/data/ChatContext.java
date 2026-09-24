package io.github.synapse4j.data;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;

/**
 * What travels alongside a call to tie it to a conversation: who the conversation is, and —
 * for the length of one exchange — where that exchange stands.
 *
 * <p>
 * The library writes every member here except the attributes. A provider-reported session id
 * is adopted into {@link #sessionId} — but only while the id is still empty, so a value the
 * application set always wins; the formats are the providers' own (a UUID, a prefixed key, a
 * cache token) and none of them is ever parsed here. {@link #request} and {@link #response}
 * record the exchange as it went out and came back, overwriting whatever an earlier call
 * left: only the current exchange is this object's business, and no history accumulates
 * here. {@link #turn} belongs to the tool-calling loop alone, which resets it at each round's
 * start; a client on its own never reads or writes it, so on a bare call whatever the
 * application set stands.
 *
 * <p>
 * The attributes belong to the application alone: the library never reads or writes them, and
 * nothing in them ever reaches the wire. The extras bags exist to be serialized; this map is
 * their opposite — an in-process lane for data that must not go out.
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
    private String sessionId;

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
    private ChatRequest request;

    /**
     * The most recent response, recorded by the client when it arrives and overwritten on every
     * call.
     */
    private ChatResponse response;

    /**
     * The application's own entries for this conversation. Never {@code null}; the library never
     * reads or writes it.
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
