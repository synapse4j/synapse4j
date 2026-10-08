package io.github.synapse4j.spring.boot.tool;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.core.annotation.AliasFor;
import org.springframework.stereotype.Component;

/**
 * Marks a class whose {@link io.github.synapse4j.tool.ToolMethod} methods are the application's tools
 * — and, through the {@link Component} it is meta-annotated with, makes the class a bean that component
 * scanning finds, so the tools are discovered without a scan of their own. The annotation belongs to
 * the class the container registers, not to a method: a bean whose class inherits one from a superclass
 * is marked too — the nearest one up the hierarchy is the one that counts — and a class nothing marks
 * is left out, however many {@code @ToolMethod} methods it carries.
 *
 * <p>
 * {@link #prefix()} is written in front of the name of every tool read from that class, the ones it
 * declares and the ones it inherits alike, which is how two classes may offer tools of one name without
 * colliding. It is literal text: a separator, if one is wanted, is part of it — {@code "weather_"} names
 * a {@code forecast} method {@code weather_forecast}. Blank, the default, writes nothing.
 *
 * <p>
 * {@link #client()} names the chat client these tools belong to, by its bean name, so an application
 * with several chat clients can give each its own set; blank, the default, makes them available to
 * every chat client the application has.
 *
 * <p>
 * The prefix has two spellings — {@code @Tools("weather_")} and {@code @Tools(prefix = "weather_")} —
 * which are aliases of each other. A reader has to resolve the annotation through Spring's own
 * annotation support to see the one that was written; plain reflection reports only the member that was
 * named.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
@Component
public @interface Tools {

    /** Alias for {@link #prefix()}, so a class may write {@code @Tools("weather_")}. */
    @AliasFor("prefix")
    String value() default "";

    /** The text written in front of every tool name this class declares; blank for none. */
    @AliasFor("value")
    String prefix() default "";

    /** The chat client these tools belong to, by bean name; blank for every chat client. */
    String client() default "";

}
