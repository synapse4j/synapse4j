package io.github.synapse4j.jackson;

import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;

/**
 * A hook for adjusting the victools configuration before a schema generator is built from it.
 *
 * <p>
 * This is the extension point for schema generation: the defaults this module ships are one
 * customizer among others, so changing them is a call on the builder rather than a fork of the
 * module. A framework that manages beans can collect implementations of this interface and hand them
 * over in order; this module knows about no such framework.
 *
 * @see JacksonSchemaGenerators
 */
@FunctionalInterface
public interface SchemaGeneratorConfigBuilderCustomizer {

    /**
     * Adjusts the configuration being assembled.
     *
     * @param configBuilder the builder to adjust; never {@code null}
     */
    void customize(SchemaGeneratorConfigBuilder configBuilder);

}
