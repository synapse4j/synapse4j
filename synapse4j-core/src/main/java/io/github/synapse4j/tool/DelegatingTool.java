package io.github.synapse4j.tool;

import java.util.List;

import org.jspecify.annotations.Nullable;

import io.github.synapse4j.data.ChatContext;
import io.github.synapse4j.data.ContentPart;
import io.github.synapse4j.json.JsonSchema;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A tool that stands in for another: it answers a declaration of its own while running the tool
 * beneath, so a caller can change what the model is told without touching what actually runs.
 *
 * <p>
 * The declaration handed in is the one that reaches the wire, whatever the delegate's own says —
 * an argument schema reshaped for one protocol is the usual reason to reach for this. Execution
 * runs on the delegate untouched; the declaration is the whole of what this wrapper changes.
 */
@RequiredArgsConstructor
public class DelegatingTool implements Tool {

    /** The tool that runs when the model calls this one. */
    @NonNull
    private final Tool delegate;

    /** The declaration this tool presents, which may differ from the delegate's own. */
    @NonNull
    private final ToolDefinition definition;

    /**
     * A wrapper whose declaration is the delegate's own with its argument schema replaced — the
     * usual reason to reach for this class, and the one case worth a factory.
     *
     * @param delegate    the tool that runs when the model calls this one; never {@code null}
     * @param inputSchema the schema to present in place of the delegate's; never {@code null}
     * @return the wrapper; never {@code null}
     */
    public static DelegatingTool withInputSchema(@NonNull Tool delegate, @NonNull JsonSchema inputSchema) {
        ToolDefinition definition = delegate.definition();
        return new DelegatingTool(delegate, new ToolDefinition(definition.getName(), definition.getDescription(),
                inputSchema, definition.getStrict(), definition.getExtras()));
    }

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
     * {@inheritDoc}
     *
     * @param arguments the arguments the model produced, as JSON text
     * @param context   the conversation this call belongs to; {@code null} when none was attached
     * @return the parts the delegate answered with; never {@code null}
     * @throws Exception if execution fails — carried openly, decided by the caller
     */
    @Override
    public List<ContentPart> execute(@Nullable String arguments, @Nullable ChatContext context) throws Exception {
        return delegate.execute(arguments, context);
    }

}
