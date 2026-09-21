package io.github.synapse4j.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.Writer;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.synapse4j.exception.SynapseIOException;

/**
 * Tests for {@link AbstractJsonReader#captureValue()} over a scripted reader: how numbers are
 * classified, and the decoded shape of nested documents. No JSON library is involved — a stub
 * hands out the tokens.
 */
class AbstractJsonReaderTest {

    @Test
    void classifiesWholeNumbersByHowTheyAreSpelled() {
        assertEquals(5, capture(number("5")));
        assertEquals(-7, capture(number("-7")));
        assertEquals(3_000_000_000L, capture(number("3000000000")));
        assertEquals(new BigInteger("99999999999999999999"), capture(number("99999999999999999999")));
    }

    @Test
    void numbersSpelledWithAFractionOrExponentAreDoubles() {
        assertEquals(1e5, (Double) capture(number("1e5")), 0.0);
        assertEquals(100.0, (Double) capture(number("100.0")), 0.0);
        assertEquals(2.5e-3, (Double) capture(number("2.5E-3")), 0.0);
    }

    @Test
    void capturesScalarsAsThemselves() {
        assertEquals("text", capture(string("text")));
        assertEquals(Boolean.TRUE, capture(new Entry(JsonReader.Token.TRUE, null)));
        assertEquals(Boolean.FALSE, capture(new Entry(JsonReader.Token.FALSE, null)));
        assertEquals(null, capture(new Entry(JsonReader.Token.NULL, null)));
    }

    @Test
    void capturesObjectsAndArraysInTheDecodedShape() {
        Object captured = capture(new Entry(JsonReader.Token.START_OBJECT, null),
                new Entry(JsonReader.Token.NAME, "a"),
                number("5"),
                new Entry(JsonReader.Token.NAME, "b"),
                new Entry(JsonReader.Token.START_ARRAY, null),
                string("x"),
                new Entry(JsonReader.Token.NULL, null),
                new Entry(JsonReader.Token.END_ARRAY, null),
                new Entry(JsonReader.Token.END_OBJECT, null));

        assertEquals(Map.of("a", 5, "b", Arrays.asList("x", null)), captured);
    }

    @Test
    void keepsObjectKeysInDocumentOrder() {
        Object captured = capture(new Entry(JsonReader.Token.START_OBJECT, null),
                new Entry(JsonReader.Token.NAME, "first"),
                number("1"),
                new Entry(JsonReader.Token.NAME, "second"),
                number("2"),
                new Entry(JsonReader.Token.END_OBJECT, null));

        Iterator<String> keys = ((Map<String, Object>) captured).keySet().iterator();
        assertEquals("first", keys.next());
        assertEquals("second", keys.next());
    }

    @Test
    void refusesToCaptureBeforeTheReaderHasAdvanced() {
        StubReader reader = new StubReader();

        assertThrows(IllegalStateException.class, reader::captureValue);
    }

    private static Object capture(Entry... script) {
        StubReader reader = new StubReader();
        for (Entry entry : script) {
            reader.script.add(entry);
        }
        reader.nextToken();
        return reader.captureValue();
    }

    private static Entry number(String text) {
        return new Entry(JsonReader.Token.NUMBER, text);
    }

    private static Entry string(String text) {
        return new Entry(JsonReader.Token.STRING, text);
    }

    private record Entry(JsonReader.Token token, String text) {
    }

    /** A reader driven by a script of tokens; captures go through the abstract class being tested. */
    private static class StubReader extends AbstractJsonReader {

        private final Deque<Entry> script = new ArrayDeque<>();

        private Entry current;

        @Override
        public Token nextToken() {
            current = script.isEmpty() ? new Entry(Token.END_DOCUMENT, null) : script.removeFirst();
            return current.token();
        }

        @Override
        public Token token() {
            return current == null ? null : current.token();
        }

        @Override
        public String name() {
            return current.text();
        }

        @Override
        public String string() {
            return current.text();
        }

        @Override
        public long string(Writer out) {
            try {
                out.write(string());
            } catch (IOException failure) {
                throw new SynapseIOException("Writing the string failed", failure);
            }
            return string().length();
        }

        @Override
        public long longValue() {
            return Long.parseLong(string());
        }

        @Override
        public double doubleValue() {
            return Double.parseDouble(string());
        }

        @Override
        public boolean booleanValue() {
            return current.token() == Token.TRUE;
        }

        @Override
        public void skipValue() {
            throw new UnsupportedOperationException("these tests never skip");
        }

        @Override
        public void close() {
            // Nothing to release.
        }

    }

}
