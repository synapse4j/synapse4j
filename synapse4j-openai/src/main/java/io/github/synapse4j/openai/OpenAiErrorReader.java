package io.github.synapse4j.openai;

import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.schema.JsonCodec;
import io.github.synapse4j.schema.JsonView;

/**
 * Turns a non-2xx response body into the single {@code SynapseException} this module throws for any
 * refused or failed call.
 *
 * <p>
 * The provider decides what is an error: any non-2xx reaches this reader, and whatever structured
 * error body came back is folded into the exception message. A body that does not parse is not a
 * second failure — the status and a snippet of the raw body are enough to diagnose. This reader
 * never lets an exception of its own escape: whatever arrives, the caller gets one
 * {@code SynapseException} describing the failed call.
 */
class OpenAiErrorReader {

    /** Raw-body snippet kept in the message when the error body is not parseable JSON. */
    private static final int SNIPPET_LIMIT = 500;

    private final JsonCodec codec;

    OpenAiErrorReader(JsonCodec codec) {
        this.codec = codec;
    }

    SynapseException read(int status, String body) {
        try {
            JsonView root = codec.decode(body, JsonView.class);
            JsonView error = root.get("error");
            if (error.isObject()) {
                return structured(status, error);
            }
        } catch (RuntimeException ignored) {
            // Not decodable as the provider's error shape; fall through to the raw body below.
        }
        return new SynapseException("OpenAI request failed with HTTP " + status + ": " + snippet(body));
    }

    private SynapseException structured(int status, JsonView error) {
        StringBuilder message = new StringBuilder("OpenAI request failed with HTTP ").append(status);
        String detail = error.get("message").asText();
        if (detail != null) {
            message.append(": ").append(detail);
        }
        String type = error.get("type").asText();
        if (type != null) {
            message.append(" [").append(type).append("]");
        }
        String code = error.get("code").asText();
        if (code != null) {
            message.append(" (").append(code).append(")");
        }
        return new SynapseException(message.toString());
    }

    private static String snippet(String raw) {
        // One long line (a proxy's HTML, say) would otherwise fill the log; a prefix is enough.
        return raw.length() <= SNIPPET_LIMIT ? raw : raw.substring(0, SNIPPET_LIMIT) + "...";
    }

}
