package io.github.synapse4j.tool;

/**
 * A step in resolving an annotated method into a tool: what a value needs, changed; the rest, left
 * alone.
 *
 * <p>
 * One runs per annotated method, in the order they are given, before the declaration is built — so
 * what a step leaves is what the next one sees and what the tool ends up carrying. This is where a
 * value the annotations cannot state comes from: a description read from configuration, an expression
 * resolved against the application's own settings.
 *
 * <p>
 * The parameters are reached through the spec, so a step that cares about one of them reads it off
 * the {@link ToolMethodSpec} it is handed.
 */
@FunctionalInterface
public interface ToolMethodSpecCustomizer {

    /**
     * Changes one method's resolution.
     *
     * @param spec the resolution as it reached this step; never {@code null}
     */
    void customize(ToolMethodSpec spec);

}
