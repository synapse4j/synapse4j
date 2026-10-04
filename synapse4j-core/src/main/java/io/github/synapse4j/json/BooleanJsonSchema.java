package io.github.synapse4j.json;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import org.jspecify.annotations.Nullable;

import lombok.EqualsAndHashCode;

/**
 * The boolean form of a schema: {@code true} accepts everything, {@code false} accepts nothing.
 *
 * <p>
 * It carries no keyword, so every getter that answers for one answers empty, and it has no sub-schema,
 * so a walk visits only this node. An instance is immutable and may be shared.
 */
@EqualsAndHashCode
public class BooleanJsonSchema implements JsonSchema {

    /** The schema that accepts everything. */
    public static final BooleanJsonSchema TRUE = new BooleanJsonSchema(true);

    /** The schema that accepts nothing. */
    public static final BooleanJsonSchema FALSE = new BooleanJsonSchema(false);

    /** The value this schema is: {@code true} or {@code false}. */
    private final boolean value;

    /**
     * Creates the boolean schema for the given value.
     *
     * @param value {@code true} to accept everything, {@code false} to accept nothing
     */
    public BooleanJsonSchema(boolean value) {
        this.value = value;
    }

    @Override
    public @Nullable Boolean asBoolean() {
        return value;
    }

    @Override
    public @Nullable List<String> getType() {
        return null;
    }

    @Override
    public @Nullable String getTitle() {
        return null;
    }

    @Override
    public @Nullable String getDescription() {
        return null;
    }

    @Override
    public @Nullable Map<String, JsonSchema> getProperties() {
        return null;
    }

    @Override
    public @Nullable List<String> getRequired() {
        return null;
    }

    @Override
    public @Nullable JsonSchema getItems() {
        return null;
    }

    @Override
    public @Nullable JsonSchema getAdditionalProperties() {
        return null;
    }

    @Override
    public @Nullable String getRef() {
        return null;
    }

    @Override
    public @Nullable Map<String, JsonSchema> getDefs() {
        return null;
    }

    @Override
    public Set<String> keys() {
        return Set.of();
    }

    @Override
    public @Nullable Object get(String keyword) {
        return null;
    }

    @Override
    public <T> @Nullable T get(String keyword, Class<?> type) {
        return null;
    }

    @Override
    public void visit(Consumer<JsonSchema> visitor) {
        visitor.accept(this);
    }

    @Override
    public JsonSchema map(UnaryOperator<JsonSchema> fn) {
        return fn.apply(this);
    }

    @Override
    public String toString() {
        return Boolean.toString(value);
    }

}
