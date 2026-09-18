package io.github.synapse4j.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JsonCodecTest {

    @Test
    void encodeSchemaHandsTheCodecTheMapRatherThanTheSchema() {
        JsonSchema schema = new JsonSchema();
        schema.setType("object");
        schema.getExtras().put("$schema", "https://json-schema.org/draft/2020-12/schema");

        StubCodec codec = new StubCodec();
        String json = codec.encodeSchema(schema);

        assertEquals("encoded", json);
        assertEquals(schema.toMap(), codec.encoded);
        assertTrue(codec.encoded instanceof Map);
    }

    @Test
    void decodeSchemaBuildsTheSchemaFromTheDecodedMap() {
        StubCodec codec = new StubCodec();
        codec.decoded = Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string")));

        JsonSchema schema = codec.decodeSchema("{\"type\":\"object\"}");

        assertEquals(Map.class, codec.decodedType);
        assertEquals(List.of("object"), schema.getType());
        assertEquals(List.of("string"), schema.getProperties().get("name").getType());
    }

    @Test
    void aCodecOnlyHasToImplementTheThreeMethodsThatNeedAJsonLibrary() {
        JsonCodec codec = new StubCodec();

        assertNull(codec.generateSchema(String.class).getTitle());
        assertEquals("encoded", codec.encode(Map.of()));
    }

    /** A codec with no JSON library behind it: enough to pin down what the default methods hand over. */
    private static class StubCodec implements JsonCodec {

        private Object encoded;
        private Object decoded;
        private Type decodedType;

        @Override
        public JsonSchema generateSchema(Type type) {
            return new JsonSchema();
        }

        @Override
        public String encode(Object value) {
            this.encoded = value;
            return "encoded";
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T decode(String json, Type type) {
            this.decodedType = type;
            return (T) decoded;
        }

    }

}
