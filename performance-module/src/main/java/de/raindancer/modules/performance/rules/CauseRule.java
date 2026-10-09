package de.raindancer.modules.performance.rules;

import de.raindancer.modules.performance.model.Cause;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * What a sampled stack of the server thread was spent on. Frames are {@code class.method}, top first.
 * A plugin frame anywhere wins, nearest the top first — vanilla code a plugin called is still that
 * plugin's time. Otherwise the first frame from the top that belongs to a known area decides.
 */
public final class CauseRule implements IPerformanceRule {

    private record Marker(Cause.Area area, String... fragments) {
        boolean matches(String frame) {
            for (String fragment : fragments) {
                if (frame.contains(fragment)) {
                    return true;
                }
            }
            return false;
        }
    }

    // Most specific first: pathfinding is inside entity ticking, block entities inside chunks.
    private static final List<Marker> MARKERS = List.of(
            new Marker(Cause.Area.PATHFINDING, ".pathfinder.", ".ai.navigation."),
            // Not the package ".redstone.": vanilla keeps every block's neighbour updates there, a /fill included.
            new Marker(Cause.Area.REDSTONE, "RedstoneWireEvaluator", "RedStoneWireBlock", "PistonBaseBlock",
                    "PistonMovingBlockEntity", "DiodeBlock", "ObserverBlock", "RedstoneTorchBlock", "ComparatorBlock"),
            new Marker(Cause.Area.BLOCK_ENTITIES, ".block.entity.", "tickBlockEntities"),
            new Marker(Cause.Area.CHUNK_LOADING, ".chunk.storage.", "scheduleChunkLoad", "ServerChunkCache.getChunk",
                    ".levelgen.", "ChunkGenerator", ".chunk.status."),
            new Marker(Cause.Area.ENTITIES, ".world.entity.", "tickNonPassenger", "EntityTickList"),
            new Marker(Cause.Area.CHUNKS, "tickChunk", "randomTick", "NaturalSpawner", "ServerChunkCache.tick"),
            new Marker(Cause.Area.COMMANDS, "Commands.performCommand", ".commands.", "ServerFunctionManager"));

    private final Function<String, Optional<String>> pluginOfClass;

    /** @param pluginOfClass the plugin a class belongs to, for a class name; empty for the server's own */
    public CauseRule(Function<String, Optional<String>> pluginOfClass) {
        this.pluginOfClass = pluginOfClass;
    }

    public Cause causeOf(List<String> frames) {
        if (frames.isEmpty() || isWaiting(frames)) {
            return Cause.vanilla(Cause.Area.IDLE);
        }
        for (String frame : frames) {
            Optional<String> plugin = pluginOfClass.apply(classOf(frame));
            if (plugin.isPresent()) {
                return Cause.plugin(plugin.get());
            }
        }
        for (String frame : frames) {
            for (Marker marker : MARKERS) {
                if (marker.matches(frame)) {
                    return Cause.vanilla(marker.area());
                }
            }
        }
        return Cause.vanilla(Cause.Area.OTHER);
    }

    /**
     * Parked, and not inside a tick: waiting for the next one. Where exactly it waits changes between
     * versions (26.3 parks in {@code recordTaskExecutionTimeWhileWaiting}), so this asks what it is not
     * doing rather than naming the method. Parked inside a tick — on a chunk, say — is lag, not idle.
     */
    private static boolean isWaiting(List<String> frames) {
        String top = frames.getFirst();
        boolean parked = top.startsWith("java.util.concurrent.locks.LockSupport.park") || top.startsWith("jdk.internal.misc.Unsafe.park")
                || top.startsWith("java.lang.Thread.sleep");
        return parked && frames.stream().noneMatch(frame -> frame.contains("MinecraftServer.tickServer")
                || frame.contains("MinecraftServer.tickChildren"));
    }

    static String classOf(String frame) {
        int dot = frame.lastIndexOf('.');
        return dot < 0 ? frame : frame.substring(0, dot);
    }

    @Override
    public String describe() {
        return "charges a sampled moment of the server thread to a plugin, or to an area of the game";
    }
}
