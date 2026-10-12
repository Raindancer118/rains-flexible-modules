package de.raindancer.modules.arena.rules;

/**
 * A rule of this module: it decides and does nothing else — nothing saved, sent or scheduled — so a screen can ask it
 * speculatively and any thread can ask it at all.
 */
public interface IArenaRule {

    String describe();
}
