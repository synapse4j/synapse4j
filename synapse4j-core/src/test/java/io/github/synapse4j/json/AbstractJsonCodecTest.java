package io.github.synapse4j.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void readsAViewOverTheDocument() {
        StubCodec codec = new StubCodec();
        codec.decoded = Map.of("choices", List.of(Map.of("message", Map.of("content", "hi"))));

        JsonView view = codec.decode("{}", JsonView.class);

        assertEquals(Object.class, codec.decodedType);
        assertEquals("hi", view.get("choices").get(0).get("message").get("content").asText());
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

    }

    /** Nothing is final here, so a subclass can intercept a type of its own and delegate the rest. */
    private static class InterceptingCodec extends StubCodec {

        @Override
        public String encode(Object value) {
            return value instanceof String ? "intercepted" : super.encode(value);
        }

    }

}
