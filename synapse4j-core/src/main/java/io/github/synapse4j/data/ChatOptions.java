package io.github.synapse4j.data;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Data;

/**
 * The configuration of one call: which model, how to tune it, and the escape hatches.
 *
 * <p>
 * Every knob is optional and expressed by a wrapper type, so {@code null} means "no opinion". An
 * adapter leaves a field without an opinion out of the payload entirely, which is what lets the
 * provider apply its own default — there is nothing to copy and nothing to send.
 *
 * <p>
 * A caller fills in only what this call should differ in; combining it with the client's defaults is
 * the client's business. The two bags are the exception to the per-field story: they are never
 * replaced, only filled.
 *
 * <p>
 * Only knobs at least two providers agree on, and that an adapter knows how to place, belong here. A
 * field one provider alone has — its thinking budget, its cache markers — goes in the
 * {@link ProviderExtras} bag instead: modelling it as a shared knob would silently do nothing on the
 * providers that do not have it.
 */
@Data
public class ChatOptions {

    /** Identifier of the model to call. */
    private String model;

    /** Sampling temperature. */
    private Double temperature;

    /** Upper bound on the tokens generated, reasoning tokens included where the provider counts them. */
    private Integer maxOutputTokens;

    /** Nucleus sampling threshold. */
    private Double topP;

    /** Headers for this call's HTTP request. */
    private final Map<String, String> headers = new LinkedHashMap<>();

    /** Provider-specific fields to merge into this call's payload. */
    private final ProviderExtras extras = new ProviderExtras();

}
