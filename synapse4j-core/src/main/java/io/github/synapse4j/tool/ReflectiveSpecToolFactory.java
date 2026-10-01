package io.github.synapse4j.tool;

import java.util.function.Supplier;

import io.github.synapse4j.exception.SynapseException;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The default {@link SpecToolFactory}: a blank name is built from a supplier handed in, and any other
 * name is a class, loaded by name and constructed with its no-argument constructor.
 *
 * <p>
 * The blank case is the one every tool method meets until it states a {@code type}, and what a tool is
 * built from when the method says nothing is the application's decision rather than the method's — so
 * it is handed in here rather than fixed.
 *
 * <p>
 * A name that cannot be loaded, that does not implement {@link SpecTool}, or whose class has no
 * no-argument constructor fails here, naming the class: a mistake in a tool method's {@code type} is
 * worth finding at startup rather than on the first call.
 *
 * <p>
 * It holds nothing but the supplier and is safe to share. An application whose classes this one cannot
 * reach by name — loaded by a container of its own, say — supplies a factory of its own instead.
 */
@RequiredArgsConstructor
public class ReflectiveSpecToolFactory implements SpecToolFactory {

    /** What a blank name is built from; never {@code null}. */
    @NonNull
    private final Supplier<SpecTool> defaultTool;

    /**
     * Creates the tool the name stands for: the supplier's for a blank name, otherwise a new instance
     * of the class the name is.
     *
     * @param type the name of the class to construct, empty for the default tool; never {@code null}
     * @return the constructed tool, not yet initialized; never {@code null}
     * @throws SynapseException if the class cannot be loaded, does not implement {@link SpecTool}, or
     *                              has no no-argument constructor
     */
    @Override
    public SpecTool create(String type) {
        if (type.isEmpty()) {
            return defaultTool.get();
        }
        Class<?> toolClass;
        try {
            toolClass = Class.forName(type);
        } catch (ClassNotFoundException e) {
            throw new SynapseException("tool type '" + type + "' was not found", e);
        }
        if (!SpecTool.class.isAssignableFrom(toolClass)) {
            throw new SynapseException(
                    "tool type '" + type + "' does not implement " + SpecTool.class.getName());
        }
        try {
            return (SpecTool) toolClass.getConstructor().newInstance();
        } catch (NoSuchMethodException e) {
            throw new SynapseException("tool type '" + type + "' has no no-argument constructor", e);
        } catch (ReflectiveOperationException e) {
            throw new SynapseException("tool type '" + type + "' could not be constructed", e);
        }
    }

}
