package io.github.synapse4j.tool;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * An instance is constructed empty and completed once, by {@link #initialize} — the
 * {@link SpecTool} contract, which this class's factories follow too: they read the method into a
 * {@link ToolMethodSpec}, construct, and initialize. Completion runs after {@code new} has
 * returned on purpose, because building the declaration asks {@link #schemaFor} about each
 * parameter, and a subclass's hook must never run while the subclass is still being constructed.
 *
 * <p>
 * The spec is the one carrier of everything about the method — its signature, its parameters, and
 * the names the declaration uses. Renaming a parameter is a spec customizer's job, not this
 * class's. What the tool adds is the one thing the run cannot read off the spec: whether the model
 * produces each parameter's value, one {@code boolean} per parameter, decided by
 * {@link #schemaFor} at completion.
 *
 * <p>
 * Once initialized, an instance is immutable and its methods may be called from any thread;
 * completion must precede the handover, which the {@link SpecTool} contract already sequences —
 * {@code initialize} runs before the tool is registered or asked anything. The target, if it has
 * state, is the application's to make thread-safe.
 */
public class MethodTool implements SpecTool, StagedTool {

    /** The codec reading arguments and rendering results. */
    private JsonCodec codec;

    /**
     * The method this tool runs, read — the resolution it was completed from when a spec drove
     * the declaration, and the bare signature when the declaration was handed in. The run reads
     * its parameters and their names from here.
     */
    private ToolMethodSpec spec;

    /** Per parameter: {@code true} when the model produces its value — see {@link #schemaFor}. */
    private boolean[] fromModel;

    /** Whether the method returns void — asked once for the result stage. */
    private boolean returnsVoid;

    /**
     * The declaration, set exactly once by completion. Every completion path sets it, so a
     * {@code null} here means the tool was never completed — the contract violation
     * {@link #definition()} answers.
     */
    private ToolDefinition definition;

    // The SpecTool path constructs empty and completes in initialize; the suppression answers
    // NullAway's "field not initialized" for exactly that window. Nothing reads the fields
    // before initialize runs — that is the interface's contract, not an accident to recheck.
    @SuppressWarnings("NullAway.Init")
    public MethodTool() {
    }

    /**
     * Completes this tool from the method's resolution: reads the signature, settles which
     * parameters the model produces, then builds the declaration — the name and description the
     * spec carries, and the arguments schema {@link #argumentsSchema} puts together.
     *
     * <p>
     * The spec is validated first, so every way of completing a tool starts from a resolution that
     * is complete and consistent; a blank description still means none.
     *
     * @param spec  the resolution of the method this tool was built from; never {@code null}, and
     *                  it must pass {@link ToolMethodSpec#validate()}
     * @param codec the codec that generates the declaration and binds arguments; never
     *                  {@code null}
     * @throws SynapseException if the spec does not validate
     */
    @Override
    public void initialize(@NonNull ToolMethodSpec spec, @NonNull JsonCodec codec) {
        readSignature(spec, codec);
        List<ToolParameterSpec> entries = spec.getParameters();
        for (int i = 0; i < entries.size(); i++) {
            fromModel[i] = schemaFor(entries.get(i).getParameter()) != null;
        }
        String description = spec.getDescription();
        this.definition = new ToolDefinition(spec.getName(), description.isBlank() ? null : description,
                codec.encode(argumentsSchema(spec)));
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
            if (!"false".equals(entry.getRequired())) {
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
     * A tool whose declaration comes from the method's signature, under the given name and
     * description: one property per parameter the model provides, each carrying the schema of
     * its declared type, all of them required.
     *
     * @param name        the name the model calls the tool by; never {@code null}
     * @param description what the tool does; never {@code null}
     * @param method      the method to run; never {@code null}
     * @param target      the instance for an instance method, {@code null} for a static one
     * @param codec       the codec reading arguments and rendering results; never {@code null}
     * @return the assembled tool, initialized and ready
     */
    public static MethodTool of(String name, String description, Method method, @Nullable Object target,
            JsonCodec codec) {
        ToolMethodSpec spec = new ToolMethodSpec(method, target);
        spec.setName(name);
        spec.setDescription(description);
        MethodTool tool = new MethodTool();
        tool.initialize(spec, codec);
        return tool;
    }

    /**
     * Validates the spec once, so the stages that follow read it without rechecking, and makes the
     * method accessible so a private one the application hands over runs like any other.
     */
    private void readSignature(ToolMethodSpec spec, JsonCodec codec) {
        spec.validate();
        this.spec = spec;
        this.codec = codec;
        Method method = spec.getMethod();
        try {
            method.setAccessible(true);
        } catch (RuntimeException failure) {
            // A module path refuses this for a package that was not opened: the caller has to open
            // it, so the message names the method and the package it lives in.
            throw new SynapseException("cannot reach " + method + ": open package "
                    + method.getDeclaringClass().getPackageName()
                    + " to this library, for example with --add-opens", failure);
        }
        this.returnsVoid = method.getReturnType() == void.class;
        this.fromModel = new boolean[spec.getParameters().size()];
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
     * @return this tool as the model sees it; never {@code null} once the tool is initialized
     * @throws IllegalStateException if completion never ran — {@link #initialize} or a factory
     *                                   step is missing
     */
    @Override
    public ToolDefinition definition() {
        // Non-null in every state the class allows; this answers the one it does not — being
        // asked before completion, which the SpecTool contract forbids.
        if (definition == null) {
            throw new IllegalStateException(
                    "no declaration: a MethodTool is completed by initialize(...) or assembled by an of(...) factory, and this one has had neither");
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
        if (raw == null) {
            if (parameter.getType().isPrimitive()) {
                Method method = spec.getMethod();
                throw new IllegalArgumentException("parameter '" + entry.getName() + "' of "
                        + method.getDeclaringClass().getSimpleName() + "." + method.getName()
                        + " is required, but the model produced no value for it");
            }
            return null;
        }
        if (parameter.getType().isInstance(raw)) {
            return raw;
        }
        return codec.convert(raw, parameter.getParameterizedType());
    }

}
