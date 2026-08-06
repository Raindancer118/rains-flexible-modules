package de.raindancer.modules.hungergames;

import de.raindancer.core.content.items.CustomItem;
import de.raindancer.core.content.items.ItemFactory;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.rules.TeamRules;
import de.raindancer.modules.hungergames.service.AnnouncementService;
import de.raindancer.modules.hungergames.service.SponsorTokenService;
import de.raindancer.modules.hungergames.store.GameSession;
import de.raindancer.modules.hungergames.store.RuntimeStore;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The wave tick and the item's identity — both without a running server: {@link ItemFactory} is mocked, so
 * "is this a real token" is a question this test controls the answer to directly.
 */
@ExtendWith(MockitoExtension.class)
class SponsorTokenServiceTest {

    private static final CustomItem TOKEN_ITEM = CustomItem.builder("hungergames", "sponsor-token")
            .material(Material.NETHER_STAR)
            .name("Sponsor Token")
            .build();

    private GameSession session;
    private final List<String> logs = new ArrayList<>();

    @Mock
    private ItemFactory items;
    @Mock
    private AnnouncementService announcements;

    private RuntimeStore runtimeStore;
    private SponsorTokenService service;
    private final UUID katniss = UUID.randomUUID();

    @BeforeEach
    void setUp(@TempDir Path dir) {
        session = new GameSession(TeamRules::defaults, new RecordingGameEvents(),
                new InMemorySessionStore(), () -> 0L, new Random(1));
        session.whitelistAdd(katniss, "Katniss");
        session.whitelistAdd(UUID.randomUUID(), "Peeta");
        session.transitionTo(GamePhase.PREFLIGHT);
        session.transitionTo(GamePhase.LOBBY);
        session.transitionTo(GamePhase.STARTUP);
        session.transitionTo(GamePhase.READY);
        session.transitionTo(GamePhase.RUNNING);

        runtimeStore = new RuntimeStore(dir.resolve("runtime.yml"));
        service = new SponsorTokenService(session, items, TOKEN_ITEM, (player, stack) -> given.add(stack),
                announcements, (category, message) -> logs.add(category + ":" + message), runtimeStore);
    }

    private final List<ItemStack> given = new ArrayList<>();

    @Test
    @DisplayName("isToken asks Core's registry rather than checking a material")
    void isTokenAsksTheRegistry() {
        ItemStack stack = mock(ItemStack.class);
        when(items.is(stack, TOKEN_ITEM.key())).thenReturn(true);

        assertThat(service.isToken(stack)).isTrue();
        assertThat(service.isToken(null)).isFalse();
    }

    @Test
    @DisplayName("a due wave gives every online, alive tribute their tokens")
    void aDueWaveGivesTokens() {
        ItemStack minted = mock(ItemStack.class);
        when(items.create(TOKEN_ITEM, 3)).thenReturn(Optional.of(minted));
        // The wave path never reads a player's name (roundLog uses the participant's stored name), so
        // stubbing player.getName() here would be unused and Mockito's strict stubbing would fail the test.
        Player player = mock(Player.class);
        Function<UUID, Player> online = uuid -> uuid.equals(katniss) ? player : null;

        SponsorTokenService.Schedule schedule = new SponsorTokenService.Schedule(
                Duration.ofSeconds(10), Duration.ofSeconds(60), 3, 0, true);

        service.tick(Duration.ofSeconds(10), schedule, online);

        assertThat(given).containsExactly(minted);
        assertThat(service.earnedTotal(katniss)).isEqualTo(3);
        assertThat(logs).anyMatch(line -> line.contains("Katniss"));
    }

    @Test
    @DisplayName("an offline tribute is skipped rather than crashing the tick")
    void offlineTributeIsSkipped() {
        Function<UUID, Player> nobodyOnline = uuid -> null;
        SponsorTokenService.Schedule schedule = new SponsorTokenService.Schedule(
                Duration.ofSeconds(10), Duration.ofSeconds(60), 3, 0, true);

        service.tick(Duration.ofSeconds(10), schedule, nobodyOnline);

        assertThat(given).isEmpty();
        assertThat(service.earnedTotal(katniss)).isZero();
    }

    @Test
    @DisplayName("nothing is granted before RUNNING")
    void nothingBeforeRunning() {
        session.declareTimeout(); // -> FINISHED
        Player player = mock(Player.class);
        SponsorTokenService.Schedule schedule = new SponsorTokenService.Schedule(
                Duration.ZERO, Duration.ofSeconds(60), 3, 0, true);

        service.tick(Duration.ofSeconds(10), schedule, uuid -> player);

        assertThat(given).isEmpty();
    }

    @Test
    @DisplayName("a manual grant is logged and does not count toward earnedTotal")
    void manualGrantDoesNotCountAsEarned() {
        ItemStack minted = mock(ItemStack.class);
        when(items.create(TOKEN_ITEM, 5)).thenReturn(Optional.of(minted));
        Player target = mock(Player.class);
        when(target.getName()).thenReturn("Katniss");

        service.giveManually("Haymitch", target, 5);

        assertThat(given).containsExactly(minted);
        assertThat(service.earnedTotal(katniss)).isZero();
        assertThat(logs).anyMatch(line -> line.contains("Haymitch") && line.contains("5"));
    }

    @Test
    @DisplayName("clearTokens removes every token from the inventory and counts them")
    void clearTokensRemovesAndCounts() {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        ItemStack tokenStack = mock(ItemStack.class);
        when(tokenStack.getAmount()).thenReturn(4);
        when(items.is(tokenStack, TOKEN_ITEM.key())).thenReturn(true);
        ItemStack[] contents = new ItemStack[]{tokenStack, null};
        when(inventory.getContents()).thenReturn(contents);

        int removed = service.clearTokens(player);

        assertThat(removed).isEqualTo(4);
        verify(inventory).setItem(0, null);
    }

    @Test
    @DisplayName("resetForNewRound forgets every player's progress and persists the empty state")
    void resetForgetsEverything() {
        ItemStack minted = mock(ItemStack.class);
        when(items.create(TOKEN_ITEM, 3)).thenReturn(Optional.of(minted));
        Player player = mock(Player.class);
        SponsorTokenService.Schedule schedule = new SponsorTokenService.Schedule(
                Duration.ZERO, Duration.ofSeconds(60), 3, 0, true);
        service.tick(Duration.ofSeconds(1), schedule, uuid -> player);
        assertThat(service.earnedTotal(katniss)).isEqualTo(3);

        service.resetForNewRound();

        assertThat(service.earnedTotal(katniss)).isZero();
        assertThat(runtimeStore.loadTokenState()).isEmpty();
    }

    @Test
    @DisplayName("start() reloads whatever progress was persisted before a restart")
    void startReloadsPersistedProgress() {
        Map<UUID, RuntimeStore.TokenState> persisted = new HashMap<>();
        persisted.put(katniss, new RuntimeStore.TokenState(2, 6));
        runtimeStore.saveTokenState(persisted);

        service.start();

        assertThat(service.earnedTotal(katniss)).isEqualTo(6);
    }
}
