package io.github.synapse4j.spring.boot.tool;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import lombok.Data;

/**
 * The {@code synapse4j.tools.*} settings: how the starter reads the tools an application declares with
 * {@code @ToolMethod}, and what each one is configured to be.
 *
 * <p>
 * {@link #spel} and {@link #classNamePrefix} decide how a method's own text is read. {@link #spel}
 * turns on the resolution of expressions in it — {@code #{...}} SpEL and {@code ${...}} placeholders —
 * and is off by default, because an expression reaches the application's beans, and an annotation that
 * never meant to be one should not be read as one. {@link #classNamePrefix} decides what a tool the
 * annotation left unnamed is called.
 *
 * <p>
 * {@link #strict} is the one declaration attribute an application sets once for every tool. The others
 * have no application-wide meaning — a name, a description or a schema that is the same for every tool
 * describes none of them — so they are set per tool under {@link #methods}.
 *
 * <p>
 * A tool is configured under the name it carries before this configuration applies: the name its
 * annotation gave it, or, when the annotation named none, the method's own name — with the class name
 * in front of it when {@link #classNamePrefix} is on. A name written here does not move the entry it
 * came from, so a tool is always found under the name it had going in.
 */
@Data
public class ToolsProperties {

    /**
     * Whether the text an annotation wrote is resolved as an expression before it configures the tool.
     * Off by default: resolution reaches the application's beans, so it is turned on deliberately.
     */
    private boolean spel = false;

    /**
     * Whether a tool the annotation left unnamed is named after the class that declares it as well as
     * the method, so two classes may declare methods of one name without colliding.
     */
    private boolean classNamePrefix = false;

    /**
     * Whether every tool's schema has to be enforced by the provider rather than merely aimed at; the
     * value a tool falls to when neither its annotation nor its entry under {@link #methods} states one.
     * Absent leaves it to the annotation and then to the protocol, as it would without this starter.
     */
    private @Nullable String strict;

    /** One tool's overrides, keyed by the name the tool carries before this configuration applies. */
    private final Map<String, ToolMethodProperties> methods = new LinkedHashMap<>();

}
