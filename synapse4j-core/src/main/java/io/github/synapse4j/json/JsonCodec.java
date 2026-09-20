package io.github.synapse4j.json;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;

/**
 * Writes Java values as JSON text, reads JSON text back into Java values, derives the schema of the
 * JSON that crosses in either direction, and opens the writer and reader that move a document token
 * by token instead of building it.
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
 * No JSON library appears in these signatures — only JDK types and types owned by this library. An
 * implementation is where the dependency on Jackson, Gson or whatever else belongs, and an
 * application picks the implementation it wants by instantiating it. The codec itself is stateless
 * and therefore shareable between threads; the writers and readers it opens are not.
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

    /**
     * Opens a writer that puts one JSON document into the given sink.
     *
     * <p>
     * This is how a provider module sends a request without building it first: the document is
     * written token by token, so the payload is held once rather than three times over. The returned
     * writer carries one document and belongs to one thread — unlike this codec, it is not
     * shareable.
     *
     * <p>
     * The sink belongs to the caller: closing the writer releases its own buffers and never closes
     * the sink.
     *
     * @param out the sink to write to; must not be {@code null}
     * @return a writer bound to it; never {@code null}
     */
    JsonWriter writer(OutputStream out);

    /**
     * Opens a reader that takes one JSON document from the given source.
     *
     * <p>
     * The counterpart of {@link #writer(OutputStream)}: a response is walked token by token, so
     * nothing is built that the caller does not ask for. The returned reader carries one document
     * and belongs to one thread — unlike this codec, it is not shareable.
     *
     * <p>
     * The source belongs to the caller: closing the reader releases its own buffers and never closes
     * the source.
     *
     * @param in the source to read from; must not be {@code null}
     * @return a reader bound to it; never {@code null}
     */
    JsonReader reader(InputStream in);

}
