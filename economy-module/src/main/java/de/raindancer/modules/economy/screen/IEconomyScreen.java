package de.raindancer.modules.economy.screen;

/** A screen belonging to this module; {@code describe()} is what a diagnostic names. */
public interface IEconomyScreen {

    void open();

    default String describe() {
        return getClass().getSimpleName();
    }
}
