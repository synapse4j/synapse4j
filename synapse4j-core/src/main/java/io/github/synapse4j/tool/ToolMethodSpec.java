package io.github.synapse4j.tool;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.synapse4j.data.ProviderExtras;
import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.json.JsonSchema;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import org.jspecify.annotations.Nullable;

/**
 * One method on its way to becoming a tool: the Java side it was read from, and the values its
 * annotations wrote.
 *
 * <p>
 * This is a container and nothing more — it reads no signature, fills no default, and refuses
 * nothing. {@link MethodTools} builds one and decides what goes into it, a
 * {@link ToolMethodSpecCustomizer} rewrites the strings, and {@link MethodTool} is what refuses a
 * resolution it could not be built from. Nothing is filled in along the way: a blank string is one
 * nobody supplied, which is what a customizer needs to tell from a value somebody wrote.
 *
 * <p>
 * The Java side is what the reader settled and does not change afterwards: the method, the instance
 * it runs on, and one entry per declared parameter, in signature order. The strings are written and
 * rewritten — the annotations write them, the customizers rewrite them, and the reader applies
 * whatever defaults are left over.
 *
 * <p>
 * The input schema is the same split as on a {@link ToolParameterSpec}: {@link #schema} is the
 * configuration surface, text written by an annotation or a customizer, and {@link #resolvedSchema} is
 * the conclusion — the envelope put together out of the parameters' own answers. {@link MethodTools}
 * writes it, and {@link MethodTool} reads it to build the declaration.
 */
@Getter
@Setter
@ToString(exclude = "target")
@RequiredArgsConstructor
public class ToolMethodSpec {

    /** The method the values were read from; the signature the declaration will be built from. */
    @NonNull
    private final Method method;

    /** The instance an instance method runs on; {@code null} for a static one. */
    private final @Nullable Object target;

    /** The name the tool is known by; blank when nothing supplied one. */
    @NonNull
    private String name = "";

    /** What the tool does; blank when nothing supplied it. */
    @NonNull
    private String description = "";

    /** Which implementation builds this tool; blank when nothing supplied one. */
    @NonNull
    private String type = "";

    /** One entry per declared parameter, in signature order; whatever the reader found. */
    @NonNull
    private final List<ToolParameterSpec> parameters;

    /** The whole input schema as a JSON document, instead of the one put together from the parameters. */
    @NonNull
    private String schema = "";

    /** Whether the provider must enforce the schema; blank when nothing supplied it. */
    @NonNull
    private String strict = "";

    /**
     * Provider-specific fields of the declaration. Nothing writes one from an annotation — a
     * {@link ToolMethodSpecCustomizer} does, out of whatever configuration it reads — so this is a
     * value rather than text, and {@code null} means none.
     */
    private @Nullable ProviderExtras extras;

    /**
     * The declaration's input schema as it stands: {@code null} while nothing has settled it, and
     * everything the model is shown of this tool's arguments in one value. {@link MethodTools} writes
     * it, {@link MethodTool} reads it.
     */
    private @Nullable JsonSchema resolvedSchema;

    /**
     * The declaration this resolution describes: the name and description it carries, the input schema
     * settled for it, and the two provider-side answers — the description blank when nobody wrote one,
     * and {@code strict} left to the protocol when nobody asked either way.
     *
     * <p>
     * A resolution that cannot become one is refused here rather than turned into a declaration with
     * holes in it: no name for the tool, a parameter left unnamed, two parameters under one name, or
     * nothing settled as the input schema. Only the strings can be wrong, since the Java side is what
     * the reader settled, and a blank description or type is an ordinary resolution rather than a
     * failure.
     *
     * @return this tool as the model sees it; never {@code null}
     * @throws SynapseException if the resolution cannot become a declaration
     */
    public ToolDefinition definition() {
        if (name.isBlank()) {
            throw new SynapseException("no name for the tool resolved from " + method);
        }
        Set<String> names = new HashSet<>();
        for (ToolParameterSpec entry : parameters) {
            if (entry.getName().isBlank()) {
                throw new SynapseException("no name to declare " + entry.getParameter() + " under");
            }
            if (!names.add(entry.getName())) {
                throw new SynapseException("'" + entry.getName() + "' names two parameters of " + method);
            }
        }
        if (resolvedSchema == null) {
            throw new SynapseException("no input schema settled for the tool resolved from " + method);
        }
        return new ToolDefinition(name, description.isBlank() ? null : description, resolvedSchema, strict(), extras);
    }

    /**
     * Whether the provider must enforce the schema, as {@link #strict} states it. Blank means nobody
     * said, which is not the same as asking for no enforcement: the flag is left unsent, and the
     * protocol's own default stands. A written value that is not exactly {@code "false"} asks for it.
     *
     * @return {@code true} to ask for enforcement, {@code false} to refuse it, {@code null} to leave it
     *         to the protocol
     */
    public @Nullable Boolean strict() {
        return strict.isBlank() ? null : !"false".equals(strict);
    }
}