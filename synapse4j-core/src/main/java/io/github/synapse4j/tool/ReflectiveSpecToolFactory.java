package io.github.synapse4j.tool;

import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.json.JsonCodec;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The default {@link SpecToolFactory}: a blank {@code type} is built by the factory handed in, and any
 * other one names a class, loaded by name and constructed from the resolution.
 *
 * <p>
 * The blank case is the one every tool method meets until it states a {@code type}, and what a tool is
 * built from when the method says nothing is the application's decision rather than the method's — so
 * it is handed in here rather than fixed. It is a {@link SpecToolFactory} like this one, because a
 * blank type is not a different kind of question: the resolution still has to become a tool.
 *
 * <p>
 * A name that cannot be loaded, that is not a {@link Tool}, or whose class has no constructor taking a
 * resolution and a codec fails here, naming the class: a mistake in a tool method's {@code type} is
 * worth finding at startup rather than on the first call.
 *
 * <p>
 * It holds nothing but that factory and is safe to share. An application whose classes this one cannot
 * reach by name — loaded by a container of its own, say — supplies a factory of its own instead.
 */
@RequiredArgsConstructor
public class ReflectiveSpecToolFactory implements SpecToolFactory {

    /** What a blank type is built by; never {@code null}. */
    @NonNull
    private final SpecToolFactory defaultFactory;

    /**
     * Creates the tool the resolution stands for: the handed-in factory's for a blank type, otherwise a
     * new instance of the class the type names.
     *
     * @param spec  the resolution of the annotated method; never {@code null}
     * @param codec the codec that generates the declaration and binds arguments; never {@code null}
     * @return the constructed tool, ready to be used; never {@code null}
     * @throws SynapseException if the class cannot be loaded, is not a {@link Tool}, has no
     *                              constructor taking a {@link ToolMethodSpec} and a {@link JsonCodec},
     *                              or could not be constructed
     */
    @Override
    public Tool create(ToolMethodSpec spec, JsonCodec codec) {
        if (spec.getType().isBlank()) {
            return defaultFactory.create(spec, codec);
        }
        String type = spec.getType();
        Class<?> toolClass;
        try {
            toolClass = Class.forName(type);
        } catch (ClassNotFoundException e) {
            throw new SynapseException("tool type '" + type + "' was not found", e);
        }
        if (!Tool.class.isAssignableFrom(toolClass)) {
            throw new SynapseException(
                    "tool type '" + type + "' is not a " + Tool.class.getName());
        }
        try {
            return (Tool) toolClass.getConstructor(ToolMethodSpec.class, JsonCodec.class)
                    .newInstance(spec, codec);
        } catch (NoSuchMethodException e) {
            throw new SynapseException("tool type '" + type + "' has no constructor taking a "
                    + ToolMethodSpec.class.getSimpleName() + " and a " + JsonCodec.class.getSimpleName(),
                    e);
        } catch (ReflectiveOperationException e) {
            throw new SynapseException("tool type '" + type + "' could not be constructed", e);
        }
    }

}