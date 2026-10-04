package io.github.synapse4j.tool;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;

import org.jspecify.annotations.Nullable;

import io.github.synapse4j.json.JsonSchema;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

/**
 * A stand-in whose single property carries a value of the given type, so the codec can answer whether
 * a value of that type is required as a property.
 *
 * <p>
 * JSON Schema declares {@code required} on the object that holds a property, never on the property's
 * own type, so a value that stands alone — a method parameter, say — cannot be asked about directly.
 * Placing it in a property's position makes the question answerable: {@link MethodTool} generates the
 * schema of {@code RequiredProbe<parameterType>} and reads whether {@code value} is listed as
 * required. Only the type is ever used; no instance is created.
 */
@Getter
@Setter
class RequiredProbe<T> {

    /**
     * The value the codec is asked about; only its type reaches the schema, never its value. No
     * instance is ever created, so the field is never assigned, and it stays free of any annotation
     * — a nullness marker of its own would be read as a fact about the value being asked about.
     */
    @SuppressWarnings("NullAway.Init")
    private T value;

    /**
     * The type that places a value of the given type in a property's position, so a codec can be asked
     * whether such a value is required as a property.
     *
     * @param valueType the type being asked about; must not be {@code null}
     * @return the probe type for it; never {@code null}
     */
    static Type wrapping(@NonNull Type valueType) {
        return new ParameterizedType() {

            @Override
            public Type[] getActualTypeArguments() {
                return new Type[] { valueType };
            }

            @Override
            public Type getRawType() {
                return RequiredProbe.class;
            }

            @Override
            public @Nullable Type getOwnerType() {
                return null;
            }

        };
    }

    /**
     * Whether the codec requires a value of the probed type, read off the schema it answered for a
     * probe: the value is required when the codec listed the {@code value} property among the
     * object's {@code required} ones.
     *
     * @param probeSchema the schema the codec generated for a probe type; must not be {@code null}
     * @return {@code true} when a value of the probed type is required as a property
     */
    static boolean isRequired(@NonNull JsonSchema probeSchema) {
        List<String> required = probeSchema.getRequired();
        return required != null && required.contains("value");
    }

}
