package io.github.synapse4j.data;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * One element of a message's content: a piece of text, the model's reasoning, a tool call, a tool
 * result or a media payload.
 *
 * <p>
 * This class is abstract, and deliberately neither {@code final} nor {@code sealed}: adding a part
 * type for content this library does not model is how a provider module — or an application —
 * extends the model without touching it.
 *
 * <p>
 * Every part owns its {@link ProviderExtras} bag, so a provider-specific field can be attached to
 * exactly the part it belongs to. The bag is created with the part and never shared: it is mutated
 * in place through {@link #getExtras()}, and reusing the entries of another bag means copying them
 * in with {@link ProviderExtras#putAll(ProviderExtras)}.
 *
 * <p>
 * Subclasses must pass {@code callSuper = true} to {@code @ToString}, so a part's printout carries
 * the inherited {@code extras} along with its own fields.
 */
@Getter
@Setter
@ToString
public abstract class ContentPart {

    /**
     * Provider-specific fields to merge into this part when the request is sent. Created with the
     * part, and never {@code null}.
     */
    private final ProviderExtras extras = new ProviderExtras();

}
