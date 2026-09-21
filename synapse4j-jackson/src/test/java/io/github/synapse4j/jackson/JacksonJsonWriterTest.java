package io.github.synapse4j.jackson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.github.synapse4j.json.JsonWriter;
import tools.jackson.databind.json.JsonMapper;

class JacksonJsonWriterTest {

    private final JacksonJsonCodec codec = new JacksonJsonCodec(JsonMapper.builder().build());

    @Test
    void writesANestedDocumentAsItsExactText() {
        RecordingOutputStream sink = new RecordingOutputStream();

        JsonWriter writer = codec.writer(sink);
        writer.writeStartObject()
                .writeName("name").writeString("Ada")
                .writeName("count").writeNumber(3)
                .writeName("ratio").writeNumber(0.5)
                .writeName("enabled").writeBoolean(true)
                .writeName("nothing").writeNull()
                .writeName("missing").writeString((String) null)
                .writeName("nested").writeStartObject().writeName("inner").writeString("x").writeEndObject()
                .writeName("items").writeStartArray().writeNumber(1).writeNumber(2).writeEndArray()
                .writeEndObject();
        writer.close();

        assertEquals("{\"name\":\"Ada\",\"count\":3,\"ratio\":0.5,\"enabled\":true,\"nothing\":null,"
                + "\"missing\":null,\"nested\":{\"inner\":\"x\"},\"items\":[1,2]}", sink.text());
    }

    @Test
    void writesAValueTooLargeToHoldStraightFromAReader() {
        String value = "0123456789".repeat(50_000);
        TrackingReader text = new TrackingReader(value);
        RecordingOutputStream sink = new RecordingOutputStream();

        JsonWriter writer = codec.writer(sink);
        writer.writeStartObject().writeName("payload").writeString(text).writeEndObject();
        writer.close();

        assertEquals("{\"payload\":\"" + value + "\"}", sink.text());
        assertTrue(text.closed);
    }

    @Test
    void writesABigNumberExactly() {
        RecordingOutputStream sink = new RecordingOutputStream();

        JsonWriter writer = codec.writer(sink);
        writer.writeStartArray()
                .writeNumber(new BigDecimal("123456789012345678901234567890.5"))
                .writeNumber(new BigDecimal("99999999999999999999"))
                .writeEndArray();
        writer.close();

        assertEquals("[123456789012345678901234567890.5,99999999999999999999]", sink.text());
    }

    @Test
    void writesAPreEncodedNumberAsGiven() {
        RecordingOutputStream sink = new RecordingOutputStream();

        JsonWriter writer = codec.writer(sink);
        writer.writeNumber("1e5");
        writer.close();

        assertEquals("1e5", sink.text());
    }

    @Test
    void flushPushesTheDocumentIntoTheSinkWithoutClosingIt() {
        RecordingOutputStream sink = new RecordingOutputStream();

        JsonWriter writer = codec.writer(sink);
        writer.writeStartObject().writeName("a").writeNumber(1).writeEndObject();
        writer.flush();

        assertEquals("{\"a\":1}", sink.text());
        assertFalse(sink.closed);
    }

    @Test
    void leavesTheSinkOpen() {
        RecordingOutputStream sink = new RecordingOutputStream();

        JsonWriter writer = codec.writer(sink);
        writer.writeStartArray().writeBoolean(false).writeEndArray();
        writer.close();

        assertEquals("[false]", sink.text());
        assertFalse(sink.closed);
    }

    /** A sink that remembers being closed, so that the ownership rule can be checked. */
    private static class RecordingOutputStream extends ByteArrayOutputStream {

        private boolean closed;

        @Override
        public void close() throws IOException {
            this.closed = true;
            super.close();
        }

        String text() {
            return toString(StandardCharsets.UTF_8);
        }

    }

    /** A reader that remembers being closed: writing from it must consume it and close it. */
    private static class TrackingReader extends StringReader {

        private boolean closed;

        TrackingReader(String text) {
            super(text);
        }

        @Override
        public void close() {
            this.closed = true;
            super.close();
        }

    }

}
