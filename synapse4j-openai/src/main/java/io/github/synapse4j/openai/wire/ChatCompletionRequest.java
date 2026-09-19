package io.github.synapse4j.openai.wire;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;

/**
 * The request body of {@code POST /chat/completions}, field for field.
 *
 * <p>
 * Field names follow Java naming; the mapper's SNAKE_CASE strategy spells them for the wire, so no
 * per-field annotations are needed. Jackson annotations appear only where a plain field cannot
 * express the shape: {@link #getExtras()} spills its entries as top-level fields via the any-getter,
 * because that is how {@code ChatOptions}' provider extras flatten into the request.
 */
@Data
public class ChatCompletionRequest {

    private String model;
    private List<Message> messages;
    private List<Tool> tools;
    private Double temperature;
    private Integer maxTokens;
    private Double topP;
    private ResponseFormat responseFormat;

    private final Map<String, Object> extras = new LinkedHashMap<>();

    // Written by hand, not generated: the two annotations split one property into "not a regular
    // property" (@JsonIgnore on the bean getter) and "spill my entries as top-level fields"
    // (@JsonAnyGetter), which Lombok cannot express on a single accessor.
    @JsonIgnore
    public Map<String, Object> getExtras() {
        return extras;
    }

    @JsonAnyGetter
    public Map<String, Object> getExtrasForWire() {
        return extras;
    }

    @JsonAnySetter
    public void putExtra(String name, Object value) {
        extras.put(name, value);
    }

}
