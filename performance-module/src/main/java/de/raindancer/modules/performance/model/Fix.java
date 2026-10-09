package de.raindancer.modules.performance.model;

/**
 * Something that could be done about a finding. Proposed, never applied by itself — staff click it.
 *
 * @param what   the entity type, plugin name, or nothing — depending on the action
 * @param amount how many to keep, the new distance, or the share in percent — depending on the action
 */
public record Fix(Action action, String what, int amount) {

    public enum Action {
        /** Remove animals of one type in the chunk until {@code amount} are left; named, tamed and leashed ones stay. */
        THIN_ANIMALS,
        /** Remove dropped items and experience orbs in the chunk. */
        CLEAR_ITEMS,
        /** Remove hostile mobs in the chunk that would despawn anyway. */
        CLEAR_MONSTERS,
        /** Tell whoever owns the land what was found there. */
        TELL_OWNER,
        /** Set every world's simulation distance to {@code amount} — undone with the same button reversed. */
        SIMULATION_DISTANCE,
        /** Only information: this plugin had {@code amount} % of the busy time. */
        SUSPECT_PLUGIN
    }

    public static Fix thin(String type, int keep) {
        return new Fix(Action.THIN_ANIMALS, type, keep);
    }

    public static Fix clearItems() {
        return new Fix(Action.CLEAR_ITEMS, "", 0);
    }

    public static Fix clearMonsters() {
        return new Fix(Action.CLEAR_MONSTERS, "", 0);
    }

    public static Fix tellOwner() {
        return new Fix(Action.TELL_OWNER, "", 0);
    }

    public static Fix simulationDistance(int chunks) {
        return new Fix(Action.SIMULATION_DISTANCE, "", chunks);
    }

    public static Fix suspectPlugin(String plugin, int percent) {
        return new Fix(Action.SUSPECT_PLUGIN, plugin, percent);
    }

    /** Whether there is anything to click — a suspect is only named. */
    public boolean isAction() {
        return action != Action.SUSPECT_PLUGIN;
    }
}
