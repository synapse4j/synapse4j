package io.github.synapse4j.jackson;

import java.lang.reflect.Member;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.github.victools.jsonschema.generator.MemberScope;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;

import io.github.synapse4j.exception.SynapseException;
import org.jspecify.annotations.Nullable;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.introspect.BeanPropertyDefinition;
import tools.jackson.databind.json.JsonMapper;

/**
 * Replaces victools' idea of which properties a type has with Jackson's, in one direction.
 *
 * <p>
 * Victools discovers properties by walking fields and methods and deciding for itself what counts as
 * a getter (a public no-argument method with a matching field) and what a property is called (the
 * member's name, unless an annotation says otherwise). Jackson decides differently, and it is Jackson
 * that writes and reads the JSON — so a schema built on victools' answers describes a different set
 * of properties than the binder moves, silently and in both directions:
 *
 * <ul>
 * <li>a getter with no field of that name is written by Jackson but dropped by victools;</li>
 * <li>a public method that is not an accessor at all is no property for Jackson, yet appears in the
 * schema once method discovery is on;</li>
 * <li>a naming strategy set on the mapper renames the JSON but not victools' keywords;</li>
 * <li>discovery off means a property that only exists as a method never appears, while discovery on
 * means its name arrives as {@code getFoo()} until another option rewrites it — and that option
 * throws on a single-letter property name.</li>
 * </ul>
 *
 * <p>
 * So this module asks Jackson instead: {@link BeanDescription#findProperties()}, in the direction the
 * schema describes, answers which properties exist, what they are called, in what order, and whether
 * they can be written or read at all. That answer is then imposed on victools — members Jackson does
 * not move in this direction are ignored, and the rest take Jackson's name.
 *
 * <p>
 * One answer victools cannot be given: a property that exists only on a constructor parameter. Its
 * members are fields and methods, so there is nothing to carry the name, and a schema built without
 * it would disagree with the binder that reads it — silently, in the direction a generated document
 * travels. Such a type is refused at introspection rather than described wrongly: the binder can
 * still read it, but this codec will not vouch for a schema it cannot make true.
 *
 * <p>
 * What victools keeps is the part it is good at: what each property's value looks like. Its type
 * resolution is what turns a field's declared type into keywords, including through generics, which is
 * why only names are translated here and not types.
 *
 * <p>
 * The three options below are not a preference: they turn victools' member exclusions off, so that
 * every field and method reaches the checks above instead of being filtered out first.
 */
@RequiredArgsConstructor
final class JacksonPropertyDiscovery implements SchemaGeneratorConfigBuilderCustomizer {

    private final JsonMapper jsonMapper;

    /** Whether the schema describes JSON this codec writes, as opposed to JSON it reads. */
    private final boolean encoding;

    private final Map<Class<?>, Properties> cache = new ConcurrentHashMap<>();

    @Override
    public void customize(SchemaGeneratorConfigBuilder configBuilder) {
        configBuilder.with(Option.GETTER_METHODS, Option.NONSTATIC_NONVOID_NONGETTER_METHODS,
                Option.FIELDS_DERIVED_FROM_ARGUMENTFREE_METHODS);
        configBuilder.forFields().withIgnoreCheck(this::isNotJacksonProperty)
                .withPropertyNameOverrideResolver(this::nameOf);
        configBuilder.forMethods().withIgnoreCheck(this::isNotJacksonProperty)
                .withPropertyNameOverrideResolver(this::nameOf);
        // Jackson reports properties in the order it writes them, which is where @JsonPropertyOrder and
        // a mapper's ordering end up; victools' own sorter is not registered, so this one decides.
        configBuilder.forTypesInGeneral().withPropertySorter(Comparator.comparingInt(this::positionOf));
    }

    private @Nullable String nameOf(MemberScope<?, ?> member) {
        return propertiesOf(member.getDeclaringType().getErasedType()).names.get(rawMemberOf(member));
    }

    private boolean isNotJacksonProperty(MemberScope<?, ?> member) {
        return nameOf(member) == null;
    }

    private int positionOf(MemberScope<?, ?> member) {
        Integer position = propertiesOf(member.getDeclaringType().getErasedType()).positions.get(rawMemberOf(member));
        return position == null ? Integer.MAX_VALUE : position;
    }

    private static Member rawMemberOf(MemberScope<?, ?> member) {
        return member.getMember().getRawMember();
    }

    private Properties propertiesOf(Class<?> type) {
        return cache.computeIfAbsent(type, this::introspect);
    }

    private Properties introspect(Class<?> type) {
        SerializationConfig config = jsonMapper.serializationConfig();
        JavaType javaType = jsonMapper.getTypeFactory().constructType(type);
        BeanDescription description = encoding
                ? config.classIntrospectorInstance()
                        .introspectForSerialization(javaType,
                                config.classIntrospectorInstance().introspectClassAnnotations(javaType))
                : config.classIntrospectorInstance()
                        .introspectForDeserialization(javaType,
                                config.classIntrospectorInstance().introspectClassAnnotations(javaType));
        Properties properties = new Properties();
        int position = 0;
        for (BeanPropertyDefinition property : description.findProperties()) {
            if (encoding ? !property.couldSerialize() : !property.couldDeserialize()) {
                continue;
            }
            List<Member> accessors = accessorsOf(property);
            if (accessors.isEmpty()) {
                // Only a constructor parameter carries this property, and victools walks fields
                // and methods — no member could hold the name, so the schema would leave the
                // property out while the binder still reads it. Refuse instead: a schema that
                // quietly disagrees with the reader is worse than no schema at all.
                throw new SynapseException("cannot describe property \"" + property.getName() + "\" of "
                        + type.getName()
                        + ": it arrives only on a constructor parameter, which the schema generator cannot walk");
            }
            for (Member accessor : accessors) {
                properties.names.put(accessor, property.getName());
                properties.positions.put(accessor, position);
            }
            position++;
        }
        return properties;
    }

    /**
     * Every member Jackson considers part of a property, so that a field and the getter over it both
     * answer with the same name and victools' own de-duplication collapses them into one.
     *
     * <p>
     * Types that only declare properties elsewhere — a record's components — contribute no member
     * here, and are described by whatever Jackson does expose for them.
     */
    private static List<Member> accessorsOf(BeanPropertyDefinition property) {
        List<Member> accessors = new ArrayList<>();
        if (property.getField() != null) {
            accessors.add(property.getField().getMember());
        }
        if (property.getGetter() != null) {
            accessors.add(property.getGetter().getMember());
        }
        if (property.getSetter() != null) {
            accessors.add(property.getSetter().getMember());
        }
        return accessors;
    }

    /** What Jackson says about one type: the name and the position of every member it moves. */
    private static class Properties {

        private final Map<Member, String> names = new LinkedHashMap<>();

        private final Map<Member, Integer> positions = new HashMap<>();

    }

}
