package io.github.synapse4j.jackson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;

import io.github.synapse4j.json.JsonSchema;
import io.github.synapse4j.json.JsonView;
import lombok.Data;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

class JacksonJsonCodecTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private final JacksonJsonCodec codec = new JacksonJsonCodec(jsonMapper);

    @Test
    void theEncodeSchemaNamesExactlyWhatWritingProduces() {
        assertEquals(keysOf(codec.encode(sampleOrder())), properties(codec.generateEncodeSchema(Order.class)));
        assertEquals(keysOf(codec.encode(new Sessions())), properties(codec.generateEncodeSchema(Sessions.class)));
        assertEquals(keysOf(codec.encode(sampleCredentials())),
                properties(codec.generateEncodeSchema(Credentials.class)));
        assertEquals(keysOf(codec.encode(new Helper())), properties(codec.generateEncodeSchema(Helper.class)));
        assertEquals(keysOf(codec.encode(new Hidden())), properties(codec.generateEncodeSchema(Hidden.class)));
        assertEquals(keysOf(codec.encode(new Point(1, 2))), properties(codec.generateEncodeSchema(Point.class)));
    }

    @Test
    void theDecodeSchemaNamesWhatReadingAccepts() {
        assertEquals(List.of("id", "labels", "quantity", "shipTo"), sorted(codec.generateDecodeSchema(Order.class)));
        assertEquals(List.of("password", "username"), sorted(codec.generateDecodeSchema(Credentials.class)));
        assertEquals(List.of("a"), properties(codec.generateDecodeSchema(Helper.class)));
        assertEquals(List.of("kept", "x"), sorted(codec.generateDecodeSchema(Hidden.class)));
        assertEquals(List.of("x", "y"), sorted(codec.generateDecodeSchema(Point.class)));
    }

    @Test
    void decodesIntoAJsonViewThatNavigatesTheDocument() {
        JsonView view = codec.decode("{\"choices\":[{\"message\":{\"content\":\"Hi\"},"
                + "\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7}}", JsonView.class);

        assertEquals("Hi", view.get("choices").get(0).get("message").get("content").asText());
        assertEquals("stop", view.get("choices").get(0).get("finish_reason").asText());
        assertEquals(11L, view.get("usage").get("prompt_tokens").asLong());
        assertTrue(view.get("usage").get("absent").isMissing());
    }

    @Test
    void aGetterWithoutASetterIsDescribedOnlyForWriting() {
        assertEquals(List.of("renamed", "sessions"), sorted(codec.generateEncodeSchema(Sessions.class)));
        assertTrue(properties(codec.generateDecodeSchema(Sessions.class)).isEmpty());
    }

    @Test
    void aPropertyThatIsOnlyWrittenOrOnlyReadLandsOnItsOwnSide() {
        assertEquals(List.of("token", "username"), properties(codec.generateEncodeSchema(Credentials.class)));
        assertEquals(List.of("password", "username"), sorted(codec.generateDecodeSchema(Credentials.class)));
    }

    @Test
    void aMethodThatIsNotAnAccessorIsNotDescribed() {
        assertEquals(List.of("a"), properties(codec.generateEncodeSchema(Helper.class)));
    }

    @Test
    void theOrderJacksonUsesIsTheOrderTheSchemaUses() {
        assertEquals(List.of("quantity", "id"), properties(codec.generateEncodeSchema(Order.class)).subList(0, 2));
    }

    @Test
    void theConfiguredNamingStrategyDecidesTheNames() {
        JacksonJsonCodec snakeCaseCodec = new JacksonJsonCodec(
                JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build());

        List<String> properties = properties(snakeCaseCodec.generateEncodeSchema(Order.class));

        assertEquals(keysOf(snakeCaseCodec.encode(sampleOrder())), properties);
        assertTrue(properties.contains("ship_to"), properties.toString());
    }

    @Test
    void theDefaultSchemaForbidsPropertiesItDoesNotDescribe() {
        JsonSchema schema = codec.generateEncodeSchema(Order.class);

        assertEquals(Boolean.FALSE, schema.getAdditionalProperties());
        assertFalse(schema.getExtras().toNestedMap().containsKey("$schema"));
    }

    @Test
    void aCustomizerCanChangeWhatTheGeneratedSchemaSays() {
        SchemaGenerator requiredGenerator = JacksonSchemaGenerators.encodeSchemaGenerator(jsonMapper,
                configBuilder -> configBuilder.forFields().withRequiredCheck(field -> true));
        JacksonJsonCodec customizedCodec = new JacksonJsonCodec(jsonMapper, requiredGenerator,
                JacksonSchemaGenerators.decodeSchemaGenerator(jsonMapper));

        assertTrue(codec.generateEncodeSchema(Order.class).getRequired().isEmpty());
        assertEquals(List.of("id", "labels", "quantity", "shipTo"),
                List.copyOf(new TreeSet<>(customizedCodec.generateEncodeSchema(Order.class).getRequired())));
    }

    @Test
    void theConfigBuilderCarriesTheSameDefaultsAsTheFactory() {
        SchemaGeneratorConfigBuilder configBuilder = JacksonSchemaGenerators.encodeSchemaConfigBuilder(jsonMapper);
        SchemaGenerator fromBuilder = new SchemaGenerator(configBuilder.build());

        assertEquals(codec.generateEncodeSchema(Order.class), readSchema(fromBuilder, Order.class));
    }

    @Test
    void aCustomizerAppliedToTheConfigBuilderReachesTheSchema() {
        SchemaGeneratorConfigBuilder configBuilder = JacksonSchemaGenerators.encodeSchemaConfigBuilder(jsonMapper);
        configBuilder.forFields().withRequiredCheck(field -> true);
        SchemaGenerator fromBuilder = new SchemaGenerator(configBuilder.build());

        JsonSchema schema = readSchema(fromBuilder, Order.class);

        assertEquals(List.of("id", "labels", "quantity", "shipTo"),
                List.copyOf(new TreeSet<>(schema.getRequired())));
    }

    @Test
    void writesAndReadsAValue() {
        String json = codec.encode(sampleOrder());

        Order order = codec.decode(json, Order.class);

        assertEquals("a-1", order.getId());
        assertEquals(2, order.getQuantity());
        assertEquals("Hangzhou", order.getShipTo().getCity());
        assertEquals(Map.of("channel", "web"), order.getLabels());
    }

    @Test
    void writesAndReadsASchemaThroughTheSameTwoMethods() {
        JsonSchema schema = codec.generateEncodeSchema(Order.class);

        String json = codec.encode(schema);

        assertEquals(codec.encode(schema.toMap()), json);
        assertEquals(schema, codec.decode(json, JsonSchema.class));
    }

    @Test
    void refusesAMapperOrGeneratorThatIsNotThere() {
        assertThrows(NullPointerException.class, () -> new JacksonJsonCodec(null, null, null));
    }

    @Test
    void genericTypeArgumentsShapeThePropertySchemas() {
        JsonSchema schema = codec.generateEncodeSchema(new TypeReference<Box<String>>() {
        }.getType());

        assertEquals(List.of("string"), schema.getProperties().get("content").getType());
        assertEquals(List.of("array"), schema.getProperties().get("items").getType());
        assertEquals(List.of("string"), schema.getProperties().get("items").getItems().getType());
    }

    @Test
    void aTypeUsedTwiceIsDefinedOnceAndReferenced() {
        JsonSchema schema = codec.generateEncodeSchema(new TypeReference<Box<Order>>() {
        }.getType());

        JsonSchema order = schema.getDefs().get("Order");

        assertEquals(List.of("string"), order.getProperties().get("id").getType());
        assertEquals("#/$defs/Order", schema.getProperties().get("content").getRef());
        assertEquals("#/$defs/Order", schema.getProperties().get("items").getItems().getRef());
    }

    @Test
    void aListTypeDescribesItsElement() {
        JsonSchema schema = codec.generateEncodeSchema(new TypeReference<List<Order>>() {
        }.getType());

        assertEquals(List.of("array"), schema.getType());
        assertEquals(List.of("string"), schema.getItems().getProperties().get("id").getType());
    }

    private List<String> properties(JsonSchema schema) {
        return List.copyOf(schema.getProperties().keySet());
    }

    private List<String> sorted(JsonSchema schema) {
        return List.copyOf(new TreeSet<>(schema.getProperties().keySet()));
    }

    private List<String> keysOf(String json) {
        Map<?, ?> map = jsonMapper.readValue(json, Map.class);
        List<String> keys = new ArrayList<>();
        map.keySet().forEach(key -> keys.add(String.valueOf(key)));
        return keys;
    }

    private JsonSchema readSchema(SchemaGenerator schemaGenerator, Class<?> type) {
        JavaType mapType = jsonMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class);
        return JsonSchema.fromMap(jsonMapper.convertValue(schemaGenerator.generateSchema(type), mapType));
    }

    private static Order sampleOrder() {
        Order order = new Order();
        order.setId("a-1");
        order.setQuantity(2);
        order.setLabels(Map.of("channel", "web"));
        Address address = new Address();
        address.setCity("Hangzhou");
        order.setShipTo(address);
        return order;
    }

    private static Credentials sampleCredentials() {
        Credentials credentials = new Credentials();
        credentials.setUsername("u");
        credentials.setPassword("p");
        credentials.setToken("t");
        return credentials;
    }

    @Data
    @JsonPropertyOrder({ "quantity", "id" })
    static class Order {

        private String id;

        private int quantity;

        private Address shipTo;

        private Map<String, String> labels;

    }

    @Data
    static class Address {

        private String city;

    }

    @Data
    static class Box<T> {

        private T content;

        private List<T> items;

    }

    /** Properties that exist only as getters: the mapper writes them and cannot read them back. */
    static class Sessions {

        private final List<String> sessionMap = new ArrayList<>();

        public List<String> getSessions() {
            return new ArrayList<>(sessionMap);
        }

        @JsonProperty("renamed")
        public String getNickname() {
            return "n";
        }

    }

    @Data
    static class Credentials {

        private String username;

        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        private String password;

        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        private String token;

    }

    /** A public method that is not an accessor, alongside a property whose name is one character. */
    @Data
    static class Helper {

        private int a;

        public int total() {
            return a * 2;
        }

    }

    @Data
    static class Hidden {

        private String kept;

        @JsonIgnore
        private String dropped;

        private String x;

    }

    record Point(int x, int y) {
    }

}
