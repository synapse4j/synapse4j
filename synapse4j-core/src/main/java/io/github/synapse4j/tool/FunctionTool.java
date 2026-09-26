package io.github.synapse4j.tool;

import io.github.synapse4j.data.ChatContext;
import io.github.synapse4j.data.ContentPart;
import io.github.synapse4j.data.TextPart;
import io.github.synapse4j.json.JsonCodec;
import io.github.synapse4j.json.JsonSchema;
import org.jspecify.annotations.Nullable;

import java.util.List;

import lombok.NonNull;

/**
 * A {@link StagedTool} for one typed lambda: the model's arguments are decoded into a single
 * value of the declared type, the lambda runs on it, and its return is rendered for the model.
 * JSON in both directions belongs to the {@link JsonCodec} — the same codec the application
 * chose for everything else — and the lambda is only the middle stage.
 *
 * <p>
 * The type comes from a token handed in at construction: a lambda carries no signature to
 * introspect, so {@code inputType} is where the knowledge lives. It has to describe an object,
 * because the protocol's arguments are an object — a scalar input type is refused at the
 * factory, with a pointer to wrap the parameters in a record.
 */
public class FunctionTool<I, O> implements StagedTool {

    /**
     * The typed middle stage: the decoded value in, the value to render out. Parsing and
     * rendering are not this lambda's business — that is the codec's; what happens between the
     * two is the application's.
     *
     * @param <I> the type the arguments decode into
     * @param <O> the type the lambda returns
     */
    @FunctionalInterface
    public interface Executor<I, O> {

        /**
         * Runs the tool on the decoded value.
         *
         * @param input   the value decoded from the model's arguments; never {@code null} for a
         *                    decodable document
         * @param context the conversation this call belongs to; {@code null} when none was
         *                    attached
         * @return what the tool produced — a String reaches the model as itself, anything else
         *         is rendered by the codec
         * @throws Exception if the tool fails — carried openly, decided by the caller
         */
        O execute(I input, @Nullable ChatContext context) throws Exception;
    }

    /** The declaration, whether generated from the token or handed in. */
    private final ToolDefinition definition;

    /** What the model's arguments decode into. */
    private final Class<I> inputType;

    /** The middle stage. */
    private final Executor<I, O> executor;

    /** The codec for both directions. */
    private final JsonCodec codec;

    /**
     * Assembles the tool.
     *
     * @param definition the declaration to carry; never {@code null}
     * @param inputType  what the arguments decode into; never {@code null}
     * @param executor   the middle stage; never {@code null}
     * @param codec      the codec for both directions; never {@code null}
     */
    protected FunctionTool(@NonNull ToolDefinition definition, @NonNull Class<I> inputType,
            @NonNull Executor<I, O> executor, @NonNull JsonCodec codec) {
        this.definition = definition;
        this.inputType = inputType;
        this.executor = executor;
        this.codec = codec;
    }

    /**
     * A tool whose declaration comes from the input type: one object schema, the type's.
     *
     * @param name        the name the model calls the tool by; never {@code null}
     * @param description what the tool does; never {@code null}
     * @param inputType   what the arguments decode into; never {@code null}, and it has to
     *                        describe an object
     * @param executor    the middle stage; never {@code null}
     * @param codec       the codec for both directions; never {@code null}
     * @param <I>         the type the arguments decode into
     * @param <O>         the type the executor returns
     * @return the assembled tool
     * @throws IllegalArgumentException if the input type does not describe an object — wrap
     *                                      the parameters in a record or a class
     */
    public static <I, O> FunctionTool<I, O> of(@NonNull String name, @NonNull String description,
            @NonNull Class<I> inputType, @NonNull Executor<I, O> executor, @NonNull JsonCodec codec) {
        JsonSchema schema = codec.generateDecodeSchema(inputType);
        if (!schema.getType().contains("object")) {
            throw new IllegalArgumentException("inputType " + inputType.getTypeName()
                    + " does not describe an object; the protocol's arguments are an object, so wrap the parameters in a record");
        }
        ToolDefinition definition = new ToolDefinition(name, description, codec.encode(schema));
        return new FunctionTool<>(definition, inputType, executor, codec);
    }

    /**
     * A tool under a declaration the application assembled itself — the input type is not
     * asked; keep the declaration consistent with what {@code inputType} decodes, the same
     * promise {@link MethodTool} makes when handed a declaration.
     *
     * @param definition the declaration to carry; never {@code null}
     * @param inputType  what the arguments decode into; never {@code null}
     * @param executor   the middle stage; never {@code null}
     * @param codec      the codec for both directions; never {@code null}
     * @param <I>        the type the arguments decode into
     * @param <O>        the type the executor returns
     * @return the assembled tool
     */
    public static <I, O> FunctionTool<I, O> of(@NonNull ToolDefinition definition, @NonNull Class<I> inputType,
            @NonNull Executor<I, O> executor, @NonNull JsonCodec codec) {
        return new FunctionTool<>(definition, inputType, executor, codec);
    }

    /**
     * The declaration of this tool, for the request.
     *
     * @return this tool as the model sees it; never {@code null}
     */
    @Override
    public ToolDefinition definition() {
        return definition;
    }

    /**
     * The model's arguments decoded into one value of the input type; arguments that never
     * arrived decode as {@code {}} — the stage's contract spells that "the model produced none",
     * and an empty object is what every protocol spells none as.
     *
     * @param arguments the arguments the model produced, as JSON text; {@code null} or blank
     *                      means the model produced none
     * @param context   the conversation this call belongs to; unused in this stage
     * @return the decoded value as the single element of an array
     * @throws Exception if the text cannot be decoded into the input type
     */
    @Override
    public @Nullable Object[] resolveArguments(@Nullable String arguments, @Nullable ChatContext context)
            throws Exception {
        if (arguments == null || arguments.isBlank()) {
            arguments = "{}";
        }
        return new Object[] { codec.decode(arguments, inputType) };
    }

    /**
     * The lambda itself — the reflection-less counterpart of {@link MethodTool}'s invoke.
     *
     * @param values  the decoded value from {@link #resolveArguments}; the array itself is never
     *                    {@code null}, while its single element may be {@code null}
     * @param context the conversation this call belongs to; passed straight through
     * @return what the lambda returned
     * @throws Exception if the lambda fails — carried openly
     */
    @Override
    public @Nullable Object call(@Nullable Object[] values, @Nullable ChatContext context) throws Exception {
        return executor.execute(inputType.cast(values[0]), context);
    }

    /**
     * The parts for the answer: a single text part with the text itself for a String, and one
     * carrying the codec's rendering for anything else — {@code null} included, which renders as
     * JSON null.
     *
     * @param returnValue what the lambda returned; may be {@code null}
     * @param context     the conversation this call belongs to; unused in this stage
     * @return the result as the model sees it; never {@code null}
     */
    @Override
    public List<ContentPart> resolveResult(@Nullable Object returnValue, @Nullable ChatContext context) {
        if (returnValue instanceof String text) {
            return List.of(new TextPart(text));
        }
        return List.of(new TextPart(codec.encode(returnValue)));
    }

}
