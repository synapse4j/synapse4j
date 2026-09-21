package io.github.synapse4j.http;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.Data;
import lombok.NonNull;

/**
 * One HTTP request: method, URL, headers, body bytes, and an optional per-request timeout.
 *
 * <p>
 * Nothing here is nullable except {@link #body} and {@link #options}, whose {@code null} means "no body"
 * and "no opinions of its own" — the two conditions a caller cannot express with a value. Headers are
 * multi-valued because the wire is; repeated header lines are the norm, not the exception.
 *
 * <p>
 * The method is a plain string with common constants, not an enum: HTTP methods are a registry that keeps
 * growing (RFC 9110 §9.1 plus extensions like QUERY) and a closed type would block a caller from using
 * one this library has not heard of.
 */
@Data
public class HttpRequest {

    public static final String GET = "GET";
    public static final String POST = "POST";
    public static final String PUT = "PUT";
    public static final String DELETE = "DELETE";
    public static final String HEAD = "HEAD";
    public static final String PATCH = "PATCH";

    /** The HTTP method. Never {@code null}; defaults to {@link #GET}. */
    private String method = GET;

    /** The request target, e.g. {@code "https://api.example.com/v1/chat"}. */
    @NonNull
    private String url;

    /**
     * The header lines, multiple values per name. Never {@code null}; empty means no headers.
     * Names are case-insensitive on the wire; implementations decide how faithfully they preserve
     * the caller's casing.
     */
    @NonNull
    private Map<String, List<String>> headers = new LinkedHashMap<>();

    /**
     * The body, or {@code null} when the request has no body (typical for {@link #GET}). An empty body
     * is not the same as no body: one goes out as a request with an empty body, while {@code null} sends
     * none at all.
     */
    private HttpBody body;

    /**
     * What this request wants from the HTTP layer, or {@code null} when it has no opinion and the
     * implementation's own {@link HttpOptions} are to be used as they are. {@link HttpOptions#effective}
     * is how the two come together.
     */
    private HttpOptions options;

}
