package io.github.synapse4j.openai;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * The event vocabulary of the Responses stream, as the protocol spells it.
 *
 * <p>
 * This protocol names its frames in the SSE {@code event:} field and repeats the name in the
 * payload's {@code type} member, so an event's {@code eventType} is the provider's own name rather
 * than a translation of it. The values are plain strings, like every value in this library that can
 * grow — a provider adding a kind of event does not need a release of this one.
 *
 * <p>
 * A holder of constants rather than a data class: it is final, and it cannot be instantiated.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class OpenAiResponsesEventTypes {

    /** One fragment of the answer's text. */
    public static final String OUTPUT_TEXT_DELTA = "response.output_text.delta";

    /** An item the answer is being assembled from has joined the response. */
    public static final String OUTPUT_ITEM_ADDED = "response.output_item.added";

    /** One fragment of a tool call's arguments. */
    public static final String FUNCTION_CALL_ARGUMENTS_DELTA = "response.function_call_arguments.delta";

    /** One fragment of the model's reasoning summary. */
    public static final String REASONING_SUMMARY_TEXT_DELTA = "response.reasoning_summary_text.delta";

    /** The exchange has been accepted: a response object exists and nothing has been said yet. */
    public static final String CREATED = "response.created";

    /** The exchange is under way. */
    public static final String IN_PROGRESS = "response.in_progress";

    /** The answer is complete; the frame carries the whole response. */
    public static final String COMPLETED = "response.completed";

    /** The answer stopped short; the frame carries the whole response and why it stopped. */
    public static final String INCOMPLETE = "response.incomplete";

    /** The exchange failed; the frame carries the whole response and its error. */
    public static final String FAILED = "response.failed";

    /** A failure reported as an event of its own, after the answer was accepted. */
    public static final String ERROR = "error";

}
