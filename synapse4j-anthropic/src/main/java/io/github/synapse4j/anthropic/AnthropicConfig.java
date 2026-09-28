package io.github.synapse4j.anthropic;

import org.jspecify.annotations.Nullable;

import lombok.Data;
import lombok.NonNull;
import lombok.ToString;

/**
 * Configuration of the Anthropic Messages API: where it lives, how the caller authenticates, and
 * which protocol version the request declares.
 *
 * <p>
 * There is deliberately no knob for the members whose spelling this protocol fixes — the token
 * limit is always {@code max_tokens}, reasoning travels in blocks rather than under a configurable
 * member name — so unlike the OpenAI family configuration this one carries no field-name settings.
 * A field one request needs beyond these goes in the call's own {@code ProviderExtras} bag or
 * headers, where every escape hatch in this library lives.
 */
@Data
public class AnthropicConfig {

    /** Base URL of the API, without a trailing path. Defaults to Anthropic's hosted endpoint. */
    @NonNull
    private String baseUrl = "https://api.anthropic.com";

    /**
     * The API key, sent as {@code x-api-key}. Kept out of {@code toString()}: a credential belongs
     * in the header it authenticates, not in a log line or an error message.
     */
    @ToString.Exclude
    private @Nullable String apiKey;

    /**
     * The value of the {@code anthropic-version} header, the protocol version the request declares.
     * The endpoint answers only to versions it knows, so the default is the one the documentation
     * sends and a caller moving to a newer version says so here rather than in per-call headers.
     */
    @NonNull
    private String anthropicVersion = "2023-06-01";

}
