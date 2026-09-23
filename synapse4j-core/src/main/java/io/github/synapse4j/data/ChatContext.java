package io.github.synapse4j.data;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;

/**
 * What travels alongside a call to tie it to a conversation: a session id, and anything else the
 * application wants carried.
 *
 * <p>
 * {@link #sessionId} is the only member the library touches: when a provider reports a session id
 * of its own during an exchange, it is written here — but only into a context whose session id is
 * still empty, so a value the caller set always wins. The id is a plain string because the formats
 * are the providers' own (a UUID, a prefixed key, a cache token) and none of them is ever parsed
 * here.
 *
 * <p>
 * The attributes belong to the application alone: the library and its providers never read or
 * write them, and nothing in them ever reaches the wire. The extras bags exist to be serialized;
 * this map is their opposite — an in-process lane for data that must not go out.
 *
 * <p>
 * The library keeps none of this between calls: a context rides in with the request and is handed
 * back with the answer. Holding one across rounds — a single instance per conversation, a store
 * keyed by session id, anything else — is the application's affair.
 */
@Getter
@Setter
public class ChatContext {

    /**
     * The conversation's identifier, opaque to this library; {@code null} until one is known. The
     * application sets it, or a provider reports it during an exchange.
     */
    private String sessionId;

    /**
     * The application's own entries for this conversation. Never {@code null}; the library never
     * reads or writes it.
     */
    private final Map<String, Object> attributes = new LinkedHashMap<>();

    /**
     * The session id and the attribute count are printed; the attributes themselves carry the
     * application's data and are not.
     *
     * @return this context, in brief
     */
    @Override
    public String toString() {
        return "ChatContext(sessionId=" + sessionId + ", attributes=" + attributes.size() + ')';
    }

}
