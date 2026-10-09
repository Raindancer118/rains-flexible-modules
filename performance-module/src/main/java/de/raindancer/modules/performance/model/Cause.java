package de.raindancer.modules.performance.model;

import java.util.Locale;

/** What one moment of the server thread was spent on: a plugin, or an area of the game itself. */
public record Cause(Area area, String plugin) {

    public enum Area {
        IDLE, PLUGIN, ENTITIES, PATHFINDING, CHUNKS, BLOCK_ENTITIES, REDSTONE, CHUNK_LOADING, COMMANDS, OTHER;

        public String readable() {
            return switch (this) {
                case IDLE -> "waiting";
                case PLUGIN -> "a plugin";
                case ENTITIES -> "mobs and other entities";
                case PATHFINDING -> "mobs finding their way";
                case CHUNKS -> "chunks (crops, spawning, random ticks)";
                case BLOCK_ENTITIES -> "hoppers, furnaces and other block entities";
                case REDSTONE -> "redstone";
                case CHUNK_LOADING -> "loading and generating chunks";
                case COMMANDS -> "commands (a /fill, a datapack function)";
                case OTHER -> "everything else";
            };
        }
    }

    public static Cause vanilla(Area area) {
        return new Cause(area, null);
    }

    public static Cause plugin(String name) {
        return new Cause(Area.PLUGIN, name);
    }

    public boolean isPlugin() {
        return area == Area.PLUGIN;
    }

    public String readable() {
        return isPlugin() ? "the plugin " + plugin : area.readable();
    }

    @Override
    public String toString() {
        return isPlugin() ? "plugin:" + plugin : area.name().toLowerCase(Locale.ROOT);
    }
}
