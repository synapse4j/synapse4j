package io.github.synapse4j.tool;

import java.util.List;

import io.github.synapse4j.data.ChatContext;
import io.github.synapse4j.data.ContentPart;
import org.jspecify.annotations.Nullable;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A tool declared to the model and run by the application: the manual round trip's counterpart to
 * {@link FunctionTool} and {@link MethodTool}.
 *
 * <p>
 * The declaration is the whole of what this class carries. An application that manages execution
 * itself sends one of these in the request and answers the call from its own machinery — so what the
 * model may call and what actually runs are decided in two places, deliberately, and this class
 * holds only the first.
 *
 * <p>
 * A call that reaches {@link #execute} anyway — an automatic loop met a tool it was never meant to
 * run — fails with {@link UnsupportedOperationException}, which the executor hands to its error
 * policy like any other failure. The model then reads that this tool is not run here, rather than
 * seeing a success nobody performed.
 */
@RequiredArgsConstructor
public class ManualTool implements Tool {

    /**
     * The declaration this tool carries, and all it carries. Never {@code null}: a tool with no
     * declaration would be no tool at all.
     */
    @NonNull
    private final ToolDefinition definition;

    /**
     * The declaration of this tool, for the request.
     *
     * @return this tool as the model sees it; never {@code null}
     */
    @Override
    public ToolDefinition definition() {
        return definition;
    }

    /**
     * Refuses: running this tool is the application's to do, so a call arriving here is a wiring
     * mistake rather than something to answer.
     *
     * @param arguments the arguments the model produced, as JSON text
     * @param context   the conversation this call belongs to; {@code null} when none was attached
     * @return nothing — this method always throws
     * @throws UnsupportedOperationException always
     */
    @Override
    public List<ContentPart> execute(@Nullable String arguments, @Nullable ChatContext context) {
        throw new UnsupportedOperationException(
                "tool '" + definition.getName() + "' is declared only; the application runs it");
    }

}
