package io.github.synapse4j.http;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpBodyTest {

    @Test
    void aLambdaBodyWritesWhatItWritesAndKnowsNoLength() throws IOException {
        HttpBody body = out -> out.write("hello".getBytes(UTF_8));

        assertArrayEquals("hello".getBytes(UTF_8), written(body));
        assertEquals(-1, body.length());
    }

    @Test
    void aBodyOfBytesWritesThemAndAnswersTheirLength() throws IOException {
        HttpBody body = HttpBody.of("{\"a\":1}".getBytes(UTF_8));

        assertArrayEquals("{\"a\":1}".getBytes(UTF_8), written(body));
        assertEquals(7, body.length());
    }

    @Test
    void aBodyOfBytesUsesTheArrayItWasGivenRatherThanACopy() throws IOException {
        byte[] bytes = { 1, 2, 3 };
        HttpBody body = HttpBody.of(bytes);

        bytes[1] = 9;

        assertArrayEquals(new byte[] { 1, 9, 3 }, written(body));
    }

    @Test
    void aBodyOfTextIsWrittenInUtf8AndCountedInBytes() throws IOException {
        HttpBody body = HttpBody.of("héllo");

        assertArrayEquals("h\u00E9llo".getBytes(UTF_8), written(body));
        assertEquals(6, body.length());
    }

    @Test
    void aBodyOfTextCanBeWrittenInAnotherCharset() throws IOException {
        HttpBody body = HttpBody.of("héllo", ISO_8859_1);

        assertArrayEquals("h\u00E9llo".getBytes(ISO_8859_1), written(body));
        assertEquals(5, body.length());
    }

    @Test
    void aBodyOfAFileWritesItsContentAndAnswersItsSize(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("request.json");
        Files.writeString(file, "{\"a\":1}");

        HttpBody body = HttpBody.ofFile(file);

        assertArrayEquals("{\"a\":1}".getBytes(UTF_8), written(body));
        assertEquals(7, body.length());
    }

    @Test
    void aFileThatCannotBeReadIsUnknownLengthAndFailsWhereItIsWritten(@TempDir Path directory) {
        HttpBody body = HttpBody.ofFile(directory.resolve("missing.json"));

        assertEquals(-1, body.length());
        assertThrows(IOException.class, () -> body.writeTo(new ByteArrayOutputStream()));
    }

    @Test
    void aFileThatGrewAfterItsLengthWasTakenFailsRatherThanPassingForAWholeBody(@TempDir Path directory)
            throws IOException {
        Path file = directory.resolve("request.json");
        Files.writeString(file, "{\"a\":1}");
        HttpBody body = HttpBody.ofFile(file);

        Files.writeString(file, "{\"a\":1,\"b\":2}");

        IOException failure = assertThrows(IOException.class, () -> body.writeTo(new ByteArrayOutputStream()));

        assertEquals(7, body.length());
        assertEquals("the file changed while it was being sent: " + file + " was 7 bytes, "
                + "13 bytes were written", failure.getMessage());
    }

    @Test
    void aFileThatShrankAfterItsLengthWasTakenFailsRatherThanPassingForAWholeBody(@TempDir Path directory)
            throws IOException {
        Path file = directory.resolve("request.json");
        Files.writeString(file, "{\"a\":1}");
        HttpBody body = HttpBody.ofFile(file);

        Files.writeString(file, "{}");

        IOException failure = assertThrows(IOException.class, () -> body.writeTo(new ByteArrayOutputStream()));

        assertEquals("the file changed while it was being sent: " + file + " was 7 bytes, "
                + "2 bytes were written", failure.getMessage());
    }

    @Test
    void aBodyWritesTheSameBytesEveryTimeItIsAsked(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("request.json");
        Files.writeString(file, "{\"a\":1}");

        // A request is sent again on a retry, on a redirect, and after an authentication challenge.
        for (HttpBody body : new HttpBody[] { HttpBody.of("{\"a\":1}"), HttpBody.ofFile(file) }) {
            assertArrayEquals(written(body), written(body));
        }
    }

    @Test
    void aBodyLeavesTheSinkItWritesToOpen(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("request.json");
        Files.writeString(file, "{}");

        RecordingOutputStream out = new RecordingOutputStream();
        HttpBody.of("{}").writeTo(out);
        HttpBody.ofFile(file).writeTo(out);

        assertFalse(out.closed);
        assertEquals("{}" + "{}", out.toString(UTF_8));
    }

    private static byte[] written(HttpBody body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        body.writeTo(out);
        return out.toByteArray();
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
