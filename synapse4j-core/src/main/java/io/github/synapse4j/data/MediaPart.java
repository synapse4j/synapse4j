package io.github.synapse4j.data;

import org.jspecify.annotations.Nullable;

import io.github.synapse4j.util.InputStreamSupplier;
import lombok.Getter;
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
 * The payload is where its bytes come from rather than the bytes themselves, so that a clip larger
 * than memory can still be sent: from a {@link #uri} the provider fetches, or from a {@link #source}
 * whose bytes this library supplies. {@link #source} is opened when the payload is written and opened
 * again for every attempt; how the bytes reach the wire — inlined, encoded, uploaded — is the protocol
 * module's decision, and this model does not make it.
 *
 * <p>
 * What a part carries is what the protocol that wrote it put there, not what this model requires: a
 * type, an origin and a name are all optional here. A part naming neither a {@link #uri} nor a
 * {@link #source}, or naming both, is left as it stands — which of the two a protocol wants, and
 * whether it wants a {@link #mediaType} spelled out, are the protocol's to decide.
 */
@Getter
@ToString(callSuper = true)
public class MediaPart extends ContentPart {

    /** MIME type of the payload, for example {@code image/png}. */
    private final @Nullable String mediaType;

    /** Where the payload can be fetched from, when it is fetched. */
    private final @Nullable String uri;

    /** Where the payload's bytes come from, when they are supplied. */
    private final @Nullable InputStreamSupplier source;

    /** File name, when the payload has one. */
    private final @Nullable String name;

    /**
     * A payload the provider fetches from the URL, discovering its type itself.
     *
     * @param mediaType MIME type of the payload, {@code null} to leave it to the protocol
     * @param uri       where the payload can be fetched from
     */
    public MediaPart(@Nullable String mediaType, @Nullable String uri) {
        this(mediaType, uri, null, null, null);
    }

    /**
     * A payload whose bytes this library supplies.
     *
     * @param mediaType MIME type of the payload, {@code null} to leave it to the protocol
     * @param source    where the payload's bytes come from
     */
    public MediaPart(@Nullable String mediaType, @Nullable InputStreamSupplier source) {
        this(mediaType, null, source, null, null);
    }

    /**
     * Everything a media part can carry. Nothing is required: a type, an origin, a file name and the
     * provider fields are all for the protocol that writes the part to make sense of.
     *
     * @param mediaType MIME type of the payload, {@code null} to leave it to the protocol
     * @param uri       where the payload can be fetched from, {@code null} when it is not fetched
     * @param source    where the payload's bytes come from, {@code null} when they are not supplied
     * @param name      file name, {@code null} when the payload has none
     * @param extras    provider-specific fields, {@code null} for none; frozen on the way in
     */
    public MediaPart(@Nullable String mediaType, @Nullable String uri, @Nullable InputStreamSupplier source,
            @Nullable String name, @Nullable ProviderExtras extras) {
        super(extras);
        this.mediaType = mediaType;
        this.uri = uri;
        this.source = source;
        this.name = name;
    }

}
