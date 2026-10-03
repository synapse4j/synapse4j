package io.github.synapse4j.jackson;

import java.util.LinkedHashSet;
import java.util.Set;

import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.module.jackson.JacksonOption;

import lombok.Data;

/**
 * Which of this module's choices to apply when a generator is built.
 *
 * <p>
 * Every value here is one this module would otherwise decide for you, and each defaults to the choice
 * it recommends. Changing one is how a caller replaces it: the choice is simply not applied, so a
 * module of the caller's own takes its place instead of competing with one of ours. Which is why the
 * victools options are two sets rather than one — {@link #options} are turned on and
 * {@link #suppressedOptions} are turned off, and the preset enables some of both.
 */
@Data
public class JacksonSchemaSettings {

    /**
     * The options handed to victools' Jackson module, which is what makes it read Jackson's
     * annotations. Defaults to {@link JacksonOption#RESPECT_JSONPROPERTY_REQUIRED}, so that
     * {@code @JsonProperty(required = true)} is honoured. Empty means the module is not applied at all.
     */
    private final Set<JacksonOption> jacksonOptions = new LinkedHashSet<>(
            Set.of(JacksonOption.RESPECT_JSONPROPERTY_REQUIRED));

    /**
     * The victools options to turn on beyond the {@code PLAIN_JSON} preset. Defaults to
     * {@link Option#FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT}, so an object that declares properties
     * says so explicitly, and {@link Option#MAP_VALUES_AS_ADDITIONAL_PROPERTIES}, so a map describes
     * its value type rather than degrading to a bare object.
     */
    private final Set<Option> options = new LinkedHashSet<>(Set.of(
            Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT,
            Option.MAP_VALUES_AS_ADDITIONAL_PROPERTIES));

    /**
     * The victools options to turn off even though the {@code PLAIN_JSON} preset enables them. Defaults
     * to {@link Option#SCHEMA_VERSION_INDICATOR}: the draft is already implied by the keywords that are
     * written, and the marker only tells a validator which dialect it is reading.
     */
    private final Set<Option> suppressedOptions = new LinkedHashSet<>(Set.of(Option.SCHEMA_VERSION_INDICATOR));

    /**
     * Whether which properties exist, what they are called and in what order comes from the mapper.
     * Turning it off leaves victools' own discovery in place — and with it a schema that may describe a
     * different set of properties than the binder moves.
     */
    private boolean discoverProperties = true;

    /**
     * Whether an {@code Optional} is described as the schema of its value made nullable, in every
     * position. Turning it off leaves the preset's {@link Option#FLATTENED_OPTIONALS} in place, which
     * answers for a member only.
     */
    private boolean flattenOptionals = true;

    /**
     * Whether a property is listed as required: what the type asks for when the schema describes JSON
     * being read, what writing produces when it describes JSON being written.
     */
    private boolean requiredProperties = true;

}
