package de.raindancer.modules.cosmetics.screen;

/**
 * A screen of this module, held to the menu grammar by {@code ScreenGrammarTest}: greyed never hidden,
 * modifiers advertised, nothing irreversible unconfirmed, buttons from Core's {@code Icons}, and every
 * refusal says something.
 */
public interface ICosmeticsScreen {

    void open();

    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
