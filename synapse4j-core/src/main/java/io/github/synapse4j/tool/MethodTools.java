package io.github.synapse4j.tool;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.json.JsonCodec;
import lombok.NonNull;

/**
 * How a Java method becomes a tool: the annotated ones a class declares through {@link #from(Object)}
 * and {@link #from(Class)}, and one named by hand through {@link #of}. Each is read into a
 * {@link ToolMethodSpec}, customized, and built into a tool.
 *
 * <p>
 * One method becomes one tool. The signature gives the structure; the annotations write the strings
 * they carry; the customizers this reader holds rewrite what they like afterwards, which is where a
 * name or a description from configuration enters. Nothing is filled in before them — a blank stays
 * blank, so a customizer can tell a value somebody wrote from one nobody did — and what is still
 * blank when they are done is filled afterwards: a method that names no tool is known by its own
 * name, a parameter by the Java parameter it was declared as.
 *
 * <p>
 * {@link #from(Object)} reads every annotated method the object has — the instance ones it runs on
 * itself, and the static ones, which need no instance — while {@link #from(Class)} reads the annotated
 * static ones, the only ones a class can supply. Visibility does not matter: a protected or private
 * declaration is read like a public one. Neither does where it sits — what the object only inherits,
 * from a superclass or an interface's default method, is read too, with an override standing in for
 * what it overrides. Abstract methods, and the synthetic and bridge methods the compiler makes, are
 * left alone. Both answers come in no particular order: the order reflection hands methods over is
 * unspecified.
 *
 * <p>
 * The reader holds configuration, not conversations. It is not safe to share while that configuration
 * is still changing: {@link #specToolFactory(SpecToolFactory)} and {@link #addCustomizer} write its
 * fields, so calling either while another thread asks for tools is not safe. Once configuration has
 * stopped, an instance is safe to share — its fields are only read from then on, and every method
 * here may be called from any thread.
 */

public class MethodTools {

    /** The codec every tool is completed with; never {@code null}. */
    @NonNull
    private final JsonCodec codec;

    /**
     * The step every resolution ends with, which settles whatever the annotations and the customizers
     * left blank. It runs after {@link #customizers} rather than among them, so an application cannot
     * add a step behind it: what is blank when it runs is what nobody wrote.
     */
    private final FinalToolMethodSpecCustomizer finalCustomizer;

    /** The steps every resolution goes through, in the order they were added. */
    private final List<ToolMethodSpecCustomizer> customizers = new ArrayList<>();

    /**
     * Builds a reader over the given codec, with the settling step every resolution ends in.
     *
     * @param codec the codec every tool is completed with; never {@code null}
     */
    public MethodTools(@NonNull JsonCodec codec) {
        this.codec = codec;
        this.finalCustomizer = new FinalToolMethodSpecCustomizer(codec);
    }

    /**
     * Where a tool method's {@code type} is resolved: by default the name is a class, and a blank one
     * is a {@link MethodTool}.
     */
    private SpecToolFactory specToolFactory = new ReflectiveSpecToolFactory(MethodTool::new);

    /**
     * Sets where a tool method's {@code type} is resolved.
     *
     * @param specToolFactory the factory to use; never {@code null}
     * @return this reader, for chaining
     */
    public MethodTools specToolFactory(@NonNull SpecToolFactory specToolFactory) {
        this.specToolFactory = specToolFactory;
        return this;
    }

    /**
     * Adds a step to every resolution this reader produces, in the order given.
     *
     * @param customizer the step to add; never {@code null}
     * @return this reader, for chaining
     */
    public MethodTools addCustomizer(@NonNull ToolMethodSpecCustomizer customizer) {
        customizers.add(customizer);
        return this;
    }

    /**
     * The tool {@code method} becomes under the given name and description: the same reading, the
     * same customizers and the same defaults as an annotated method, with the two strings supplied
     * here instead of read off an annotation.
     *
     * @param name        the name the model calls the tool by; never {@code null}
     * @param description what the tool does; never {@code null}
     * @param method      the method to run; never {@code null}
     * @param target      the instance an instance method runs on, {@code null} for a static one
     * @return the assembled tool, initialized and ready; never {@code null}
     * @throws SynapseException if the method is an instance method and no target is given
     */
    public MethodTool of(@NonNull String name, @NonNull String description, @NonNull Method method,
            @Nullable Object target) {
        ToolMethodSpec spec = specOf(method, target);
        spec.setName(name);
        spec.setDescription(description);
        customize(spec);
        finalCustomizer.customize(spec);
        return new MethodTool(spec, codec);
    }

    /**
     * The tools {@code target} declares: the methods it carries {@link ToolMethod} on, instance and
     * static alike.
     *
     * @param target the object whose methods are read; never {@code null}
     * @return one tool per annotated method; never {@code null}, and empty when there are none
     * @throws SynapseException if two methods resolve to one name, or a resolution cannot be built
     *                              into a tool — see {@link #from(Class)}
     */
    public List<Tool> from(@NonNull Object target) {
        return read(target.getClass(), target);
    }

    /**
     * The tools {@code type} declares, run on {@code target}: instance and static methods alike are
     * read off the given class rather than off the instance's own, so a bean the container has wrapped
     * in a proxy still yields its tools while the proxy stays what a call runs on.
     *
     * @param type   the class whose annotated methods are read; never {@code null}
     * @param target the instance instance methods run on; never {@code null}
     * @return one tool per annotated method; never {@code null}, and empty when there are none
     * @throws SynapseException if two methods resolve to one name, or a resolution cannot be built
     *                              into a tool — see {@link #from(Class)}
     */
    public List<Tool> from(@NonNull Class<?> type, @NonNull Object target) {
        return read(type, target);
    }

    /**
     * The tools {@code type} declares: its annotated static methods. An instance method is not read
     * here — there is no instance to run it on; hand {@link #from(Object)} the instance instead.
     *
     * @param type the class whose methods are read; never {@code null}
     * @return one tool per annotated static method; never {@code null}, and empty when there are none
     * @throws SynapseException if two methods resolve to one name, or a resolution cannot be built
     *                              into a tool: a {@code type} naming an unknown class or one without
     *                              a no-argument constructor, or a tool refusing the resolution it is
     *                              completed from
     */
    public List<Tool> from(@NonNull Class<?> type) {
        return read(type, null);
    }

    private List<Tool> read(Class<?> type, @Nullable Object target) {
        List<Tool> tools = new ArrayList<>();
        Map<String, Method> named = new LinkedHashMap<>();
        for (Method method : runnableMethods(type)) {
            ToolMethod annotation = method.getAnnotation(ToolMethod.class);
            if (annotation == null) {
                continue;
            }
            boolean statik = Modifier.isStatic(method.getModifiers());
            if (!statik && target == null) {
                continue;
            }
            ToolMethodSpec spec = specOf(method, statik ? null : target);
            if (!annotation.name().isBlank()) {
                spec.setName(annotation.name());
            }
            if (!annotation.description().isBlank()) {
                spec.setDescription(annotation.description());
            }
            if (!annotation.type().isBlank()) {
                spec.setType(annotation.type());
            }
            if (!annotation.strict().isBlank()) {
                spec.setStrict(annotation.strict());
            }
            readParameters(method, spec);
            customize(spec);
            finalCustomizer.customize(spec);
            Method earlier = named.putIfAbsent(spec.getName(), method);
            if (earlier != null) {
                throw new SynapseException("two tool methods of " + type.getName() + " declare the name '"
                        + spec.getName() + "': " + earlier + " and " + method);
            }
            tools.add(specToolFactory.create(spec, codec));
        }
        return tools;
    }

    /**
     * The resolution of one method before anything is written into it: its signature, the instance it
     * runs on, and nothing else. Which parameters exist and which Java parameter each one stands for
     * is settled here and cannot drift afterwards; an instance method needs the instance it runs on,
     * and that pairing is this reader's business — the spec is handed two unrelated objects.
     *
     * @param method the method to read; never {@code null}
     * @param target the instance an instance method runs on, {@code null} for a static one
     * @return the empty resolution; never {@code null}
     * @throws SynapseException if the method is an instance method and no target is given
     */
    private ToolMethodSpec specOf(Method method, @Nullable Object target) {
        if (!Modifier.isStatic(method.getModifiers()) && target == null) {
            throw new SynapseException("no target for " + method + ", which is an instance method");
        }
        List<ToolParameterSpec> parameters = Arrays.stream(method.getParameters())
                .map(ToolParameterSpec::new).toList();
        return new ToolMethodSpec(method, target, parameters);
    }

    /** Writes onto the spec whatever each parameter's {@link ToolParam} actually said. */
    private void readParameters(Method method, ToolMethodSpec spec) {
        Parameter[] declared = method.getParameters();
        for (int i = 0; i < declared.length; i++) {
            ToolParam annotation = declared[i].getAnnotation(ToolParam.class);
            if (annotation == null) {
                continue;
            }
            ToolParameterSpec entry = spec.getParameters().get(i);
            if (!annotation.name().isBlank()) {
                entry.setName(annotation.name());
            }
            if (!annotation.description().isBlank()) {
                entry.setDescription(annotation.description());
            }
            if (!annotation.required().isBlank()) {
                entry.setRequired(annotation.required());
            }
            if (!annotation.fromModel().isBlank()) {
                entry.setFromModel(annotation.fromModel());
            }
            if (!annotation.schema().isBlank()) {
                entry.setSchema(annotation.schema());
            }
        }
    }

    /** Runs every customizer this reader holds, in the order they were added. */
    private void customize(ToolMethodSpec spec) {
        for (ToolMethodSpecCustomizer customizer : customizers) {
            customizer.customize(spec);
        }
    }

    /**
     * Every method {@code type} can run, whatever its visibility, one entry per signature with the
     * most derived declaration first — so an override takes the place of what it overrides, as
     * Java's own method lookup has it — and nothing abstract, synthetic or bridge.
     */
    private static List<Method> runnableMethods(Class<?> type) {
        Map<String, Method> bySignature = new LinkedHashMap<>();
        for (Class<?> level = type; level != null && level != Object.class; level = level.getSuperclass()) {
            for (Method method : level.getDeclaredMethods()) {
                if (method.isSynthetic() || method.isBridge() || Modifier.isAbstract(method.getModifiers())) {
                    continue;
                }
                bySignature.putIfAbsent(signature(method), method);
            }
            for (Class<?> face : level.getInterfaces()) {
                readDefaultMethods(face, bySignature);
            }
        }
        return List.copyOf(bySignature.values());
    }

    /**
     * Reads an interface's default methods into the given map, then those its extended interfaces
     * declare. A signature already present is left as it stands, so the most derived declaration
     * wins.
     */
    private static void readDefaultMethods(Class<?> face, Map<String, Method> bySignature) {
        for (Method method : face.getDeclaredMethods()) {
            if (!method.isDefault() || method.isSynthetic() || method.isBridge()) {
                continue;
            }
            bySignature.putIfAbsent(signature(method), method);
        }
        for (Class<?> parent : face.getInterfaces()) {
            readDefaultMethods(parent, bySignature);
        }
    }

    /** What makes two declarations one method: the name and the parameter types, as an override reads them. */
    private static String signature(Method method) {
        StringBuilder signature = new StringBuilder(method.getName());
        for (Class<?> parameter : method.getParameterTypes()) {
            signature.append(' ').append(parameter.getName());
        }
        return signature.toString();
    }

}