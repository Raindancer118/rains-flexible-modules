package de.raindancer.modules.performance.rules;

import de.raindancer.modules.performance.model.Cause;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a moment of the main thread was busy with. A stack is read from the top: the first frame
 * that belongs to a plugin makes it that plugin's time, however deep in vanilla it sits; otherwise
 * the vanilla area it is in. The stacks below are shortened copies of real ones from Lilly's SMP.
 */
class CauseRuleTest {

    /** Which plugin a class belongs to — in the server, the plugin whose class loader loaded it. */
    private static final Map<String, String> PLUGINS = Map.of(
            "com.example.lagger.Hopper", "LaggyHoppers",
            "de.raindancer.modules.anticheat.listener.MovementListener", "RainsAntiCheat");

    private final CauseRule rule = new CauseRule(type -> Optional.ofNullable(PLUGINS.get(type)));

    @Test
    @DisplayName("a mob's AI is entity time")
    void entities() {
        assertThat(rule.causeOf(List.of(
                "net.minecraft.world.entity.ai.goal.GoalSelector.tick",
                "net.minecraft.world.entity.Mob.aiStep",
                "net.minecraft.world.entity.LivingEntity.tick",
                "net.minecraft.server.level.ServerLevel.tickNonPassenger",
                "net.minecraft.server.level.ServerLevel.tick",
                "net.minecraft.server.MinecraftServer.tickChildren")))
                .isEqualTo(Cause.vanilla(Cause.Area.ENTITIES));
    }

    @Test
    @DisplayName("a pathfinder is its own area — a mob farm's usual cost")
    void pathfinding() {
        assertThat(rule.causeOf(List.of(
                "net.minecraft.world.level.pathfinder.PathFinder.findPath",
                "net.minecraft.world.entity.ai.navigation.PathNavigation.createPath",
                "net.minecraft.world.entity.Mob.aiStep",
                "net.minecraft.server.level.ServerLevel.tick")))
                .isEqualTo(Cause.vanilla(Cause.Area.PATHFINDING));
    }

    @Test
    @DisplayName("random ticks and spawning are chunk time")
    void chunks() {
        assertThat(rule.causeOf(List.of(
                "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase.randomTick",
                "net.minecraft.server.level.ServerLevel.tickChunk",
                "net.minecraft.server.level.ServerChunkCache.tickChunks",
                "net.minecraft.server.level.ServerLevel.tick")))
                .isEqualTo(Cause.vanilla(Cause.Area.CHUNKS));
    }

    @Test
    @DisplayName("hoppers and furnaces are block entities")
    void blockEntities() {
        assertThat(rule.causeOf(List.of(
                "net.minecraft.world.level.block.entity.HopperBlockEntity.pushItemsTick",
                "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity.tick",
                "net.minecraft.world.level.Level.tickBlockEntities")))
                .isEqualTo(Cause.vanilla(Cause.Area.BLOCK_ENTITIES));
    }

    @Test
    @DisplayName("redstone wire and pistons are redstone")
    void redstone() {
        assertThat(rule.causeOf(List.of(
                "net.minecraft.world.level.redstone.DefaultRedstoneWireEvaluator.updatePowerStrength",
                "net.minecraft.world.level.block.RedStoneWireBlock.neighborChanged",
                "net.minecraft.server.level.ServerLevel.tick")))
                .isEqualTo(Cause.vanilla(Cause.Area.REDSTONE));
    }

    @Test
    @DisplayName("a /fill is a command's time, not redstone, though vanilla keeps block updates in its redstone package")
    void neighbourUpdatesAreNotRedstone() {
        assertThat(rule.causeOf(List.of(
                "net.minecraft.world.level.redstone.CollectingNeighborUpdater.runUpdates",
                "net.minecraft.world.level.Level.setBlock",
                "net.minecraft.server.commands.FillCommand.fillBlocks",
                "net.minecraft.commands.Commands.performCommand")))
                .isEqualTo(Cause.vanilla(Cause.Area.COMMANDS));
    }

    @Test
    @DisplayName("loading and generating chunks is its own area")
    void chunkLoading() {
        assertThat(rule.causeOf(List.of(
                "net.minecraft.world.level.chunk.storage.RegionFile.read",
                "net.minecraft.server.level.ChunkMap.scheduleChunkLoad",
                "net.minecraft.server.level.ServerChunkCache.getChunk")))
                .isEqualTo(Cause.vanilla(Cause.Area.CHUNK_LOADING));
    }

    @Test
    @DisplayName("a plugin anywhere in the stack makes it that plugin's time, even under vanilla frames")
    void plugin() {
        assertThat(rule.causeOf(List.of(
                "net.minecraft.world.level.block.entity.HopperBlockEntity.pushItemsTick",
                "com.example.lagger.Hopper.onMove",
                "org.bukkit.plugin.java.JavaPluginLoader$1.execute",
                "net.minecraft.world.level.Level.tickBlockEntities")))
                .isEqualTo(Cause.plugin("LaggyHoppers"));
    }

    @Test
    @DisplayName("the plugin nearest the top is the one charged")
    void nearestPlugin() {
        assertThat(rule.causeOf(List.of(
                "de.raindancer.modules.anticheat.listener.MovementListener.onMove",
                "com.example.lagger.Hopper.onMove")))
                .isEqualTo(Cause.plugin("RainsAntiCheat"));
    }

    @Test
    @DisplayName("the thread waiting for the next tick is idle, not busy")
    void idle() {
        assertThat(rule.causeOf(List.of(
                "java.util.concurrent.locks.LockSupport.parkNanos",
                "net.minecraft.server.MinecraftServer.waitUntilNextTick",
                "net.minecraft.server.MinecraftServer.runServer")))
                .isEqualTo(Cause.vanilla(Cause.Area.IDLE));
    }

    @Test
    @DisplayName("26.3 waits for the next tick somewhere else — parked outside a tick is idle, whatever the method is called")
    void idleOn263() {
        assertThat(rule.causeOf(List.of(
                "jdk.internal.misc.Unsafe.park",
                "java.util.concurrent.locks.LockSupport.parkNanos",
                "net.minecraft.server.MinecraftServer.recordTaskExecutionTimeWhileWaiting",
                "net.minecraft.server.MinecraftServer.runServer",
                "net.minecraft.server.MinecraftServer.lambda$spin$0",
                "java.lang.Thread.run")))
                .isEqualTo(Cause.vanilla(Cause.Area.IDLE));
    }

    @Test
    @DisplayName("parked inside a tick is not idle: the tick is waiting for a chunk, which is lag")
    void parkedInATick() {
        assertThat(rule.causeOf(List.of(
                "jdk.internal.misc.Unsafe.park",
                "java.util.concurrent.locks.LockSupport.park",
                "net.minecraft.server.level.ServerChunkCache.getChunk",
                "net.minecraft.server.level.ServerLevel.tick",
                "net.minecraft.server.MinecraftServer.tickChildren",
                "net.minecraft.server.MinecraftServer.tickServer")))
                .isEqualTo(Cause.vanilla(Cause.Area.CHUNK_LOADING));
    }

    @Test
    @DisplayName("anything else is other, and an empty stack is idle")
    void other() {
        assertThat(rule.causeOf(List.of("net.minecraft.server.network.ServerConnectionListener.tick")))
                .isEqualTo(Cause.vanilla(Cause.Area.OTHER));
        assertThat(rule.causeOf(List.of())).isEqualTo(Cause.vanilla(Cause.Area.IDLE));
    }
}
