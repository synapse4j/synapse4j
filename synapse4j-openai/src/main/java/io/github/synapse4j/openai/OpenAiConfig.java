package io.github.synapse4j.openai;

import org.jspecify.annotations.Nullable;

import lombok.Data;
import lombok.NonNull;
import lombok.ToString;

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
    @NonNull
    private String baseUrl = "https://api.openai.com/v1";

    /**
     * The API key, sent as {@code Authorization: Bearer}. Kept out of {@code toString()}: a
     * credential belongs in the header it authenticates, not in a log line or an error message.
     */
    @ToString.Exclude
    private @Nullable String apiKey;

    /** Optional {@code OpenAI-Organization} header value. */
    private @Nullable String organization;

    /** Optional {@code OpenAI-Project} header value. */
    private @Nullable String project;

    /**
     * The member the token limit goes out as, since endpoints disagree about its name: the modern
     * {@code max_completion_tokens}, which the official API's current models answer to, or the
     * legacy {@code max_tokens}, which older models and many compatible servers answer to. Blank
     * means the limit is not sent at all.
     *
     * <p>
     * It is read and written as one name rather than inferred from a response: a limit is only ever
     * sent, so there is nothing to infer it from, and a wrong guess is a member the endpoint ignores
     * or refuses.
     */
    @NonNull
    private String maxTokensField = "max_completion_tokens";

    /**
     * The member the model's reasoning travels under, read and written alike. Endpoints disagree
     * about its name — {@code reasoning_content} is the one the providers that require it back use,
     * {@code reasoning} the one the newer servers settled on — and a name is one thing, not two: a
     * conversation that reads one spelling and writes another renames a member the endpoint never
     * sent. Blank means reasoning is neither read nor sent.
     *
     * <p>
     * A response that carries reasoning under some other name is not a failure: that member stays in
     * the message's extras, under its own name, where an application can see it and change this
     * setting.
     */
    @NonNull
    private String reasoningField = "reasoning_content";

}
