package io.github.synapse4j.openai.wire;

import lombok.Data;

/**
 * The error body any non-2xx from the API carries: an {@code error} object with a message and the
 * classification fields around it.
 */
@Data
public class WireError {

    private ErrorBody error;

    /** The payload of the {@code error} field. */
    @Data
    public static class ErrorBody {

        private String message;
        private String type;
        private String code;
        private String param;

    }

}
