package io.github.synapse4j.jackson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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
    void answersWithTheTokenTheLastAdvanceReturned() {
        JsonReader reader = codec.reader(source("{\"a\":1}"));

        assertNull(reader.token());

        assertEquals(Token.START_OBJECT, reader.nextToken());
        assertEquals(Token.START_OBJECT, reader.token());
        assertEquals(Token.NAME, reader.nextToken());
        assertEquals(Token.NAME, reader.token());
        assertEquals("a", reader.name());
        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals(Token.NUMBER, reader.token());
        assertEquals(1L, reader.longValue());
        assertEquals(Token.END_OBJECT, reader.nextToken());
        assertEquals(Token.END_OBJECT, reader.token());
        assertEquals(Token.END_DOCUMENT, reader.nextToken());
        assertEquals(Token.END_DOCUMENT, reader.token());
        assertEquals(Token.END_DOCUMENT, reader.nextToken());
        assertEquals(Token.END_DOCUMENT, reader.token());
    }

    @Test
    void capturesScalarsInTheShapeADecodedDocumentHas() {
        JsonReader reader = codec.reader(source(
                "[\"Ada\",3,2147483647,2147483648,9223372036854775807,9223372036854775808,2.5,true,false,null]"));

        assertEquals(Token.START_ARRAY, reader.nextToken());

        assertEquals(Token.STRING, reader.nextToken());
        assertEquals("Ada", reader.captureValue());

        // One of every shape an untyped document hands back, the whole numbers included: what each
        // number becomes is pinned by the test below, and this one that all of them survive a walk.
        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals(3, reader.captureValue());

        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals(2147483647, reader.captureValue());

        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals(2147483648L, reader.captureValue());

        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals(9223372036854775807L, reader.captureValue());

        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals(new BigInteger("9223372036854775808"), reader.captureValue());

        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals(2.5, reader.captureValue());

        assertEquals(Token.TRUE, reader.nextToken());
        assertEquals(Boolean.TRUE, reader.captureValue());

        assertEquals(Token.FALSE, reader.nextToken());
        assertEquals(Boolean.FALSE, reader.captureValue());

        assertEquals(Token.NULL, reader.nextToken());
        assertNull(reader.captureValue());

        assertEquals(Token.END_ARRAY, reader.nextToken());
        assertEquals(Token.END_DOCUMENT, reader.nextToken());
    }

    @Test
    void capturesAnObjectAndAnArrayWithNothingInThem() {
        JsonReader reader = codec.reader(source("{\"empty\":{},\"list\":[],\"dup\":1,\"dup\":2}"));

        assertEquals(Token.START_OBJECT, reader.nextToken());

        Object captured = reader.captureValue();

        // A key the document wrote twice is there once, holding the last value it was given and
        // keeping the place the first of them had.
        assertEquals(Map.of("empty", Map.of(), "list", List.of(), "dup", 2), captured);
        // Map equality ignores order, so the place the key kept is checked on its own.
        assertIterableEquals(List.of("empty", "list", "dup"), ((Map<?, ?>) captured).keySet());
    }

    @Test
    void capturesANumberInTheNarrowestTypeThatHoldsIt() {
        JsonReader reader = codec.reader(source("[0,-0,999999999,1000000000,2147483647,-2147483648,"
                + "2147483648,-2147483649,999999999999999999,1000000000000000000,9223372036854775807,"
                + "-9223372036854775808,9223372036854775808,-9223372036854775809,2.5,1.0,1e2,1e309,1e-400]"));

        assertEquals(Token.START_ARRAY, reader.nextToken());

        // Up to nine digits an int holds whatever the digits say, and the tenth is where the value
        // starts to decide — 1000000000 still fits, so it stays an int while the next one cannot.
        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals(0, reader.captureValue());

        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals(0, reader.captureValue());

        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals(999_999_999, reader.captureValue());

        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals(1_000_000_000, reader.captureValue());

        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals(Integer.MAX_VALUE, reader.captureValue());

        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals(Integer.MIN_VALUE, reader.captureValue());

        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals(2147483648L, reader.captureValue());

        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals(-2147483649L, reader.captureValue());

        // Nineteen digits is the same question one size up, and a long answers it as long as it fits.
        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals(999_999_999_999_999_999L, reader.captureValue());

        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals(1_000_000_000_000_000_000L, reader.captureValue());

        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals(Long.MAX_VALUE, reader.captureValue());

        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals(Long.MIN_VALUE, reader.captureValue());

        // Past that a number is kept as it was written rather than rounded into one that fits.
        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals(new BigInteger("9223372036854775808"), reader.captureValue());

        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals(new BigInteger("-9223372036854775809"), reader.captureValue());

        // Anything spelled with a fraction or an exponent is a double, whatever it evaluates to.
        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals(2.5, reader.captureValue());

        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals(1.0, reader.captureValue());

        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals(100.0, reader.captureValue());

        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals(Double.POSITIVE_INFINITY, reader.captureValue());

        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals(0.0, reader.captureValue());

        assertEquals(Token.END_ARRAY, reader.nextToken());
        assertEquals(Token.END_DOCUMENT, reader.nextToken());
    }

    @Test
    void capturesAnObjectKeepingItsKeysInDocumentOrder() {
        JsonReader reader = codec
                .reader(source("{\"object\":{\"b\":1,\"a\":\"two\",\"nested\":{\"x\":true}}}"));

        assertEquals(Token.START_OBJECT, reader.nextToken());
        assertEquals(Token.NAME, reader.nextToken());
        assertEquals(Token.START_OBJECT, reader.nextToken());

        Object captured = reader.captureValue();

        assertEquals(Map.of("b", 1, "a", "two", "nested", Map.of("x", true)), captured);
        // Map equality ignores order, so the order the document wrote is checked on its own.
        assertIterableEquals(List.of("b", "a", "nested"), ((Map<?, ?>) captured).keySet());

        assertEquals(Token.END_OBJECT, reader.nextToken());
        assertEquals(Token.END_DOCUMENT, reader.nextToken());
    }

    @Test
    void capturesAnArrayOfMixedElements() {
        JsonReader reader = codec.reader(source("{\"list\":[1,\"two\",{\"three\":3},[4],null]}"));

        assertEquals(Token.START_OBJECT, reader.nextToken());
        assertEquals(Token.NAME, reader.nextToken());
        assertEquals(Token.START_ARRAY, reader.nextToken());

        assertEquals(Arrays.asList(1, "two", Map.of("three", 3), List.of(4), null), reader.captureValue());

        assertEquals(Token.END_OBJECT, reader.nextToken());
        assertEquals(Token.END_DOCUMENT, reader.nextToken());
    }

    @Test
    void capturingASubtreeLeavesTheEnclosingWalkInPlace() {
        JsonReader reader = codec
                .reader(source("{\"kept\":{\"deep\":[1,{\"deeper\":2}]},\"after\":\"value\"}"));

        assertEquals(Token.START_OBJECT, reader.nextToken());
        assertEquals(Token.NAME, reader.nextToken());
        assertEquals("kept", reader.name());
        assertEquals(Token.START_OBJECT, reader.nextToken());

        assertEquals(Map.of("deep", Arrays.asList(1, Map.of("deeper", 2))), reader.captureValue());

        // The whole subtree was consumed, so the walk resumes on what follows it.
        assertEquals(Token.NAME, reader.nextToken());
        assertEquals("after", reader.name());
        assertEquals(Token.STRING, reader.nextToken());
        assertEquals("value", reader.string());
        assertEquals(Token.END_OBJECT, reader.nextToken());
        assertEquals(Token.END_DOCUMENT, reader.nextToken());
    }

    @Test
    void capturingTheLastValueOfAnObjectLeavesTheWalkOnItsEndToken() {
        JsonReader reader = codec.reader(source("{\"a\":1,\"kept\":{\"b\":[2]}}"));

        assertEquals(Token.START_OBJECT, reader.nextToken());
        assertEquals(Token.NAME, reader.nextToken());
        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals(1, reader.captureValue());
        assertEquals(Token.NAME, reader.nextToken());
        assertEquals(Token.START_OBJECT, reader.nextToken());

        assertEquals(Map.of("b", List.of(2)), reader.captureValue());

        assertEquals(Token.END_OBJECT, reader.nextToken());
        assertEquals(Token.END_DOCUMENT, reader.nextToken());
    }

    @Test
    void captureValueNeedsAValueToStandOn() {
        JsonReader reader = codec.reader(source("{\"a\":1}"));

        assertThrows(IllegalStateException.class, reader::captureValue);

        assertEquals(Token.START_OBJECT, reader.nextToken());
        assertEquals(Token.NAME, reader.nextToken());
        assertThrows(IllegalStateException.class, reader::captureValue);

        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals(1, reader.captureValue());

        assertEquals(Token.END_OBJECT, reader.nextToken());
        assertThrows(IllegalStateException.class, reader::captureValue);
        assertEquals(Token.END_DOCUMENT, reader.nextToken());
        assertThrows(IllegalStateException.class, reader::captureValue);
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
        // A number the document spelled with a fraction: the caller asked for something a number
        // token can carry, so this is a failed read rather than a misuse.
        assertThrows(SynapseException.class, reader::longValue);

        assertEquals(Token.TRUE, reader.nextToken());
        assertTrue(reader.booleanValue());
        assertThrows(IllegalStateException.class, reader::longValue);
        assertThrows(IllegalStateException.class, reader::doubleValue);
    }

    @Test
    void stringAnswersOnlyForTokensThatCarryText() {
        JsonReader reader = codec.reader(source("{\"text\":\"value\",\"int\":7,\"float\":1e2,\"yes\":true,"
                + "\"no\":false,\"none\":null,\"object\":{},\"array\":[]}"));

        assertEquals(Token.START_OBJECT, reader.nextToken());
        assertNull(reader.string());

        assertEquals(Token.NAME, reader.nextToken());
        assertNull(reader.string());
        assertEquals(Token.STRING, reader.nextToken());
        assertEquals("value", reader.string());

        assertEquals(Token.NAME, reader.nextToken());
        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals("7", reader.string());

        // A number answers with the form the document spelled, not with a re-rendering of the value.
        assertEquals(Token.NAME, reader.nextToken());
        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals("1e2", reader.string());

        // Jackson's own getString() answers "true", "false", "{" and "[" on the tokens below — answers
        // that read like text and cannot be told apart from a string that really carries it — so a
        // token with no text of its own answers null here instead.
        assertEquals(Token.NAME, reader.nextToken());
        assertEquals(Token.TRUE, reader.nextToken());
        assertNull(reader.string());
        assertTrue(reader.booleanValue());

        assertEquals(Token.NAME, reader.nextToken());
        assertEquals(Token.FALSE, reader.nextToken());
        assertNull(reader.string());

        assertEquals(Token.NAME, reader.nextToken());
        assertEquals(Token.NULL, reader.nextToken());
        assertNull(reader.string());

        assertEquals(Token.NAME, reader.nextToken());
        assertEquals(Token.START_OBJECT, reader.nextToken());
        assertNull(reader.string());
        assertEquals(Token.END_OBJECT, reader.nextToken());
        assertNull(reader.string());

        assertEquals(Token.NAME, reader.nextToken());
        assertEquals(Token.START_ARRAY, reader.nextToken());
        assertNull(reader.string());
        assertEquals(Token.END_ARRAY, reader.nextToken());
        assertNull(reader.string());

        assertEquals(Token.END_OBJECT, reader.nextToken());
        assertNull(reader.string());
    }

    @Test
    void nameAnswersOnlyOnAPropertyName() {
        JsonReader reader = codec.reader(source("{\"outer\":{\"inner\":1}}"));

        assertEquals(Token.START_OBJECT, reader.nextToken());
        assertNull(reader.name());

        assertEquals(Token.NAME, reader.nextToken());
        assertEquals("outer", reader.name());

        // Jackson's currentName() goes on answering "outer" here and on every token until the next
        // name, since it answers from where the parser is. This one answers from the token.
        assertEquals(Token.START_OBJECT, reader.nextToken());
        assertNull(reader.name());

        assertEquals(Token.NAME, reader.nextToken());
        assertEquals("inner", reader.name());
        assertEquals(Token.NUMBER, reader.nextToken());
        assertNull(reader.name());
        assertEquals(1L, reader.longValue());
        assertNull(reader.name());

        assertEquals(Token.END_OBJECT, reader.nextToken());
        assertNull(reader.name());
        assertEquals(Token.END_OBJECT, reader.nextToken());
        assertNull(reader.name());
    }

    @Test
    void aNumberTooLargeForALongIsNeverTruncated() {
        JsonReader reader = codec.reader(source("[9223372036854775808]"));

        assertEquals(Token.START_ARRAY, reader.nextToken());
        assertEquals(Token.NUMBER, reader.nextToken());

        SynapseException failure = assertThrows(SynapseException.class, reader::longValue);

        // The message says what was being done, and the cause Jackson reported carries the range and
        // the value, so repeating either here would only be a second place to keep in step.
        assertEquals("Reading the JSON document failed", failure.getMessage());
        assertTrue(failure.getCause().getMessage().contains("9223372036854775808"));
        // The number is still on the token, so a caller that wants its text can read it.
        assertEquals("9223372036854775808", reader.string());
    }

    @Test
    void aNumberSpelledWithAFractionIsReadAsALongOnlyWhenItIsWhole() {
        JsonReader reader = codec.reader(source("[11.0,11.5]"));

        assertEquals(Token.START_ARRAY, reader.nextToken());
        assertEquals(Token.NUMBER, reader.nextToken());
        assertEquals(11L, reader.longValue());

        assertEquals(Token.NUMBER, reader.nextToken());

        SynapseException failure = assertThrows(SynapseException.class, reader::longValue);

        assertEquals("the number is not an integer: 11.5", failure.getMessage());
        assertEquals(11.5, reader.doubleValue());
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
