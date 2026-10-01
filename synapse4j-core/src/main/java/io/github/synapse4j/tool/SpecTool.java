package io.github.synapse4j.tool;

import io.github.synapse4j.json.JsonCodec;

/**
 * A tool completed from a {@link ToolMethodSpec}: not ready when it is constructed, because the
 * resolution it is built from, and the codec that turns that resolution into a declaration and binds
 * arguments, arrive afterwards, in {@link #initialize}.
 *
 * <p>
 * The two steps are not decoration. Building the declaration asks the tool about each parameter — what
 * the model provides, and what it is called — so a subclass's answer has to be in place before the
 * question is asked, and a constructor is too early for that: a subclass's own fields are not set
 * while its constructor runs.
 *
 * <p>
 * A tool method names its implementation in {@code type}; whoever resolves that name constructs it and
 * hands it the resolution to complete, so what the name means is that resolver's affair. What the
 * constructed object has to accept is this interface's — the resolution and the codec, and nothing
 * else.
 */
public interface SpecTool extends Tool {

    /**
     * Completes this tool: reads the resolution of the method it was built from, and the codec that
     * generates its declaration and binds its arguments. Called exactly once, after construction and
     * before the tool is registered or asked anything; when it returns, the tool is ready to be used.
     *
     * @param spec  the resolution of the annotated method this tool was built from; never {@code null}
     * @param codec the codec that generates the declaration and binds arguments; never {@code null}
     */
    void initialize(ToolMethodSpec spec, JsonCodec codec);

}
