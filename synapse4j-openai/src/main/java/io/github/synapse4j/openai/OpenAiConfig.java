package io.github.synapse4j.openai;

import lombok.Data;

/**
 * Configuration of the OpenAI family: where the API lives and how the caller authenticates.
 *
 * <p>
 * This is family-level configuration, shared by the chat-completions adapter implemented now and the
 * Responses adapter to come: both speak to the same API with the same credentials, so neither owns
 * a config of its own.
 */
@Data
public class OpenAiConfig {

    /** Base URL of the API, without a trailing path. Defaults to OpenAI's hosted endpoint. */
    private String baseUrl = "https://api.openai.com/v1";

    /** The API key, sent as {@code Authorization: Bearer}. */
    private String apiKey;

    /** Optional {@code OpenAI-Organization} header value. */
    private String organization;

    /** Optional {@code OpenAI-Project} header value. */
    private String project;

}
