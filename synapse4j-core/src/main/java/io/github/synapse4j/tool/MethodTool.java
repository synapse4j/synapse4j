package io.github.synapse4j.tool;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import io.github.synapse4j.json.JsonSchemaBuilder;
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

    /**
     * The codec reading arguments and rendering results, and the one {@link #schemaFor} reads while
     * this object is still being constructed.
     */
    private final JsonCodec codec;

    /** Per parameter: {@code true} when the model produces its value — see {@link #schemaFor}. */
    private final boolean[] fromModel;

    /** Whether the method returns void — asked once for the result stage. */
    private final boolean returnsVoid;

    /** The declaration built from the spec. */
    private final ToolDefinition definition;

    /**
     * Builds this tool from the method's resolution: reads the signature, settles which parameters
     * the model produces, then builds the declaration — the name and description the spec carries,
     * and the arguments schema {@link #argumentsSchema} puts together.
     *
     * <p>
     * The spec is validated first, so every way of building a tool starts from a resolution that is
     * complete and consistent; a blank description still means none.
     *
     * <p>
     * The declaration is built here, in the constructor, which means {@link #schemaFor} and
     * {@link #argumentsSchema} are called while a subclass of this class is still being
     * constructed. An override therefore cannot read the subclass's own fields — they are not
     * initialized yet — and must stand on what the parameters handed to it carry.
     *
     * @param spec  the resolution of the method this tool is built from; never {@code null}, and it
     *                  has to name the tool and every one of its parameters
     * @param codec the codec that generates the declaration and binds arguments; never {@code null}
     * @throws SynapseException if the spec names no tool, or leaves a parameter unnamed, or names
     *                              two of them under one name
     */
    public MethodTool(@NonNull ToolMethodSpec spec, @NonNull JsonCodec codec) {
        validate(spec);
        this.spec = spec;
        this.codec = codec;
        makeAccessible(spec.getMethod());
        List<ToolParameterSpec> entries = spec.getParameters();
        boolean[] fromModel = new boolean[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            fromModel[i] = schemaFor(entries.get(i).getParameter()) != null;
        }
        this.fromModel = fromModel;
        this.returnsVoid = spec.getMethod().getReturnType() == void.class;
        String description = spec.getDescription();
        this.definition = new ToolDefinition(spec.getName(), description.isBlank() ? null : description,
                argumentsSchema(spec));
    }

    /**
     * The schema the model is given for this tool's arguments: an object with one property per
     * parameter {@link #schemaFor} keeps on the wire, named by the spec's entry, described and
     * required as that entry says.
     *
     * <p>
     * Called while the declaration is defined, once, never on a call. Override to shape the envelope
     * itself — its type, keywords the per-parameter schemas never carry, or another arrangement of
     * them; take {@code super} to keep the built-in one and add to it. Binding is not this method's
     * to change: it follows what {@link #schemaFor} answered, so a declaration shaped away from
     * those properties is a mismatch the tool will not repair.
     *
     * <p>
     * The built-in implementation asks {@link #schemaFor} once more for every parameter it lists —
     * that answer was already needed, to settle the binding — so an override of {@code schemaFor}
     * has to answer the same thing both times.
     *
     * @param spec the resolution this tool was completed from; never {@code null}
     * @return the arguments schema to send; never {@code null}
     */
    protected JsonSchema argumentsSchema(ToolMethodSpec spec) {
        JsonSchemaBuilder envelope = new JsonSchemaBuilder().setType("object");
        Map<String, JsonSchema> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (ToolParameterSpec entry : spec.getParameters()) {
            JsonSchema schema = schemaFor(entry.getParameter());
            if (schema == null) {
                continue;
            }
            if (schema.asBoolean() == null && !entry.getDescription().isBlank()) {
                schema = JsonSchemaBuilder.from(schema).setDescription(entry.getDescription()).build();
            }
            properties.put(entry.getName(), schema);
            if (isRequired(entry)) {
                required.add(entry.getName());
            }
        }
        if (!properties.isEmpty()) {
            envelope.setProperties(properties);
        }
        if (!required.isEmpty()) {
            envelope.setRequired(required);
        }
        return envelope.build();
    }

    /**
     * Whether the model has to produce this argument. The annotation's word wins when it gave one;
     * otherwise the codec decides, asked through a {@link RequiredProbe}: the value is placed in a
     * property's position so a type the codec makes optional (an {@link java.util.Optional}, say) is
     * not required, and anything else is.
     */
    private boolean isRequired(ToolParameterSpec entry) {
        if (!entry.getRequired().isBlank()) {
            return !"false".equals(entry.getRequired());
        }
        Type valueType = entry.getParameter().getParameterizedType();
        return RequiredProbe.isRequired(codec.generateDecodeSchema(RequiredProbe.wrapping(valueType)));
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
     * The declaration, built from the signature when this tool was constructed.
     *
     * @return this tool as the model sees it; never {@code null}
     */
    @Override
    public ToolDefinition definition() {
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
        List<ToolParameterSpec> entries = spec.getParameters();
        @Nullable
        Object[] values = new Object[entries.size()];
        Map<String, Object> args = null;
        for (int i = 0; i < entries.size(); i++) {
            ToolParameterSpec entry = entries.get(i);
            if (!fromModel[i]) {
                values[i] = valueFor(entry.getParameter(), context);
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
