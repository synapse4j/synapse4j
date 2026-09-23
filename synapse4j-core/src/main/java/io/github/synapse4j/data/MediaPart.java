package io.github.synapse4j.data;

import io.github.synapse4j.util.InputStreamSupplier;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * A media payload: an image, an audio clip, a video or a document.
 *
 * <p>
 * One type carrying the concrete kind in {@link #mediaType} rather than a type per kind: the kinds
 * differ only in how they are rendered, they keep appearing, and every protocol already tells them
 * apart by MIME type. A provider-hosted file — one the provider stores and refers to by id — is a
 * different concept, and belongs in its own part rather than in a field here.
 *
 * <p>
 * The payload is where its bytes come from rather than the bytes themselves, so that a clip larger than
 * memory can still be sent. {@link #source} is opened when the payload is written and opened again for
 * every attempt; how the bytes reach the wire — inlined, encoded, uploaded — is the protocol module's
 * decision, and this model does not make it.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(callSuper = true)
public class MediaPart extends ContentPart {

    /** MIME type of the payload, for example {@code image/png}. */
    private String mediaType;

    /** Where the payload can be fetched from; in practice set instead of {@link #source}. */
    private String uri;

    /** Where the payload's bytes come from; in practice set instead of {@link #uri}. */
    private InputStreamSupplier source;

    /** File name, when the payload has one. */
    private String name;

}
