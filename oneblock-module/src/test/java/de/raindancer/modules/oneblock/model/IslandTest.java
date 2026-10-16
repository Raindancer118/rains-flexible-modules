package de.raindancer.modules.oneblock.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IslandTest {

    private static final UUID OWNER = UUID.fromString("3673c488-98af-41b2-9b72-6894f42640de");
    private static final UUID FRIEND = UUID.fromString("a1ecd8ef-6e59-4a4a-b99f-a85fe1082cdd");

    private static Island island(EconomyMode mode) {
        return Island.create(UUID.randomUUID(), OWNER, "Lilly", mode, 1000L);
    }

    @Test
    @DisplayName("a new island has its overworld block at the start spot and nothing else unlocked")
    void startsWithTheOverworld() {
        Island island = island(EconomyMode.PURE);

        assertThat(island.unlocked(IslandDimension.OVERWORLD)).isTrue();
        assertThat(island.unlocked(IslandDimension.NETHER)).isFalse();
        assertThat(island.unlocked(IslandDimension.END)).isFalse();
        assertThat(island.block(IslandDimension.OVERWORLD)).map(IslandBlock::spot).contains(BlockSpot.START);
        assertThat(island.autoUnload()).isTrue();
        assertThat(island.hold()).isEmpty();
    }

    @Test
    @DisplayName("the owner is a member and cannot be taken off their own island")
    void theOwnerStays() {
        Island island = island(EconomyMode.PURE).withMember(FRIEND);

        assertThat(island.isMember(OWNER)).isTrue();
        assertThat(island.isMember(FRIEND)).isTrue();
        assertThat(island.withoutMember(OWNER).isMember(OWNER)).isTrue();
        assertThat(island.withoutMember(FRIEND).isMember(FRIEND)).isFalse();
        assertThat(island.everyone()).containsExactly(OWNER, FRIEND);
        assertThat(island.withMember(FRIEND).withMember(OWNER).everyone()).hasSize(2);
    }

    @Test
    @DisplayName("the economy is chosen once: an undecided island takes a choice, a decided one never changes")
    void theModeIsChosenOnce() {
        assertThat(island(EconomyMode.UNDECIDED).decide(EconomyMode.SMP)).map(Island::mode).contains(EconomyMode.SMP);
        assertThat(island(EconomyMode.PURE).decide(EconomyMode.SMP)).isEmpty();
        assertThat(island(EconomyMode.SMP).decide(EconomyMode.PURE)).isEmpty();
        assertThat(island(EconomyMode.UNDECIDED).decide(EconomyMode.UNDECIDED)).isEmpty();
    }

    @Test
    @DisplayName("an undecided island plays without SMP money")
    void undecidedPlaysPure() {
        assertThat(island(EconomyMode.UNDECIDED).mode().allowsSmpMoney()).isFalse();
        assertThat(island(EconomyMode.SMP).mode().allowsSmpMoney()).isTrue();
    }

    @Test
    @DisplayName("shards are never spent into debt")
    void shardsNeverGoNegative() {
        Island island = island(EconomyMode.PURE).withShardsAdded(30);

        assertThat(island.spending(50)).isEmpty();
        assertThat(island.spending(30)).map(Island::shards).contains(0L);
        assertThat(island.spending(-5)).isEmpty();
        assertThat(island.withShardsAdded(-100).shards()).isZero();
    }

    @Test
    @DisplayName("unlocking a dimension puts its block at the start spot, once")
    void unlockingIsOnce() {
        Island unlocked = island(EconomyMode.PURE).unlocking(IslandDimension.NETHER).orElseThrow();

        assertThat(unlocked.unlocked(IslandDimension.NETHER)).isTrue();
        assertThat(unlocked.block(IslandDimension.NETHER)).map(IslandBlock::spot).contains(BlockSpot.START);
        assertThat(unlocked.unlocking(IslandDimension.NETHER)).isEmpty();
        assertThat(unlocked.unlocking(IslandDimension.OVERWORLD)).isEmpty();
    }

    @Test
    @DisplayName("each dimension has its own world, named after the island")
    void worldNames() {
        UUID id = UUID.fromString("18be87da-ae72-4295-afb7-3d9cd6da83d7");
        Island island = Island.create(id, OWNER, "Lilly", EconomyMode.PURE, 0);

        assertThat(island.worldName(IslandDimension.OVERWORLD)).isEqualTo("island_18be87daae724295");
        assertThat(island.worldName(IslandDimension.NETHER)).isEqualTo("island_18be87daae724295_nether");
        assertThat(island.worldName(IslandDimension.END)).isEqualTo("island_18be87daae724295_end");
        assertThat(Island.idPartOf("island_18be87daae724295_nether")).contains("18be87daae724295");
        assertThat(Island.dimensionOf("island_18be87daae724295_nether")).contains(IslandDimension.NETHER);
        assertThat(Island.dimensionOf("island_18be87daae724295")).contains(IslandDimension.OVERWORLD);
        assertThat(Island.dimensionOf("world")).isEmpty();
        assertThat(Island.idPartOf("island_")).isEmpty();
    }

    @Test
    @DisplayName("breaking counts on the dimension's own block")
    void breakingCountsPerDimension() {
        Island island = island(EconomyMode.PURE).unlocking(IslandDimension.NETHER).orElseThrow()
                .broken(IslandDimension.NETHER).broken(IslandDimension.NETHER).broken(IslandDimension.OVERWORLD);

        assertThat(island.block(IslandDimension.NETHER)).map(IslandBlock::broken).contains(2L);
        assertThat(island.block(IslandDimension.OVERWORLD)).map(IslandBlock::broken).contains(1L);
        assertThat(island.totalBroken()).isEqualTo(3);
        assertThat(island.broken(IslandDimension.END).unlocked(IslandDimension.END)).isFalse();
    }

    @Test
    @DisplayName("a hold is replaced and released as a whole")
    void holds() {
        Hold up = new Hold(Hold.Kind.UP, Hold.FOREVER, "admin");
        Island held = island(EconomyMode.PURE).withHold(Optional.of(up));

        assertThat(held.hold()).contains(up);
        assertThat(held.withHold(Optional.empty()).hold()).isEmpty();
        assertThat(held.withAutoUnload(false).autoUnload()).isFalse();
    }

    @Test
    @DisplayName("a fresh island takes a spawn, and arrivals use it instead of the block's top")
    void spawnOnAFreshIsland() {
        Island island = island(EconomyMode.PURE);
        assertThat(island.spawn(IslandDimension.OVERWORLD)).isEqualTo(Spawn.above(BlockSpot.START));

        Spawn set = new Spawn(3.5, 70, 3.5, 180f, 0f);
        assertThat(island.withSpawn(IslandDimension.OVERWORLD, set).spawn(IslandDimension.OVERWORLD)).isEqualTo(set);
        assertThat(island.withSpawn(IslandDimension.OVERWORLD, set).withSpawn(IslandDimension.OVERWORLD, null)
                .spawn(IslandDimension.OVERWORLD)).isEqualTo(Spawn.above(BlockSpot.START));
    }

    @Test
    @DisplayName("the end's block starts away from 0/0, where an end world builds its exit portal")
    void theEndStartsAside() {
        Island island = island(EconomyMode.PURE).unlocking(IslandDimension.END).orElseThrow();

        assertThat(island.block(IslandDimension.END).orElseThrow().spot())
                .isNotEqualTo(new BlockSpot(0, 64, 0)).isEqualTo(IslandDimension.END.start());
    }
}
