package io.github.synapse4j.schema;

import java.lang.reflect.Type;

/**
 * Writes Java values as JSON text, reads JSON text back into Java values, and derives the schema of
 * the JSON that crosses in either direction.
 *
 * <p>
 * A schema has a direction, because the two directions do not always describe the same JSON. A binder
 * may write a property it cannot read back, and read one it never writes: a getter with no setter is
 * written but not read, a property marked read-only is written but never read, one marked write-only
 * is read but never written. So there are two methods, each named after the operation whose JSON it
 * describes:
 *
 * <ul>
 * <li>{@link #generateEncodeSchema(Type)} describes what {@link #encode(Object)} writes — the JSON
 * this codec hands out;</li>
 * <li>{@link #generateDecodeSchema(Type)} describes what {@link #decode(String, Type)} accepts — the
 * JSON this codec takes in.</li>
 * </ul>
 *
 * <p>
 * A schema is only worth generating if it describes JSON the codec really moves, so both must be
 * derived from the same settings that bind values, and neither may describe a property the codec does
 * not move in that direction. Nothing here requires the two directions to agree: where a binder is
 * asymmetric, a schema that claimed to describe both would be describing one of them wrongly.
 *
 * <p>
 * No JSON library appears in these signatures — only {@link Object}, {@link String}, {@link Type} and
 * this library's own {@link JsonSchema}. An implementation is where the dependency on Jackson, Gson or
 * whatever else belongs, and an application picks the implementation it wants by instantiating it.
 * Implementations are stateless and therefore shareable between threads.
 *
 * <p>
 * {@link #generateDecodeSchema(Type)} takes a {@link Type} rather than a {@link Class} so that a
 * generic type arrives with its type arguments: {@code List<Order>} has to describe an array of
 * orders, not an array of anything.
 *
 * <p>
 * {@link JsonSchema} is a value like any other here: {@link #encode(Object)} writes one as the JSON
 * document it describes rather than as the fields of its class, and
 * {@code decode(json, JsonSchema.class)} reads one back. {@link AbstractJsonCodec} takes care of that
 * for an implementation; an implementation that does not extend it carries the same obligation.
 *
 * <p>
 * Decoding has one more library-mandated target: {@code decode(json, JsonView.class)} must read any
 * JSON document generically — object, array or scalar — into the null-safe {@link JsonView}, the way
 * provider modules navigate responses. An implementation that extends {@link AbstractJsonCodec}
 * gets this for free; a direct implementation of this interface carries the same obligation.
 */
public interface JsonCodec {

    /**
     * Generates the schema of the JSON that writing a value of the given type produces.
     *
     * <p>
     * This is the schema to hand to whoever reads that JSON: a model being asked to call a tool whose
     * result is this type, a client being told what a call returns.
     *
     * @param type the type to describe, type arguments included; must not be {@code null}
     * @return the schema; never {@code null}
     */
    JsonSchema generateEncodeSchema(Type type);

    /**
     * Generates the schema of the JSON that reading a value of the given type accepts.
     *
     * <p>
     * This is the schema to hand to whoever produces that JSON: a model being asked for arguments to a
     * tool taking this type, or for a structured response parsed into it.
     *
     * @param type the type to describe, type arguments included; must not be {@code null}
     * @return the schema; never {@code null}
     */
    JsonSchema generateDecodeSchema(Type type);

    /**
     * Writes a value as JSON text.
     *
     * @param value the value to write; may be {@code null}
     * @return the JSON text; never {@code null}
     */
    String encode(Object value);

    /**
     * Reads JSON text into a value of the given type.
     *
     * @param <T>  the type of the value
     * @param json the JSON text to read; must not be {@code null}
     * @param type the type to read into, type arguments included; must not be {@code null}
     * @return the value; may be {@code null}
     */
    <T> T decode(String json, Type type);

}
