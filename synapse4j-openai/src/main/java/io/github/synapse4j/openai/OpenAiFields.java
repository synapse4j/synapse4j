package io.github.synapse4j.openai;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Members of the chat-completions wire that this module touches in more than one place — the
 * spellings are the protocol's, held once so a reader and a writer can never drift apart.
 *
 * <p>
 * A holder of constants rather than a data class: it is final, and it cannot be instantiated.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class OpenAiFields {

    /**
     * The chunk member naming which entry of the {@code tool_calls} array a fragment belongs to.
     * The fold keeps it on the assembled call so further fragments can find their way, and the
     * request that replays the call leaves it behind: a request's tool call carries no such
     * member, and sending one would be a field the protocol never asked for.
     */
    static final String TOOL_CALL_INDEX = "index";

}
