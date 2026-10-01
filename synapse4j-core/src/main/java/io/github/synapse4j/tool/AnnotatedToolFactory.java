package io.github.synapse4j.tool;

/**
 * Where an {@link AnnotatedTool} comes from, given the name a tool method states in its {@code type}.
 *
 * <p>
 * A tool method does not build its tool itself: it names the implementation, and this turns that name
 * into an instance of one. The default reads the name as a class and constructs it reflectively; an
 * application that builds its objects its own way — out of a container, say — supplies one of these
 * instead, and then a name means whatever that application says it means.
 *
 * <p>
 * What comes back is not yet a usable tool: it is handed to {@link AnnotatedTool#initialize}, which is
 * where it learns the method it was built from. So an implementation here constructs, and nothing
 * more.
 */
@FunctionalInterface
public interface AnnotatedToolFactory {

    /**
     * Creates the tool a name stands for.
     *
     * @param type the name a tool method stated; never {@code null}
     * @return a new tool, not yet initialized; never {@code null}
     */
    AnnotatedTool create(String type);

}
