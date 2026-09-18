package io.github.synapse4j.data;

import lombok.Data;

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
 * Subclasses must pass {@code callSuper = true} to both {@code @EqualsAndHashCode} and
 * {@code @ToString}. Without it the inherited {@code extras} silently drops out of equality, and
 * two parts that differ only in their provider-specific fields compare equal.
 */
@Data
public abstract class ContentPart {

    /**
     * Provider-specific fields to merge into this part when the request is sent. Created with the
     * part, and never {@code null}.
     */
    private final ProviderExtras extras = new ProviderExtras();

}
