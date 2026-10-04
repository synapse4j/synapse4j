package io.github.synapse4j.tool;

import io.github.synapse4j.exception.SynapseException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.ToString;

/**
 * One annotated method, read: the Java side it was read from, and the values its annotations wrote.
 *
 * <p>
 * This is what a customizer sees and changes — a step of resolution, not a declaration: no schema
 * exists at this point, and neither does a tool. The Java side is fixed and read only: one entry per
 * declared parameter, in signature order, on a list that cannot grow or shrink. What a customizer
 * rewrites are the strings.
 *
 * <p>
 * A resolution is complete when it names the tool and every one of its parameters, and repeats no
 * name. {@link #validate} states that contract — here, rather than in a consumer, because it is
 * about this type's own fields. What a consumer does with the names is the consumer's business;
 * leaving one out is not.
 */
@Getter
@Setter
@ToString(exclude = "target")
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

    /** One per declared parameter, in declaration order; fixed when the signature is read. */
    private final List<ToolParameterSpec> parameters;

    /**
     * Reads a method's signature into a spec: one {@link ToolParameterSpec} per declared parameter,
     * carrying the reflection name — the starting point every resolver shares. Annotations and
     * configuration rewrite the strings from here; this constructor reads none of them.
     *
     * <p>
     * The entries are built here and the list is unmodifiable, so which parameters exist, and which
     * Java parameter each one stands for, is settled once and cannot drift afterwards. An instance
     * method needs the instance it runs on: a resolution that names one is not usable, and this is
     * the earliest place to say so.
     *
     * @param method the method to read; never {@code null}
     * @param target the instance an instance method runs on, {@code null} for a static one
     * @throws SynapseException if the method is an instance method and no target is given
     */
    public ToolMethodSpec(@NonNull Method method, @Nullable Object target) {
        if (!Modifier.isStatic(method.getModifiers()) && target == null) {
            throw new SynapseException("no target for " + method + ", which is an instance method");
        }
        this.method = method;
        this.target = target;
        List<ToolParameterSpec> parameters = new ArrayList<>(method.getParameterCount());
        for (Parameter parameter : method.getParameters()) {
            ToolParameterSpec argument = new ToolParameterSpec(parameter);
            argument.setName(parameter.getName());
            parameters.add(argument);
        }
        this.parameters = List.copyOf(parameters);
    }

    /**
     * Refuses a resolution that cannot become a tool: no name for the tool, a parameter left
     * unnamed, or two parameters under one name.
     *
     * <p>
     * Only the strings can be wrong here — the Java side is settled by the constructor — and the
     * strings are exactly what customizers rewrite, so this is the check to run after them, not
     * before. The failure is the library's, not a caller's: a spec is this library's own intermediate
     * structure, and this is where the result turns out unusable. Strings that mean "nothing
     * supplied" are not refused — a blank description or type is an ordinary resolution, and so is a
     * blank {@code required}.
     *
     * @throws SynapseException if the tool is unnamed, a parameter is unnamed, or two share a name
     */
    public void validate() {
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
    }
}
