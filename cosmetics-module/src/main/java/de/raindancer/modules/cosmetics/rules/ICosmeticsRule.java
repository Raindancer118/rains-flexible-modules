package de.raindancer.modules.cosmetics.rules;

/**
 * Something that decides and does nothing else: no saving, no sending, no scheduling, safe from any
 * thread. Screens ask a rule speculatively to grey a button, so a rule that acted would act on a look.
 */
public interface ICosmeticsRule {

    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
