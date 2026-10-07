package de.raindancer.modules.voicebridge.screen;

/** A screen belonging to this module; {@code describe()} is what a diagnostic names. */
public interface IVoiceBridgeScreen {

    void open();

    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
