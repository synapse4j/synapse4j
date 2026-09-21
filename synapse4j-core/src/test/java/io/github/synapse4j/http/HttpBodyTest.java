package io.github.synapse4j.http;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

class HttpBodyTest {

    @Test
    void aLambdaBodyWritesWhatItWritesAndHasNoBuffer() throws IOException {
        HttpBody body = out -> out.write("hello".getBytes(UTF_8));

        assertArrayEquals("hello".getBytes(UTF_8), written(body));
        assertNull(body.buffer());
    }

    @Test
    void aBodyOfBytesWritesThemAndAnswersWithABuffer() throws IOException {
        HttpBody body = HttpBody.of("{\"a\":1}".getBytes(UTF_8));

        assertArrayEquals("{\"a\":1}".getBytes(UTF_8), written(body));
        assertArrayEquals("{\"a\":1}".getBytes(UTF_8), buffered(body));
        assertEquals(7, body.buffer().remaining());
    }

    @Test
    void aBodyOfBytesUsesTheArrayItWasGivenRatherThanACopy() throws IOException {
        byte[] bytes = { 1, 2, 3 };
        HttpBody body = HttpBody.of(bytes);

        bytes[1] = 9;

        assertArrayEquals(new byte[] { 1, 9, 3 }, written(body));
        assertArrayEquals(new byte[] { 1, 9, 3 }, buffered(body));
    }

    @Test
    void aBodyOfTextIsWrittenInUtf8() throws IOException {
        HttpBody body = HttpBody.of("héllo");

        assertArrayEquals("h\u00E9llo".getBytes(UTF_8), written(body));
        assertEquals(6, body.buffer().remaining());
    }

    @Test
    void aBodyOfTextCanBeWrittenInAnotherCharset() throws IOException {
        HttpBody body = HttpBody.of("héllo", ISO_8859_1);

        assertArrayEquals("h\u00E9llo".getBytes(ISO_8859_1), written(body));
        assertEquals(5, body.buffer().remaining());
    }

    @Test
    void everyCallAnswersWithABufferOfItsOwn() {
        HttpBody body = HttpBody.of("{\"a\":1}");

        ByteBuffer first = body.buffer();
        first.position(first.limit()); // a caller consumes what it was given

        ByteBuffer second = body.buffer();

        assertEquals(0, second.position());
        assertEquals(first.limit(), second.limit());
        assertArrayEquals("{\"a\":1}".getBytes(UTF_8), buffered(body));
    }

    @Test
    void aBodyProducesTheSameBytesEveryTimeItIsAsked() throws IOException {
        // A request is sent again on a retry, on a redirect, and after an authentication challenge.
        HttpBody body = HttpBody.of("{\"a\":1}");

        assertArrayEquals(written(body), written(body));
        assertArrayEquals(buffered(body), buffered(body));
    }

    @Test
    void aBodyLeavesTheSinkItWritesToOpen() throws IOException {
        RecordingOutputStream out = new RecordingOutputStream();

        HttpBody.of("{}").writeTo(out);

        assertFalse(out.closed);
        assertEquals("{}", out.toString(UTF_8));
    }

    private static byte[] written(HttpBody body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        body.writeTo(out);
        return out.toByteArray();
    }

    private static byte[] buffered(HttpBody body) {
        ByteBuffer buffer = body.buffer();
        byte[] content = new byte[buffer.remaining()];
        buffer.get(content);
        return content;
    }

    /** A sink that remembers being closed, so that the ownership rule can be checked. */
    private static class RecordingOutputStream extends ByteArrayOutputStream {

        private boolean closed;

        @Override
        public void close() throws IOException {
            this.closed = true;
            super.close();
        }

    }

}
