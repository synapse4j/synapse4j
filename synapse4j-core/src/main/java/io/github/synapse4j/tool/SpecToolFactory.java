package io.github.synapse4j.tool;

import io.github.synapse4j.json.JsonCodec;

/**
 * Where the tool a {@link ToolMethodSpec} stands for comes from.
 *
 * <p>
 * A tool method does not build its tool itself: it names the implementation in its {@code type}, and
 * this turns that name — together with everything the spec says — into a tool that is ready to be
 * used. The default reads the name as a class and constructs it; an application that builds its
 * objects its own way — out of a container, say — supplies one of these instead, and then a name
 * means whatever that application says it means.
 *
 * <p>
 * What comes back is finished: it knows the method it was built from and carries the declaration that
 * method describes, so there is no completing step left to the caller. An implementation here
 * constructs a complete tool and nothing else.
 */
@FunctionalInterface
public interface SpecToolFactory {

    /**
     * Creates the tool this resolution stands for.
     *
     * @param spec  the resolution of the annotated method; never {@code null}
     * @param codec the codec that generates the declaration and binds arguments; never {@code null}
     * @return a new tool, ready to be used; never {@code null}
     */
    Tool create(ToolMethodSpec spec, JsonCodec codec);

}