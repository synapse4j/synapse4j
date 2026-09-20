package io.github.synapse4j.jackson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.exception.SynapseIOException;
import io.github.synapse4j.json.JsonReader;
import io.github.synapse4j.json.JsonReader.Token;
import tools.jackson.databind.json.JsonMapper;

class JacksonJsonReaderTest {

    private final JacksonJsonCodec codec = new JacksonJsonCodec(JsonMapper.builder().build());

    @Test
    void walksADocumentTokenByToken() {
        JsonReader reader = codec.reader(source("{\"name\":\"Ada\",\"count\":3,\"ok\":true,\"none\":null,"
                + "\"items\":[1,\"two\"]}"));

        assertEquals(Token.START_OBJECT, reader.nextToken());

        assertEquals(Token.NAME, reader.nextToken());
        assertEquals("name", reader.name());
        assertEquals(Token.STRING, reader.nextToken());
        assertEquals("Ada", reader.string());

        assertEquals(Token.NAME, reader.nextToken());
        assertEquals("count", reader.name());
        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals(3L, reader.longValue());
        assertEquals(3.0, reader.doubleValue());

        assertEquals(Token.NAME, reader.nextToken());
        assertEquals("ok", reader.name());
        assertEquals(Token.TRUE, reader.nextToken());
        assertTrue(reader.booleanValue());

        assertEquals(Token.NAME, reader.nextToken());
        assertEquals("none", reader.name());
        assertEquals(Token.NULL, reader.nextToken());

        assertEquals(Token.NAME, reader.nextToken());
        assertEquals("items", reader.name());
        assertEquals(Token.START_ARRAY, reader.nextToken());
        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals(1L, reader.longValue());
        assertEquals(Token.STRING, reader.nextToken());
        assertEquals("two", reader.string());
        assertEquals(Token.END_ARRAY, reader.nextToken());
        assertEquals(Token.END_OBJECT, reader.nextToken());
        assertEquals(Token.END_DOCUMENT, reader.nextToken());
        assertEquals(Token.END_DOCUMENT, reader.nextToken());
    }

    @Test
    void skipsAValueWhicheverShapeItHas() {
        JsonReader reader = codec
                .reader(source("{\"kept\":1,\"object\":{\"a\":{\"b\":[1,2]}},\"array\":[{\"c\":3},4],\"also\":2}"));

        assertEquals(Token.START_OBJECT, reader.nextToken());
        assertEquals(Token.NAME, reader.nextToken());
        assertEquals("kept", reader.name());
        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals(1L, reader.longValue());
        reader.skipValue();

        assertEquals(Token.NAME, reader.nextToken());
        assertEquals("object", reader.name());
        assertEquals(Token.START_OBJECT, reader.nextToken());
        reader.skipValue();

        assertEquals(Token.NAME, reader.nextToken());
        assertEquals("array", reader.name());
        assertEquals(Token.START_ARRAY, reader.nextToken());
        reader.skipValue();

        assertEquals(Token.NAME, reader.nextToken());
        assertEquals("also", reader.name());
        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals(2L, reader.longValue());
        assertEquals(Token.END_OBJECT, reader.nextToken());
        assertEquals(Token.END_DOCUMENT, reader.nextToken());
    }

    @Test
    void streamsAStringValueIntoTheGivenWriter() {
        String value = "0123456789".repeat(50_000);
        JsonReader reader = codec.reader(source("{\"payload\":\"" + value + "\"}"));

        assertEquals(Token.START_OBJECT, reader.nextToken());
        assertEquals(Token.NAME, reader.nextToken());
        assertEquals(Token.STRING, reader.nextToken());

        StringWriter out = new StringWriter();
        long written = reader.string(out);

        assertEquals(value.length(), written);
        assertEquals(value, out.toString());
    }

    @Test
    void aTokenRefusesAValueItCannotCarry() {
        JsonReader reader = codec.reader(source("[\"text\",1,2.5,true]"));

        assertEquals(Token.START_ARRAY, reader.nextToken());

        assertEquals(Token.STRING, reader.nextToken());
        assertThrows(IllegalStateException.class, reader::longValue);
        assertThrows(IllegalStateException.class, reader::doubleValue);
        assertThrows(IllegalStateException.class, reader::booleanValue);

        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals(1L, reader.longValue());
        assertEquals(1.0, reader.doubleValue());
        assertThrows(IllegalStateException.class, reader::booleanValue);
        assertThrows(IllegalStateException.class, () -> reader.string(new StringWriter()));

        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals(2.5, reader.doubleValue());
        assertThrows(IllegalStateException.class, reader::longValue);

        assertEquals(Token.TRUE, reader.nextToken());
        assertTrue(reader.booleanValue());
        assertThrows(IllegalStateException.class, reader::longValue);
        assertThrows(IllegalStateException.class, reader::doubleValue);
    }

    @Test
    void aNumberTooLargeForALongIsNeverTruncated() {
        JsonReader reader = codec.reader(source("[9223372036854775808]"));

        assertEquals(Token.START_ARRAY, reader.nextToken());
        assertEquals(Token.NUMBER, reader.nextToken());

        assertThrows(SynapseException.class, reader::longValue);
    }

    @Test
    void malformedJsonIsASynapseException() {
        JsonReader reader = codec.reader(source("{\"a\":1,\"b\":}"));

        assertEquals(Token.START_OBJECT, reader.nextToken());
        assertEquals(Token.NAME, reader.nextToken());
        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals(1L, reader.longValue());

        assertThrows(SynapseException.class, reader::nextToken);
    }

    @Test
    void aFailingSourceIsASynapseIOException() {
        JsonReader reader = codec.reader(new FailingInputStream("{\"a\":1"));

        assertEquals(Token.START_OBJECT, reader.nextToken());

        // Producing a name token reads ahead into the value it names, so the source is hit here.
        SynapseIOException failure = assertThrows(SynapseIOException.class, reader::nextToken);

        assertEquals("broken pipe", failure.getCause().getMessage());
    }

    @Test
    void aSourceThatHasAlreadyFailedIsASynapseIOExceptionWhereTheReaderIsOpened() {
        SynapseIOException failure = assertThrows(SynapseIOException.class,
                () -> codec.reader(new FailingInputStream("")));

        assertEquals("broken pipe", failure.getCause().getMessage());
    }

    @Test
    void leavesTheSourceOpen() {
        RecordingInputStream source = new RecordingInputStream("{\"a\":[1]}");
        JsonReader reader = codec.reader(source);

        assertEquals(Token.START_OBJECT, reader.nextToken());
        assertEquals(Token.NAME, reader.nextToken());
        assertEquals(Token.START_ARRAY, reader.nextToken());
        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals(1L, reader.longValue());
        assertEquals(Token.END_ARRAY, reader.nextToken());
        assertEquals(Token.END_OBJECT, reader.nextToken());
        assertEquals(Token.END_DOCUMENT, reader.nextToken());
        reader.close();

        assertFalse(source.closed);
    }

    private static InputStream source(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    /** A source that remembers being closed, so that the ownership rule can be checked. */
    private static class RecordingInputStream extends ByteArrayInputStream {

        private boolean closed;

        RecordingInputStream(String json) {
            super(json.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void close() throws IOException {
            this.closed = true;
            super.close();
        }

    }

    /** A source that serves its content and then fails the way a broken transport does. */
    private static class FailingInputStream extends InputStream {

        private final byte[] content;

        private int position;

        FailingInputStream(String content) {
            this.content = content.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public int read() throws IOException {
            if (position >= content.length) {
                throw new IOException("broken pipe");
            }
            return content[position++] & 0xFF;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            if (position >= content.length) {
                throw new IOException("broken pipe");
            }
            int count = Math.min(length, content.length - position);
            System.arraycopy(content, position, target, offset, count);
            this.position += count;
            return count;
        }

    }

}
