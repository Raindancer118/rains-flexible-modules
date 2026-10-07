package de.raindancer.modules.anticheat.screen;

/** A screen of this module: greyed rather than hidden, refusals said out loud, nothing irreversible unconfirmed. */
public interface IAntiCheatScreen {

    void open();

    default String describe() {
        return getClass().getSimpleName();
    }
}
