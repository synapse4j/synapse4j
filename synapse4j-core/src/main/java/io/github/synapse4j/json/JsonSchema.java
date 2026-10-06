package io.github.synapse4j.json;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import org.jspecify.annotations.Nullable;

import lombok.NonNull;

/**
 * A JSON Schema: what shape a JSON document has to have.
 *
 * <p>
 * A schema is either a boolean — {@code true} accepts everything, {@code false} accepts nothing — or
 * an object carrying keywords. {@link #asBoolean()} answers which: a non-null answer is the boolean
 * form, {@code null} the object form.
 *
 * <p>
 * This is the read side. The keywords a caller reaches for constantly have a named getter; every other
 * keyword is read through {@link #keys()} and {@link #get(String, Class)}, which together see everything
 * the node carries, modelled or not. The keyword names the getters below do not cover are still walked:
 * a keyword whose value is a sub-schema is visited and mapped like any other.
 *
 * <p>
 * Nothing a schema carries is lost: a keyword this interface does not name is kept as it is and read
 * through {@link #keys()} and {@link #get(String, Class)}, so a schema of any draft survives a round
 * trip. The named getters, though, are those of JSON Schema 2019-09 and later and read only that
 * spelling — where a keyword was renamed, {@link #getDefs()} answers {@code $defs} and not the older
 * {@code definitions} of draft-07 and earlier. Reading a schema of another dialect through the named
 * getters is not what this interface does; {@link #keys()} and {@link #get(String, Class)} see it all.
 *
 * <p>
 * {@link #visit(Consumer)} walks the tree and lets each node be read. {@link #map(UnaryOperator)} walks
 * it and produces a new tree: the function answers which node to use, and a node it leaves unchanged is
 * shared rather than copied, so only the path to a change is rebuilt.
 *
 * <p>
 * Read-only: a schema is assembled through {@link JsonSchemaBuilder} and read from then on. The built-in
 * implementations freeze what they carry, so nothing a getter hands back can change the schema. A change
 * is a new schema rather than a change to this one — {@link #map(UnaryOperator)} produces one, sharing
 * the nodes it leaves alone.
 *
 * <p>
 * A schema built through {@link JsonSchemaBuilder} cannot contain itself: a node is built from nodes
 * that already exist, so a cycle is not constructible, and a recursive schema is spelled with
 * {@code $ref} instead. The interface is open, though, so an implementation that hands out itself as
 * its own sub-schema is possible; the walks of {@link #visit(Consumer)} and {@link #map(UnaryOperator)}
 * do not guard against one.
 *
 * <p>
 * A schema is a value: two schemas are equal when they carry the same content.
 */
public interface JsonSchema {

    /**
     * Whether this schema is the boolean form, and which.
     *
     * @return {@code true} or {@code false} for a boolean schema, {@code null} for the object form
     */
    @Nullable
    Boolean asBoolean();

    /**
     * The JSON types the value may have.
     *
     * @return the types, or {@code null} if this node does not say — which allows any type
     */
    @Nullable
    List<String> getType();

    /**
     * A short name for this schema, for readers of the document.
     *
     * @return the title, or {@code null} if this node has none
     */
    @Nullable
    String getTitle();

    /**
     * What this schema means, for a reader — or a model — to read.
     *
     * @return the description, or {@code null} if this node has none
     */
    @Nullable
    String getDescription();

    /**
     * The properties of an object, each with its own schema.
     *
     * @return the properties by name, or {@code null} if this node declares none
     */
    @Nullable
    Map<String, JsonSchema> getProperties();

    /**
     * Names of the properties that must be present.
     *
     * @return the names, or {@code null} if this node requires none
     */
    @Nullable
    List<String> getRequired();

    /**
     * What the object does about properties beyond {@link #getProperties()}.
     *
     * <p>
     * This is a schema: {@code false} is the boolean form that forbids them, {@code true} the one that
     * allows anything, and an object schema constrains what they may be.
     *
     * @return the schema, or {@code null} if this node is silent
     */
    @Nullable
    JsonSchema getAdditionalProperties();

    /**
     * A reference to a sub-schema, such as {@code #/$defs/Location}.
     *
     * @return the reference, or {@code null} if this node is not one
     */
    @Nullable
    String getRef();

    /**
     * Reusable sub-schemas, addressed by {@link #getRef()}.
     *
     * <p>
     * This answers the {@code $defs} keyword of JSON Schema 2019-09 and later. The older spelling,
     * {@code definitions} (draft-07 and earlier), is a different keyword and is not answered here; it is
     * still carried, and read through {@link #keys()} and {@link #get(String, Class)} like any keyword
     * this interface does not name. The named getters read the modern spelling only — a schema of
     * another dialect is read through {@link #keys()} and {@link #get(String, Class)}, not through them.
     *
     * @return the definitions by name, or {@code null} if this node has none
     */
    @Nullable
    Map<String, JsonSchema> getDefs();

    /**
     * Every keyword this node carries, the ones above included.
     *
     * @return the keyword names; never {@code null}
     */
    Set<String> keys();

    /**
     * The value of any keyword this node carries, as the JSON data it is.
     *
     * <p>
     * The keywords with a getter above say their type there, and {@link #get(String, Class)} reads one
     * as an expected type. This method asks for no type and answers with whatever the node holds: a
     * sub-schema for a keyword whose value is one, the JSON data otherwise, {@code null} when the node
     * does not carry the keyword.
     *
     * @param keyword the keyword to read
     * @return its value as it is held, or {@code null} if this node does not carry it
     */
    @Nullable
    Object get(String keyword);

    /**
     * The value of any keyword this node carries, read as the given type.
     *
     * <p>
     * The keywords with a getter above say their type there; this method is for the rest. The answer is
     * the value when it is an instance of the given type, and {@code null} when this node does not carry
     * the keyword or carries something else — a value of another type is not forced into the asked one.
     *
     * @param <T>     the type to read the value as
     * @param keyword the keyword to read
     * @param type    the type to read it as; must not be {@code null}
     * @return its value, or {@code null} if this node does not carry it as that type
     */
    <T> @Nullable T get(String keyword, @NonNull Class<T> type);

    /**
     * The value of any keyword this node carries, read as a list of the given element type.
     *
     * <p>
     * The answer is the value when it is a list whose every element is an instance of the given type,
     * and {@code null} when this node does not carry the keyword, carries something else, or carries a
     * list an element of which is of another type.
     *
     * @param <T>     the element type to read the list as
     * @param keyword the keyword to read
     * @param type    the element type to read it as; must not be {@code null}
     * @return the list, or {@code null} if this node does not carry one of that element type
     */
    <T> @Nullable List<T> getList(String keyword, @NonNull Class<T> type);

    /**
     * The value of any keyword this node carries, read as a map of the given value type by string key.
     *
     * <p>
     * The answer is the value when it is a map whose keys are strings and whose every value is an
     * instance of the given type, and {@code null} when this node does not carry the keyword, carries
     * something else, or carries a map holding something else.
     *
     * @param <T>     the value type to read the map as
     * @param keyword the keyword to read
     * @param type    the value type to read it as; must not be {@code null}
     * @return the map, or {@code null} if this node does not carry one of that value type
     */
    <T> @Nullable Map<String, T> getMap(String keyword, @NonNull Class<T> type);

    /**
     * Walks this schema and every sub-schema below it, this one first.
     *
     * <p>
     * The visitor receives each schema and may read it. The sub-schemas of that schema are visited
     * afterwards.
     *
     * @param visitor what to do with each schema; must not be {@code null}
     */
    void visit(@NonNull Consumer<JsonSchema> visitor);

    /**
     * Walks this schema and every sub-schema below it and produces a new schema.
     *
     * <p>
     * The walk is bottom-up: a node's sub-schemas are mapped first, then the function is handed that
     * node with them already replaced. The node it answers with is the one used; answering with the
     * same instance leaves that node shared, so only the nodes whose answer changed — and the path
     * down to them — are rebuilt.
     *
     * @param fn which node to use in place of each; must not be {@code null}
     * @return the new schema; never {@code null}
     */
    JsonSchema map(@NonNull UnaryOperator<JsonSchema> fn);

}
