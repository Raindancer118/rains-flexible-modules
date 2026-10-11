package de.raindancer.modules.sends.screen;

/** A screen of this module. */
public interface ISendsScreen {

    void open();

    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
