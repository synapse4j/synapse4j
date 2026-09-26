package io.github.synapse4j.tool;

import io.github.synapse4j.data.ChatContext;
import io.github.synapse4j.data.ContentPart;
import io.github.synapse4j.data.TextPart;
import io.github.synapse4j.json.JsonCodec;
import io.github.synapse4j.json.JsonSchema;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import lombok.NonNull;

/**
 * A {@link StagedTool} backed by a Java method: the signature becomes the declaration, the
 * model's text becomes the arguments, and the return becomes the result — with this class
 * holding the one act in the middle, the invoke.
 *
 * <p>
 * Construction is two steps on purpose. The constructor does only what needs no judgement —
 * assertions, accessibility, reading the signature into arrays the stages later live off — and
 * {@link #define} builds the declaration after {@code new} has returned, because building it
 * asks {@link #schemaFor}, and a subclass's hook must never run while the subclass is still
 * being constructed. The factories perform both steps; a subclass's own factory does the same
 * two.
 *
 * <p>
 * Everything the stages read from the signature is extracted up front — names, types, the
 * model-or-environment decision — so a call pays only array reads on this class's side; the
 * costs that remain are the codec reading the arguments and the invoke itself. Once defined, an
 * instance is immutable and shareable; the target, if it has state, is the application's to
 * make thread-safe.
 */
public class MethodTool implements StagedTool {

    /** The method to run. */
    private final Method method;

    /** The instance to run an instance method on; {@code null} for a static method. */
    private final @Nullable Object target;

    /** The codec reading arguments and rendering results. */
    private final JsonCodec codec;

    /** The declared parameters, read once — {@link Method#getParameters()} clones every call. */
    private final Parameter[] parameters;

    /** The name of each parameter, read once — absent names are synthesized per call by the JDK. */
    private final String[] names;

    /** The declared type of each parameter, read once. */
    private final Class<?>[] types;

    /** The generic type of each parameter, read once for the binding fallback. */
    private final Type[] genericTypes;

    /** Per parameter: {@code true} when the model produces its value — see {@link #schemaFor}. */
    private final boolean[] fromModel;

    /** Whether the method returns void — asked once for the result stage. */
    private final boolean returnsVoid;

    /** The declaration; set exactly once by {@link #define}. */
    private @Nullable ToolDefinition definition;

    /**
     * Reads the signature and holds everything the stages need; builds nothing yet. An instance
     * method needs a target; a static method takes {@code null}. The method is made accessible,
     * so a private method the application hands over runs like any other.
     *
     * @param method the method to run; never {@code null}
     * @param target the instance for an instance method, {@code null} for a static one
     * @param codec  the codec reading arguments and rendering results; never {@code null}
     */
    protected MethodTool(@NonNull Method method, @Nullable Object target, @NonNull JsonCodec codec) {
        this.method = method;
        this.codec = codec;
        if (!Modifier.isStatic(method.getModifiers())) {
            Objects.requireNonNull(target, "target must not be null: " + method + " is an instance method");
        }
        this.target = target;
        method.setAccessible(true);
        this.parameters = method.getParameters();
        this.returnsVoid = method.getReturnType() == void.class;
        this.names = new String[parameters.length];
        this.types = new Class<?>[parameters.length];
        this.genericTypes = new Type[parameters.length];
        this.fromModel = new boolean[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            names[i] = parameters[i].getName();
            types[i] = parameters[i].getType();
            genericTypes[i] = parameters[i].getParameterizedType();
        }
    }

    /**
     * A tool whose declaration comes from the method's signature, under the given name and
     * description: one property per parameter the model provides, each carrying the schema of
     * its declared type, all of them required.
     *
     * @param name        the name the model calls the tool by; never {@code null}
     * @param description what the tool does; never {@code null}
     * @param method      the method to run; never {@code null}
     * @param target      the instance for an instance method, {@code null} for a static one
     * @param codec       the codec reading arguments and rendering results; never {@code null}
     * @return the assembled tool, defined and ready
     */
    public static MethodTool of(String name, String description, Method method, @Nullable Object target,
            JsonCodec codec) {
        MethodTool tool = new MethodTool(method, target, codec);
        tool.define(name, description);
        return tool;
    }

    /**
     * A tool under a declaration the application assembled itself. The signature is still read
     * for binding: {@link #schemaFor} decides which parameters the model produces, whether or
     * not this declaration was built from that same decision — keep the two consistent.
     *
     * @param definition the declaration to carry; never {@code null}, and its name must be set
     * @param method     the method to run; never {@code null}
     * @param target     the instance for an instance method, {@code null} for a static one
     * @param codec      the codec reading arguments and rendering results; never {@code null}
     * @return the assembled tool, defined and ready
     */
    public static MethodTool of(ToolDefinition definition, Method method, @Nullable Object target, JsonCodec codec) {
        MethodTool tool = new MethodTool(method, target, codec);
        tool.define(definition);
        return tool;
    }

    /**
     * Sets the declaration built from the signature — the second step of construction, for this
     * class's factories and for a subclass's alike. Call it exactly once, after {@code new} has
     * returned: it asks {@link #schemaFor} for every parameter, and a subclass hook reads
     * subclass state that does not exist during construction.
     *
     * @param name        the name the model calls the tool by; never {@code null}
     * @param description what the tool does; never {@code null}
     */
    protected final void define(@NonNull String name, @NonNull String description) {
        JsonSchema envelope = new JsonSchema();
        envelope.setType("object");
        for (int i = 0; i < parameters.length; i++) {
            JsonSchema schema = schemaFor(parameters[i]);
            if (schema == null) {
                fromModel[i] = false;
                continue;
            }
            fromModel[i] = true;
            envelope.getProperties().put(names[i], schema);
            envelope.getRequired().add(names[i]);
        }
        this.definition = new ToolDefinition(name, description, codec.encode(envelope));
    }

    /**
     * Keeps a declaration the application assembled itself — the second step of construction
     * when the signature is not the source. The model-or-environment decision is still
     * {@link #schemaFor}'s; this only stores the declaration.
     *
     * @param definition the declaration to carry; never {@code null}, and its name must be set
     */
    protected final void define(@NonNull ToolDefinition definition) {
        Objects.requireNonNull(definition.getName(), "definition must have a name");
        for (int i = 0; i < parameters.length; i++) {
            fromModel[i] = schemaFor(parameters[i]) != null;
        }
        this.definition = definition;
    }

    /**
     * What this parameter is declared as to the model — the one place that decides both what
     * the schema contains and where the value comes from: a schema here means the model
     * produces the value and binding reads it from the arguments; {@code null} means the
     * parameter never reaches the model, so its value can only come from {@link #valueFor}.
     *
     * <p>
     * Called while the declaration is defined, never per call. Override to keep further types
     * off the wire or to shape what a parameter is declared as; call {@code super} to keep the
     * built-in split.
     *
     * @param parameter the declared parameter
     * @return the schema to send, or {@code null} to leave the parameter off the wire
     */
    protected @Nullable JsonSchema schemaFor(Parameter parameter) {
        if (ChatContext.class.isAssignableFrom(parameter.getType())) {
            return null;
        }
        return codec.generateDecodeSchema(parameter.getParameterizedType());
    }

    /**
     * The value of a parameter {@link #schemaFor} left off the wire — the environment's side of
     * the split.
     *
     * <p>
     * The default provides a {@link ChatContext}-typed parameter with the conversation itself,
     * {@code null} included, and refuses any other claimed type loudly rather than letting the
     * mismatch surface as an invoke failure: override this alongside {@link #schemaFor}.
     *
     * @param parameter the declared parameter
     * @param context   the conversation this call belongs to; {@code null} when none was attached
     * @return the value to pass; {@code null} when there is none
     * @throws IllegalStateException if the parameter is off the wire but no value is provided
     *                                   for its type — override this method for it
     */
    protected @Nullable Object valueFor(Parameter parameter, @Nullable ChatContext context) {
        if (ChatContext.class.isAssignableFrom(parameter.getType())) {
            return context;
        }
        throw new IllegalStateException("parameter '" + parameter.getName() + "' of type "
                + parameter.getType().getName()
                + " is off the wire but no value is provided for it; override valueFor()");
    }

    /**
     * The declaration, whether built from the signature or handed in.
     *
     * @return this tool as the model sees it; never {@code null} once {@link #define} has run
     * @throws IllegalStateException if the factory steps were not both taken
     */
    @Override
    public ToolDefinition definition() {
        if (definition == null) {
            throw new IllegalStateException(
                    "no declaration: construction ends with define(...), which this tool has not had");
        }
        return definition;
    }

    /**
     * One value per declared parameter: the model's for {@link #schemaFor} non-null parameters,
     * {@link #valueFor}'s for the rest. Keys the method does not declare are ignored; a missing
     * key for a primitive is an error rather than a null.
     *
     * @param arguments the arguments the model produced, as JSON text; {@code null} or blank
     *                      means the model produced none
     * @param context   the conversation this call belongs to; {@code null} when none was attached
     * @return one value per declared parameter; the array itself is never {@code null}, while an
     *         element is {@code null} when its parameter has no value
     * @throws Exception if the text cannot be read or a value does not fit its parameter
     */
    @Override
    public @Nullable Object[] resolveArguments(@Nullable String arguments, @Nullable ChatContext context)
            throws Exception {
        Object[] values = new Object[parameters.length];
        Map<String, Object> args = null;
        for (int i = 0; i < parameters.length; i++) {
            if (!fromModel[i]) {
                values[i] = valueFor(parameters[i], context);
                continue;
            }
            if (args == null) {
                args = decodeArguments(arguments);
            }
            values[i] = bind(i, args.get(names[i]));
        }
        return values;
    }

    /**
     * The invoke itself, wrapped only by the reflection layer on the way in: what the method
     * threw arrives as itself. The context is here for the stage's signature; reflection does
     * not use it.
     *
     * @param values  the values resolved for this call; the array itself is never {@code null},
     *                    while an element is {@code null} when its parameter has no value
     * @param context the conversation this call belongs to; unused by this implementation
     * @return what the method returned; {@code null} for void
     * @throws Exception if the method fails — the cause out of reflection's wrapper, unwrapped
     */
    @Override
    public @Nullable Object call(@Nullable Object[] values, @Nullable ChatContext context) throws Exception {
        try {
            return method.invoke(target, values);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(cause);
        }
    }

    /**
     * The parts for the answer: a single text part carrying {@code "Success"} for a void method,
     * the text itself for a String, and the codec's rendering for anything else — {@code null}
     * included, which renders as JSON null. The context is here for the stage's signature; the
     * default does not use it.
     *
     * @param returnValue what the method returned; {@code null} for void and for a null return
     * @param context     the conversation this call belongs to; unused by this implementation
     * @return the result as the model sees it; never {@code null}
     */
    @Override
    public List<ContentPart> resolveResult(@Nullable Object returnValue, @Nullable ChatContext context) {
        if (returnsVoid) {
            return List.of(new TextPart("Success"));
        }
        if (returnValue instanceof String text) {
            return List.of(new TextPart(text));
        }
        return List.of(new TextPart(codec.encode(returnValue)));
    }

    private Map<String, Object> decodeArguments(@Nullable String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        Map<String, Object> decoded = codec.decode(arguments, Map.class);
        return decoded == null ? Map.of() : decoded;
    }

    private @Nullable Object bind(int i, @Nullable Object raw) {
        if (raw == null) {
            if (types[i].isPrimitive()) {
                throw new IllegalArgumentException("parameter '" + names[i] + "' of "
                        + method.getDeclaringClass().getSimpleName() + "." + method.getName()
                        + " is required, but the model produced no value for it");
            }
            return null;
        }
        if (types[i].isInstance(raw)) {
            return raw;
        }
        return codec.decode(codec.encode(raw), genericTypes[i]);
    }

}
