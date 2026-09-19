package io.github.synapse4j.openai;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * The single home of the Jackson configuration the OpenAI wire model is bound with.
 *
 * <p>
 * Protocol bytes never go through the application's {@code JsonCodec} — the wire shape has to be
 * identical no matter which JSON library the application chose — so this module owns a private
 * mapper instead. Its settings are fixed and live in exactly one place: {@code null}-valued fields
 * are omitted (an opinionated {@code null} would override the provider's own default), unknown
 * properties are ignored (the provider adds fields without notice, and responses must keep
 * parsing), and the SNAKE_CASE naming strategy translates camelCase fields to the wire's spelling
 * — so wire classes carry no {@code @JsonProperty} annotations except where a name is not the
 * standard conversion. Extras keys are literal by design ({@code @JsonAnyGetter}) and are not
 * touched by the naming strategy.
 */
final class OpenAiJson {

    private OpenAiJson() {
    }

    static JsonMapper newMapper() {
        return JsonMapper.builder()
                .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
    }

}
