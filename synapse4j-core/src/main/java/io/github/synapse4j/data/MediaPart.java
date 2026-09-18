package io.github.synapse4j.data;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * A media payload: an image, an audio clip, a video or a document.
 *
 * <p>
 * One type carrying the concrete kind in {@link #mediaType} rather than a type per kind: the kinds
 * differ only in how they are rendered, they keep appearing, and every protocol already tells them
 * apart by MIME type. A provider-hosted file — one the provider stores and refers to by id — is a
 * different concept, and belongs in its own part rather than in a field here.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class MediaPart extends ContentPart {

    /** MIME type of the payload, for example {@code image/png}. */
    private String mediaType;

    /** Where the payload can be fetched from; in practice set instead of {@link #data}. */
    private String uri;

    /** The payload itself; in practice set instead of {@link #uri}. */
    private byte[] data;

    /** File name, when the payload has one. */
    private String name;

}
