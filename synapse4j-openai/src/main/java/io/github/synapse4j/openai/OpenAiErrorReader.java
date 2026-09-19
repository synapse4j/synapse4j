package io.github.synapse4j.openai;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.openai.wire.WireError;
import tools.jackson.core.JacksonException;

/**
 * Turns a non-2xx response body into the single {@code SynapseException} this module throws for any
 * refused or failed call.
 *
 * <p>
 * The provider decides what is an error: any non-2xx reaches this reader, and whatever structured
 * error body came back is folded into the exception message. A body that does not parse is not a
 * second failure — the status and a snippet of the raw body are enough to diagnose.
 */
class OpenAiErrorReader {

    /** Raw-body snippet kept in the message when the error body is not parseable JSON. */
    private static final int SNIPPET_LIMIT = 500;

    private final tools.jackson.databind.json.JsonMapper mapper;

    OpenAiErrorReader(tools.jackson.databind.json.JsonMapper mapper) {
        this.mapper = mapper;
    }

    SynapseException read(int status, InputStream body) {
        String raw;
        try {
            raw = new String(body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SynapseException("OpenAI request failed with HTTP " + status
                    + ", and the error body could not be read", e);
        }
        try {
            WireError wire = mapper.readValue(raw, WireError.class);
            if (wire != null && wire.getError() != null) {
                return structured(status, wire.getError());
            }
        } catch (JacksonException ignored) {
            // Not the provider's error shape; fall through to the raw body below.
        }
        return new SynapseException("OpenAI request failed with HTTP " + status + ": " + snippet(raw));
    }

    private SynapseException structured(int status, WireError.ErrorBody error) {
        StringBuilder message = new StringBuilder("OpenAI request failed with HTTP ").append(status);
        if (error.getMessage() != null) {
            message.append(": ").append(error.getMessage());
        }
        if (error.getType() != null) {
            message.append(" [").append(error.getType()).append("]");
        }
        if (error.getCode() != null) {
            message.append(" (").append(error.getCode()).append(")");
        }
        return new SynapseException(message.toString());
    }

    private static String snippet(String raw) {
        // One long line (a proxy's HTML, say) would otherwise fill the log; a prefix is enough.
        return raw.length() <= SNIPPET_LIMIT ? raw : raw.substring(0, SNIPPET_LIMIT) + "...";
    }

}
