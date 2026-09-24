package io.github.synapse4j.exception;

/**
 * A call named a tool the request did not carry — the model asking for something that was never
 * offered to it.
 *
 * <p>
 * The default executor raises this where a name resolves against nothing, and hands it to the
 * error policy like any other failure: the model can read that the tool does not exist and
 * answer accordingly, and a policy that wants to tell hallucinated names apart from real
 * failures does so by catching this type.
 */
public class ToolNotFoundException extends SynapseException {

    public ToolNotFoundException(String name) {
        super("no tool named \"" + name + '"');
    }

}
