package io.github.synapse4j.chat;

import io.github.synapse4j.data.ChatMessage;
import io.github.synapse4j.data.ChatRequest;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Gives a request that carries no system message one saying a fixed text, so every call runs under
 * the same framing without each call stating it.
 *
 * <p>
 * A request that states a system message of its own is left alone: the standing one fills a gap, it
 * never overrides a caller. The message is built fresh for each request that needs one, so no two
 * requests share the instance and nothing one conversation does to its framing reaches another.
 *
 * <p>
 * Nothing registers this on a client by itself. An application adds it through
 * {@link ChatClient#addChatCustomizer(ChatCustomizer)}, and a framework may wire one on its behalf.
 */
@RequiredArgsConstructor
public class DefaultSystemMessageCustomizer implements ChatCustomizer {

    /** What the supplied system message says. */
    @NonNull
    private final String text;

    @Override
    public void customizeRequest(ChatClient client, ChatRequest request) {
        if (request.getSystemMessage() == null) {
            request.setSystemMessage(ChatMessage.system(text));
        }
    }

}
