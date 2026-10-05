package io.github.synapse4j.json;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;

import org.jspecify.annotations.Nullable;

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
 * Which direction a caller wants follows from who produces the JSON. The decode schema is the one to
 * hand to whoever produces it — the arguments of a tool the model calls, the shape of a structured
 * answer. The encode schema describes what this codec produced, for whoever reads it: a model told
 * what a call returns, a caller shown a tool's result.
 *
 * <p>
 * A schema is worth generating only if it describes JSON the codec really moves, so the guarantee runs
 * one way: the schema never allows what the codec would refuse. {@link #decode(String, Type)} accepts
 * every document {@link #generateDecodeSchema(Type)} allows, and {@link #encode(Object)} produces
 * nothing {@link #generateEncodeSchema(Type)} forbids. A schema may be stricter than the codec — it is
 * a contract for whoever produces that JSON, so demanding more than a lenient binder would need is the
 * point, not a mistake. The two directions answer the binder's questions the same way — which
 * properties exist, what they are called, in what order — and may differ only in how much they demand;
 * an asymmetric binder is why they are separate, and one schema covering both would describe one of
 * them wrongly.
 *
 * <p>
 * Generating a schema is best-effort, because a type is not the whole story: a serializer or
 * deserializer registered for it, an annotation the implementation does not read, a feature of the
 * library it does not model — any of these can settle the JSON where the type does not. So an
 * implementation describes the types it can and no more, and a type it cannot describe faithfully is
 * the application's to describe: the application supplies that type's schema, and the serialization
 * behind it when the JSON is what the application settled. This interface cannot say how — the way to
 * hand an implementation a schema of your own is the implementation's to define, and is stated where
 * the implementation is.
 *
 * <p>
 * What a particular service accepts is not this interface's business: providers differ about which
 * keywords they honour, how they spell a nullable value, and whether every property must be listed as
 * required. Translating the {@link JsonSchema} into what a target takes is the provider module's job —
 * never a keyword here or an option on an implementation, since one codec serves every provider an
 * application talks to. Such a translation needs one thing from an implementation, so it is required
 * here: a property whose absence the codec tolerates must be one whose schema allows null. A protocol
 * that can express "optional" only as "nullable" has nothing else to go on.
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
 * {@code decode(json, JsonSchema.class)} reads one back. Every implementation carries that obligation.
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
    String encode(@Nullable Object value);

    /**
     * Reads JSON text into a value of the given type.
     *
     * @param <T>  the type of the value
     * @param json the JSON text to read; must not be {@code null}
     * @param type the type to read into, type arguments included; must not be {@code null}
     * @return the value; may be {@code null}
     */
    <T> @Nullable T decode(String json, Type type);

    /**
     * Reads a value that is already decoded — the maps, lists and scalars {@link #decode} or a
     * reader's {@code captureValue} produce — as a value of the given type.
     *
     * <p>
     * The default spells the value out as JSON text and reads it back, which every codec can do.
     * An implementation whose library converts a decoded value directly should say so here
     * instead: one pass over the value, with no text in between.
     *
     * <p>
     * A {@code null} value means the value is absent, and what absence becomes is the type's to
     * settle: {@code null} for a type that cannot spell it, the empty value for one that can — an
     * {@link java.util.Optional}, say. The implementation answers, so a caller never has to guess.
     *
     * @param <T>   the type of the value
     * @param value the decoded value to read as the given type; may be {@code null}, meaning absent
     * @param type  the type to read it as, type arguments included; must not be {@code null}
     * @return the value; may be {@code null}
     */
    default <T> @Nullable T convert(@Nullable Object value, Type type) {
        return decode(encode(value), type);
    }

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
