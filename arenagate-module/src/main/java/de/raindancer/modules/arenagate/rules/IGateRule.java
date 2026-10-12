package de.raindancer.modules.arenagate.rules;

/** A rule of this module: it decides and does nothing else, so it can be asked from anywhere, any time. */
public interface IGateRule {

    String describe();
}
