package de.raindancer.modules.anticheat.rules;

/** Decides, and does nothing else: no side effects, safe from any thread. */
public interface IAntiCheatRule {

    String describe();
}
