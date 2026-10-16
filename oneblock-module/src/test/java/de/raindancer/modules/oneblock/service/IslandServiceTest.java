package de.raindancer.modules.oneblock.service;

import de.raindancer.modules.oneblock.model.Content;
import de.raindancer.modules.oneblock.model.EconomyMode;
import de.raindancer.modules.oneblock.model.Island;
import de.raindancer.modules.oneblock.model.IslandDimension;
import de.raindancer.modules.oneblock.model.Reply;
import de.raindancer.modules.oneblock.model.Spawn;
import de.raindancer.modules.oneblock.model.Upgrade;
import de.raindancer.modules.oneblock.store.IslandBook;
import de.raindancer.modules.oneblock.store.IslandStorage;
import de.raindancer.modules.oneblock.store.PlayerSpot;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IslandServiceTest {

    private static final IslandStorage NOWHERE = new IslandStorage() {
        @Override
        public Optional<List<Island>> loadAll() {
            return Optional.of(List.of());
        }

        @Override
        public boolean save(Island island) {
            return true;
        }

        @Override
        public boolean archive(Island island) {
            return true;
        }

        @Override
        public boolean savePosition(UUID player, PlayerSpot spot, long now) {
            return true;
        }

        @Override
        public Optional<PlayerSpot> position(UUID player) {
            return Optional.empty();
        }

        @Override
        public Optional<String> modeOf(UUID player) {
            return Optional.empty();
        }

        @Override
        public Optional<java.util.Map<UUID, String>> modesOf(java.util.Collection<UUID> players) {
            return Optional.of(java.util.Map.of());
        }
    };

    private static final Upgrade COAL = new Upgrade("coal", "Coal", Upgrade.Kind.BLOCK, Material.COAL_ORE, null,
            Set.of(IslandDimension.OVERWORLD), List.of(new Upgrade.Level(5, 20), new Upgrade.Level(10, 50)));
    private static final Content CONTENT = new Content(Map.of(), Map.of("coal", COAL),
            Map.of(IslandDimension.NETHER, 300L, IslandDimension.END, 1000L), 10, 1, 3, 8);

    private final IslandBook book = new IslandBook(NOWHERE);
    private final IslandService service = new IslandService(book, () -> CONTENT, () -> 42L);
    private final UUID ada = UUID.randomUUID();
    private final UUID bo = UUID.randomUUID();
    private final UUID cy = UUID.randomUUID();

    private Island adas() {
        return book.ofPlayer(ada).orElseThrow();
    }

    private void give(long shards) {
        book.change(adas().id(), it -> Optional.of(it.withShardsAdded(shards)));
    }

    @Test
    @DisplayName("an island is made once per player, with a money chosen there and then")
    void create() {
        assertThat(service.create(ada, "Ada", EconomyMode.UNDECIDED).key()).isEqualTo("oneblock.create.choose");
        Reply made = service.create(ada, "Ada", EconomyMode.SMP);

        assertThat(made.ok()).isTrue();
        assertThat(adas().mode()).isEqualTo(EconomyMode.SMP);
        assertThat(adas().created()).isEqualTo(42L);
        assertThat(service.create(ada, "Ada", EconomyMode.PURE).key()).isEqualTo("oneblock.create.already");
        assertThat(adas().mode()).as("asking again changes nothing").isEqualTo(EconomyMode.SMP);
    }

    @Test
    @DisplayName("an undecided island's owner picks the money once; nobody else, never again")
    void decide() {
        book.add(Island.create(UUID.randomUUID(), ada, "Ada", EconomyMode.UNDECIDED, 0).withMember(bo));

        assertThat(service.decide(bo, EconomyMode.SMP).key()).isEqualTo("oneblock.mode.owner-only");
        assertThat(service.decide(ada, EconomyMode.PURE).ok()).isTrue();
        assertThat(service.decide(ada, EconomyMode.SMP).key()).isEqualTo("oneblock.mode.already");
        assertThat(adas().mode()).isEqualTo(EconomyMode.PURE);
    }

    @Test
    @DisplayName("joining a team: not from an island of your own, and not past the team size")
    void join() {
        service.create(ada, "Ada", EconomyMode.PURE);
        UUID island = adas().id();

        assertThat(service.join(bo, island).ok()).isTrue();
        assertThat(service.join(bo, island)).as("joining again is nothing").isEqualTo(Reply.silent());
        assertThat(service.join(cy, island).ok()).isTrue();
        UUID dee = UUID.randomUUID();
        assertThat(service.join(dee, island).key()).isEqualTo("oneblock.join.full");

        UUID owner = UUID.randomUUID();
        service.create(owner, "Owner", EconomyMode.SMP);
        UUID other = book.ofPlayer(owner).orElseThrow().id();
        assertThat(service.join(owner, island).key()).isEqualTo("oneblock.join.own-island");
        assertThat(service.join(bo, other).key()).isEqualTo("oneblock.join.other-island");
    }

    @Test
    @DisplayName("a member leaves; the owner gives up an island only when alone on it, and it is archived")
    void leave() {
        service.create(ada, "Ada", EconomyMode.PURE);
        service.join(bo, adas().id());

        assertThat(service.leave(ada).key()).isEqualTo("oneblock.leave.has-team");
        assertThat(service.leave(bo).ok()).isTrue();
        assertThat(book.ofPlayer(bo)).isEmpty();
        assertThat(service.leave(ada).key()).isEqualTo("oneblock.leave.abandoned");
        assertThat(book.ofPlayer(ada)).isEmpty();
        assertThat(service.create(ada, "Ada", EconomyMode.SMP).ok()).as("and may start a new one").isTrue();
    }

    @Test
    @DisplayName("only the owner kicks, and only somebody on the team")
    void kick() {
        service.create(ada, "Ada", EconomyMode.PURE);
        service.join(bo, adas().id());

        assertThat(service.kick(bo, ada, "Ada").key()).isEqualTo("oneblock.kick.owner-only");
        assertThat(service.kick(ada, ada, "Ada").key()).isEqualTo("oneblock.kick.self");
        assertThat(service.kick(ada, cy, "Cy").key()).isEqualTo("oneblock.kick.not-member");
        assertThat(service.kick(ada, bo, "Bo").ok()).isTrue();
        assertThat(adas().isMember(bo)).isFalse();
    }

    @Test
    @DisplayName("unlocking takes the price off the island's shards and opens the dimension")
    void unlock() {
        service.create(ada, "Ada", EconomyMode.PURE);
        assertThat(service.unlock(ada, IslandDimension.NETHER).key()).isEqualTo("oneblock.buy.too-poor");
        give(350);

        assertThat(service.unlock(ada, IslandDimension.NETHER).ok()).isTrue();
        assertThat(adas().shards()).isEqualTo(50);
        assertThat(adas().unlocked(IslandDimension.NETHER)).isTrue();
        assertThat(service.unlock(ada, IslandDimension.NETHER).key()).isEqualTo("oneblock.unlock.already");
    }

    @Test
    @DisplayName("an upgrade's level goes up by one per purchase, paid by the island")
    void buy() {
        service.create(ada, "Ada", EconomyMode.PURE);
        service.join(bo, adas().id());
        give(70);

        assertThat(service.buy(bo, IslandDimension.OVERWORLD, "coal").ok()).isTrue();
        assertThat(service.buy(ada, IslandDimension.OVERWORLD, "coal").ok()).isTrue();
        assertThat(adas().shards()).isZero();
        assertThat(adas().block(IslandDimension.OVERWORLD).orElseThrow().level("coal")).isEqualTo(2);
        assertThat(service.buy(ada, IslandDimension.OVERWORLD, "coal").key()).isEqualTo("oneblock.buy.maxed");
        assertThat(service.buy(cy, IslandDimension.OVERWORLD, "coal").key()).isEqualTo("oneblock.refuse.no-island");
    }

    @Test
    @DisplayName("the spawn is set only on the player's own island")
    void spawn() {
        service.create(ada, "Ada", EconomyMode.PURE);
        service.create(bo, "Bo", EconomyMode.PURE);
        Spawn here = new Spawn(1, 65, 1, 0, 0);

        assertThat(service.setSpawn(ada, book.ofPlayer(bo).orElseThrow().worldName(IslandDimension.OVERWORLD), here)
                .key()).isEqualTo("oneblock.spawn.not-here");
        assertThat(service.setSpawn(ada, "world", here).key()).isEqualTo("oneblock.spawn.not-here");
        assertThat(service.setSpawn(ada, adas().worldName(IslandDimension.OVERWORLD), here).ok()).isTrue();
        assertThat(adas().spawn(IslandDimension.OVERWORLD)).isEqualTo(here);
    }
}
