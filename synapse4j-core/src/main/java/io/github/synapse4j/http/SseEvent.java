package io.github.synapse4j.http;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;

/**
 * One server-sent event as it arrived: the frame of the {@code text/event-stream} format, before
 * any protocol gives it meaning.
 *
 * <p>
 * This is deliberately the raw frame and nothing more — the {@code event:} name if the frame had
 * one, and the {@code data:} lines joined. What a frame means, and whether the empty data or a
 * keep-alive comment is worth an event of its own, belongs to the protocol module that reads it;
 * the transport's job is to cut the bytes into frames faithfully.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SseEvent {

    /**
     * The {@code event:} field of the frame, or {@code null} when it carried none. Protocols that
     * name their events put the name here; those that discriminate inside the payload leave it
     * empty.
     */
    private String event;

    /**
     * The frame's {@code data:} lines joined with a newline, never {@code null}. A frame with no
     * data at all is not an event and is not handed out.
     */
    @NonNull
    private String data;

}
