package io.github.synapse4j.data;

import org.jspecify.annotations.Nullable;

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
 * A part may carry a {@link ProviderExtras} bag, so a provider-specific field can be attached to
 * exactly the part it belongs to. No bag is created until one is set, so a part nobody configures
 * allocates nothing. Bags are never shared: each part holds its own, and reusing the entries of
 * another bag means copying them in with {@link ProviderExtras#putAll(ProviderExtras)}.
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
     * Provider-specific fields to merge into this part when the request is sent. Absent until one is
     * set: a part nobody configures carries no bag at all.
     */
    private @Nullable ProviderExtras extras;

    /**
     * The extras bag, created on first use — never {@code null}, unlike {@code getExtras()}.
     * Only a node about to record something allocates; a part nobody configures still carries
     * no bag until this is called.
     *
     * @return this part's extras, existing or fresh; never {@code null}
     */
    public ProviderExtras getOrCreateExtras() {
        if (extras == null) {
            extras = new ProviderExtras();
        }
        return extras;
    }

}
