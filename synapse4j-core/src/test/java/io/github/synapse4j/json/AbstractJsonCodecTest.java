package io.github.synapse4j.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AbstractJsonCodecTest {

    @Test
    void writesASchemaAsTheDocumentItDescribes() {
        JsonSchema schema = new JsonSchema();
        schema.setType("object");
        schema.getProperties().put("name", subSchema("string"));
        schema.getExtras().put("$schema", "https://json-schema.org/draft/2020-12/schema");

        StubCodec codec = new StubCodec();

        assertEquals("stub", codec.encode(schema));
        assertEquals(schema.toMap(), codec.encoded);
        assertTrue(codec.encoded instanceof Map);
    }

    @Test
    void readsASchemaFromTheDocumentItDescribes() {
        StubCodec codec = new StubCodec();
        codec.decoded = Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string")));

        JsonSchema schema = codec.decode("{\"type\":\"object\"}", JsonSchema.class);

        assertEquals(Map.class, codec.decodedType);
        assertEquals(List.of("object"), schema.getType());
        assertEquals(List.of("string"), schema.getProperties().get("name").getType());
    }

    @Test
    void readsASchemaAskedForAsASubclassTheSameWay() {
        StubCodec codec = new StubCodec();
        codec.decoded = Map.of("type", "string");

        JsonSchema schema = codec.decode("{}", ExtendedSchema.class);

        assertEquals(Map.class, codec.decodedType);
        assertEquals(List.of("string"), schema.getType());
    }

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
        assertEquals("stub", codec.encode(new JsonSchema()));
        assertEquals(Map.of(), codec.encoded);
    }

    private static JsonSchema subSchema(String type) {
        JsonSchema schema = new JsonSchema();
        schema.setType(type);
        return schema;
    }

    /** Providers extend the schema model; decoding must treat a subclass like the base type. */
    private static class ExtendedSchema extends JsonSchema {

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
        // supplies it, and these tests are about the special cases above.

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
