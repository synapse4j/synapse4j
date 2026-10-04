package io.github.synapse4j.json;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AbstractJsonCodecTest {

    @Test
    void handsEverythingElseToTheSubclass() {
        StubCodec codec = new StubCodec();

        assertEquals("stub", codec.encode(Map.of("name", "value")));
        assertEquals(Map.of("name", "value"), codec.encoded);

        codec.decode("{\"name\":\"value\"}", String.class);
        assertEquals(String.class, codec.decodedType);
    }

    @Test
    void aSubclassCanAddItsOwnSpecialCase() {
        StubCodec codec = new InterceptingCodec();

        assertEquals("intercepted", codec.encode("value"));
        // Anything the subclass does not intercept reaches the hook as the value it was given.
        assertEquals("stub", codec.encode(new JsonSchemaBuilder().build()));
        assertEquals(new JsonSchemaBuilder().build(), codec.encoded);
    }

    private static JsonSchema subSchema(String type) {
        return new JsonSchemaBuilder().setType(type).build();
    }

    private static class StubCodec extends AbstractJsonCodec {

        private Object encoded;

        private Object decoded;

        private Type decodedType;

        @Override
        public JsonSchema generateEncodeSchema(Type type) {
            return subSchema("stub");
        }

        @Override
        public JsonSchema generateDecodeSchema(Type type) {
            return subSchema("stub");
        }

        @Override
        protected String encodeValue(Object value) {
            this.encoded = value;
            return "stub";
        }

        @Override
        @SuppressWarnings("unchecked")
        protected <T> T decodeValue(String json, Type type) {
            this.decodedType = type;
            return (T) decoded;
        }

        // The streaming side is the library's business, not this class's: a codec over a JSON library
        // supplies it, and these tests are about the encode and decode hooks.

        @Override
        public JsonWriter writer(OutputStream out) {
            throw new UnsupportedOperationException("these tests never write a document");
        }

        @Override
        public JsonReader reader(InputStream in) {
            throw new UnsupportedOperationException("these tests never read a document");
        }

    }

    /** Nothing is final here, so a subclass can intercept a type of its own and delegate the rest. */
    private static class InterceptingCodec extends StubCodec {

        @Override
        public String encode(Object value) {
            return value instanceof String ? "intercepted" : super.encode(value);
        }

    }

}
