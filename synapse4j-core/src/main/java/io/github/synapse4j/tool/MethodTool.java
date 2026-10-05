package io.github.synapse4j.tool;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import io.github.synapse4j.data.ChatContext;
import io.github.synapse4j.data.ContentPart;
import io.github.synapse4j.data.TextPart;
import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.json.JsonCodec;
import io.github.synapse4j.json.JsonSchema;
import lombok.NonNull;

/**
 * A {@link StagedTool} backed by a Java method: the signature becomes the declaration, the
 * model's text becomes the arguments, and the return becomes the result — with this class
 * holding the one act in the middle, the invoke.
 *
 * <p>
 * An instance is complete the moment {@code new} returns: the constructor reads the method's
 * resolution, settles which parameters the model produces, and builds the declaration. What a
 * subclass has to know about that is that the declaration is built <em>inside</em> its constructor,
 * so {@link #schemaFor} and {@link #argumentsSchema} run while the subclass's own fields are not
 * initialized yet and an override cannot read them. An override stands on what the parameters
 * handed to it carry, and on what this class has already set.
 *
 * <p>
 * The spec is the one carrier of everything about the method — its signature, its parameters, and
 * the names the declaration uses. Renaming a parameter is a spec customizer's job, not this
 * class's. What the tool adds is the one thing the run cannot read off the spec: whether the model
 * produces each parameter's value, one {@code boolean} per parameter, decided by
 * {@link #schemaFor}.
 *
 * <p>
 * Every field is final and set in the constructor, so an instance is immutable once it exists and
 * its methods may be called from any thread. The target, if it has state, is the application's to
 * make thread-safe.
 */
public class MethodTool implements StagedTool {

    /** The resolution this tool was built from; the run reads its method and its names from here. */
    private final ToolMethodSpec spec;

    /** The codec the arguments are bound with and the result rendered by. */
    private final JsonCodec codec;

    /** Whether the method returns void — asked once for the result stage. */
    private final boolean returnsVoid;

    /** The declaration built from the spec. */
    private final ToolDefinition definition;

    /**
     * Builds this tool from a settled resolution: the declaration is the one the spec carries, and
     * every call binds the arguments the model produces and takes the rest from the parameter's own
     * {@link ToolParameterValueProvider}.
     *
     * <p>
     * There is nothing to settle here. The spec is the conclusion — {@link MethodTools} reads a method
     * into one, the customizers rewrite it, {@link FinalToolMethodSpecCustomizer} fills what is left
     * blank, and what arrives is finished. A spec that still has blanks in it is one this constructor
     * refuses, rather than one it quietly completes.
     *
     * @param spec  the resolution of the method this tool is built from; never {@code null}, and it has
     *                  to name the tool, name every one of its parameters, and carry an input schema
     * @param codec the codec that binds arguments and renders results; never {@code null}
     * @throws SynapseException if the spec names no tool, leaves a parameter unnamed, names two of them
     *                              under one name, or carries no input schema
     */
    public MethodTool(@NonNull ToolMethodSpec spec, @NonNull JsonCodec codec) {
        validate(spec);
        this.spec = spec;
        this.codec = codec;
        makeAccessible(spec.getMethod());
        this.returnsVoid = spec.getMethod().getReturnType() == void.class;
        String description = spec.getDescription();
        this.definition = new ToolDefinition(spec.getName(), description.isBlank() ? null : description,
                inputSchema(spec));
    }

    /**
     * Refuses a resolution this tool cannot be built from: no name for the tool, a parameter left
     * unnamed, or two parameters under one name.
     *
     * <p>
     * The refusal lives here rather than on the resolution because this is where it bites — every
     * way of completing a tool goes through {@link #initialize}, and the spec itself only carries
     * values. Only the strings can be wrong, since the Java side is what the reader settled, and a
     * blank description or type is an ordinary resolution rather than a failure.
     *
     * @param spec the resolution this tool is being completed from; never {@code null}
     * @throws SynapseException if the tool is unnamed, a parameter is unnamed, or two share a name
     */
    private static void validate(ToolMethodSpec spec) {
        if (spec.getName().isBlank()) {
            throw new SynapseException("no name for the tool resolved from " + spec.getMethod());
        }
        Set<String> names = new HashSet<>();
        for (ToolParameterSpec entry : spec.getParameters()) {
            if (entry.getName().isBlank()) {
                throw new SynapseException("no name to declare " + entry.getParameter() + " under");
            }
            if (!names.add(entry.getName())) {
                throw new SynapseException(
                        "'" + entry.getName() + "' names two parameters of " + spec.getMethod());
            }
        }
    }

    /**
     * Makes the method accessible so a private one the application hands over runs like any other.
     *
     * @param method the method the tool will run; never {@code null}
     * @throws SynapseException if the module path refuses to open it
     */
    private static void makeAccessible(Method method) {
        try {
            method.setAccessible(true);
        } catch (RuntimeException failure) {
            // A module path refuses this for a package that was not opened: the caller has to open
            // it, so the message names the method and the package it lives in.
            throw new SynapseException("cannot reach " + method + ": open package "
                    + method.getDeclaringClass().getPackageName()
                    + " to this library, for example with --add-opens", failure);
        }
    }

    /**
     * The input schema the reader settled for this tool; a resolution without one is one this tool
     * refuses, since the declaration has to show the model something.
     *
     * @param spec the resolution this tool is being built from; never {@code null}
     * @return the input schema the declaration carries; never {@code null}
     * @throws SynapseException if nothing settled one
     */
    private static JsonSchema inputSchema(ToolMethodSpec spec) {
        JsonSchema schema = spec.getResolvedSchema();
        if (schema == null) {
            throw new SynapseException("no input schema settled for the tool resolved from " + spec.getMethod());
        }
        return schema;
    }

    /**
     * The value of a parameter the model does not produce: what the parameter's own
     * {@link ToolParameterValueProvider} answers. The built-in one is settled by
     * {@link FinalToolMethodSpecCustomizer}, which is also where an application's own arrives from.
     *
     * <p>
     * A parameter off the wire with nothing to fill it is refused loudly here rather than letting the
     * mismatch surface as an invoke failure — a null for a primitive, or an argument the method cannot
     * use.
     *
     * @param entry   the parameter to fill; never {@code null}
     * @param context the conversation this call belongs to; {@code null} when none was attached
     * @return the value to pass; {@code null} when there is none
     * @throws IllegalStateException if the parameter is off the wire and nothing supplies its value
     */
    private @Nullable Object valueFor(ToolParameterSpec entry, @Nullable ChatContext context) {
        ToolParameterValueProvider provider = entry.getValueProvider();
        if (provider == null) {
            throw new IllegalStateException("parameter '" + entry.getName() + "' of type "
                    + entry.getParameter().getType().getName()
                    + " is off the wire but no value is provided for it; write one with a customizer");
        }
        return provider.get(spec, entry, context);
    }

    /**
     * The declaration, built from the signature when this tool was constructed.
     *
     * @return this tool as the model sees it; never {@code null}
     */
    @Override
    public ToolDefinition definition() {
        return definition;
    }

    /**
     * One value per declared parameter: the model's for the parameters it produces, the parameter's
     * own {@link ToolParameterValueProvider}'s for the rest. Keys the method does not declare are
     * ignored; a missing key for a primitive is an error rather than a null.
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
        List<ToolParameterSpec> entries = spec.getParameters();
        @Nullable
        Object[] values = new Object[entries.size()];
        Map<String, Object> args = null;
        for (int i = 0; i < entries.size(); i++) {
            ToolParameterSpec entry = entries.get(i);
            if (!entry.fromModel()) {
                values[i] = valueFor(entry, context);
                continue;
            }
            if (args == null) {
                args = decodeArguments(arguments);
            }
            values[i] = bind(entry, args.get(entry.getName()));
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
            return spec.getMethod().invoke(spec.getTarget(), values);
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

    private @Nullable Object bind(ToolParameterSpec entry, @Nullable Object raw) {
        Parameter parameter = entry.getParameter();
        if (raw == null && parameter.getType().isPrimitive()) {
            Method method = spec.getMethod();
            throw new IllegalArgumentException("parameter '" + entry.getName() + "' of "
                    + method.getDeclaringClass().getSimpleName() + "." + method.getName()
                    + " is required, but the model produced no value for it");
        }
        return codec.convert(raw, parameter.getParameterizedType());
    }

}
