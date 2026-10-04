package de.raindancer.e2e.speedrun;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Which entries of the {@link Catalog} a scenario plays — what {@link CoverageTest} checks every entry
 * has, so a new word, page, setting or chat button cannot ship without a scenario that plays it.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Covers {

    /** Catalogue ids, exactly as {@link Catalog} spells them. */
    String[] value();
}
