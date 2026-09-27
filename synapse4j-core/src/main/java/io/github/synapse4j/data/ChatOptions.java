package io.github.synapse4j.data;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.synapse4j.http.HttpOptions;
import org.jspecify.annotations.Nullable;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

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
@Getter
@Setter
@ToString
public class ChatOptions {

    /** Do not reason. */
    public static final String REASONING_EFFORT_NONE = "none";

    /** Reason as briefly as the model can. */
    public static final String REASONING_EFFORT_MINIMAL = "minimal";

    /** Reason a little. */
    public static final String REASONING_EFFORT_LOW = "low";

    /** The middle of the ladder, and where several providers put their own default. */
    public static final String REASONING_EFFORT_MEDIUM = "medium";

    /** Reason substantially. */
    public static final String REASONING_EFFORT_HIGH = "high";

    /** Beyond {@link #REASONING_EFFORT_HIGH}, on the models that go further. */
    public static final String REASONING_EFFORT_XHIGH = "xhigh";

    /** As much as the model offers. */
    public static final String REASONING_EFFORT_MAX = "max";

    /** Let the model decide whether to call a tool. */
    public static final String TOOL_CHOICE_AUTO = "auto";

    /** Call no tool. */
    public static final String TOOL_CHOICE_NONE = "none";

    /** Call at least one tool. */
    public static final String TOOL_CHOICE_REQUIRED = "required";

    /** Call the tool {@link #toolChoiceName} names, and no other. */
    public static final String TOOL_CHOICE_TOOL = "tool";

    /** Identifier of the model to call. */
    private @Nullable String model;

    /** Sampling temperature. */
    private @Nullable Double temperature;

    /** Upper bound on the tokens generated, reasoning tokens included where the provider counts them. */
    private @Nullable Integer maxOutputTokens;

    /** Nucleus sampling threshold. */
    private @Nullable Double topP;

    /**
     * How much the model should reason before it answers: one of the {@code REASONING_EFFORT_*}
     * constants, or any other level the endpoint understands. The constants name the rungs that mean
     * the same thing from one provider to the next; a level of one endpoint's own is written as it
     * stands, since no protocol fixes the set. {@code null} leaves the decision to the protocol, which
     * is the only way to ask for a model's own default rather than a level.
     */
    private @Nullable String reasoningEffort;

    /**
     * Which tools the model may call: one of the {@code TOOL_CHOICE_*} constants, or any other mode a
     * provider understands. {@code null} leaves the decision to the protocol, which every provider
     * this library speaks to reads as "decide for yourself".
     */
    private @Nullable String toolChoice;

    /**
     * The tool {@link #TOOL_CHOICE_TOOL} names, and no other; unset for every other mode, since a name
     * beside a mode that names no tool has nowhere to go.
     */
    private @Nullable String toolChoiceName;

    /**
     * HTTP-level settings for this call's request, or {@code null} to leave every one of them to
     * the {@link io.github.synapse4j.http.HttpClient} in use. Set it when one call needs different
     * HTTP behavior than the client's defaults — a longer response timeout, a larger frame budget.
     */
    private @Nullable HttpOptions httpOptions;

    /** Headers for this call's HTTP request. */
    private final Map<String, String> headers = new LinkedHashMap<>();

    /** Provider-specific fields to merge into this call's payload. */
    private final ProviderExtras extras = new ProviderExtras();

}
