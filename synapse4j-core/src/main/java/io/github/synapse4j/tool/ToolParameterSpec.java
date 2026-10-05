package io.github.synapse4j.tool;

import java.lang.reflect.Parameter;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * One declared parameter of a tool method, read: the Java side it was read from, and the values its
 * annotations wrote.
 *
 * <p>
 * A customizer sees and changes this the way it does {@link ToolMethodSpec}; see there for what that
 * means.
 */
@Getter
@Setter
@ToString
@RequiredArgsConstructor
public class ToolParameterSpec {

    /** The declared parameter the values were read from. */
    @NonNull
    private final Parameter parameter;

    /** The property name this argument goes by; blank when nothing supplied one. */
    @NonNull
    private String name = "";

    /** What this argument means; blank when nothing supplied it. */
    @NonNull
    private String description = "";

    /** Whether the model has to produce it; blank when nothing supplied it. */
    @NonNull
    private String required = "";

    /** Whether the model produces it, as against its value coming from somewhere else. */
    @NonNull
    private String fromModel = "";

    /** The schema of this one property; blank when the codec derives it from the declared type. */
    @NonNull
    private String schema = "";
}
