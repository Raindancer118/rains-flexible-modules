package de.raindancer.modules.claims;

import de.raindancer.modules.claims.model.ClaimNames;
import de.raindancer.modules.claims.selection.SelectionFlow;
import de.raindancer.modules.claims.selection.SelectionService;
import de.raindancer.modules.claims.selection.SelectionStick;
import de.raindancer.modules.claims.service.AmbienceService;
import de.raindancer.modules.claims.service.BroadcastService;
import de.raindancer.modules.claims.service.ClaimService;
import de.raindancer.modules.claims.service.CostService;
import de.raindancer.modules.claims.service.EntryFeeService;
import de.raindancer.modules.claims.service.EquipService;
import de.raindancer.modules.claims.service.EvictionService;
import de.raindancer.modules.claims.service.FenceService;
import de.raindancer.modules.claims.store.ClaimLandProvider;
import de.raindancer.modules.claims.store.ClaimRegistry;
import de.raindancer.modules.claims.store.ClaimStorage;
import de.raindancer.modules.claims.store.ZoneRegistry;
import de.raindancer.modules.claims.rules.ClaimRightsRule;
import de.raindancer.modules.claims.rules.FeatureRules;
import de.raindancer.modules.claims.visual.BorderVisualizer;
import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.prompt.ChatPrompts;
import de.raindancer.core.world.protection.FlagRules;
import de.raindancer.core.world.protection.Land;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Builds a {@link ClaimServices} with real domain objects where the tests care about real behaviour
 * (the claim registry, a claim's own ban list) and Mockito mocks everywhere else, left unstubbed unless
 * a test needs a particular answer — which is deliberate: an unstubbed mock's default (false, null, an
 * empty collection) is what makes {@code onEnter}/{@code onLeave}'s optional feature checks fall through
 * to a no-op instead of every test having to wire up titles, border flashes and entry-fee prompts it is
 * not about.
 * <p>
 * Not a JUnit test itself — surefire's default include pattern does not pick this up.
 */
final class FakeServices {

    static final UUID WORLD = UUID.randomUUID();

    private FakeServices() {
    }

    static World world() {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(WORLD);
        return world;
    }

    static Location at(World world, int x, int y, int z) {
        return new Location(world, x, y, z);
    }

    /**
     * A player whose Folia entity scheduler runs everything handed to it immediately, in place — so a
     * test does not need a real tick loop to observe what {@code Scheduling.entity}/{@code entityLater}
     * eventually do.
     */
    static Player player(UUID uuid) {
        Player player = mock(Player.class);
        // Lenient throughout: this is a general-purpose fixture, and a test that short-circuits before
        // ever asking the player's identity or scheduler (a border check skipped for a sub-block wobble,
        // say) is not a reason to make every caller stub its own player from scratch.
        lenient().when(player.getUniqueId()).thenReturn(uuid);
        EntityScheduler scheduler = mock(EntityScheduler.class);
        lenient().when(scheduler.run(any(), any(), any())).thenAnswer(FakeServices::runNow);
        lenient().when(scheduler.runDelayed(any(), any(), any(), anyLong())).thenAnswer(FakeServices::runNow);
        lenient().when(player.getScheduler()).thenReturn(scheduler);
        return player;
    }

    @SuppressWarnings("unchecked")
    private static ScheduledTask runNow(org.mockito.invocation.InvocationOnMock invocation) {
        Consumer<ScheduledTask> task = invocation.getArgument(1);
        task.accept(null);
        return null;
    }

    static Builder builder() {
        return new Builder();
    }

    /** Only the collaborators a test actually names are anything other than an unstubbed mock. */
    static final class Builder {
        private Plugin plugin = mock(Plugin.class);
        private ClaimRegistry claims = new ClaimRegistry();
        private Land land = mock(Land.class);
        private Messages messages = mock(Messages.class);
        private ClaimNames names = mock(ClaimNames.class);
        private ClaimSettings settings = ClaimSettings.DEFAULTS;
        private EvictionService eviction = mock(EvictionService.class);
        private ClaimLandProvider provider = mock(ClaimLandProvider.class);

        Builder claims(ClaimRegistry claims) {
            this.claims = claims;
            return this;
        }

        Builder land(Land land) {
            this.land = land;
            return this;
        }

        Builder settings(ClaimSettings settings) {
            this.settings = settings;
            return this;
        }

        Builder eviction(EvictionService eviction) {
            this.eviction = eviction;
            return this;
        }

        ClaimServices build() {
            // The movement tracker is built from the very ClaimServices it will be handed back through,
            // same as ClaimsModule wires it in production — a mutable holder stands in for the field that
            // is filled in immediately after construction.
            de.raindancer.modules.claims.listener.MovementListener[] movement =
                    new de.raindancer.modules.claims.listener.MovementListener[1];
            ClaimServices services = new ClaimServices(plugin, mock(Server.class), mock(LogChannel.class),
                    messages, mock(Brand.class), mock(ChatPrompts.class), land, mock(FlagRules.class),
                    mock(FeatureRules.class), claims, mock(ClaimStorage.class), mock(ZoneRegistry.class),
                    mock(ClaimService.class), names, mock(ClaimRightsRule.class), provider,
                    mock(CostService.class), mock(SelectionService.class), mock(SelectionStick.class),
                    mock(SelectionFlow.class), mock(BorderVisualizer.class), mock(FenceService.class),
                    mock(AmbienceService.class), mock(EntryFeeService.class), eviction,
                    mock(EquipService.class), mock(BroadcastService.class), () -> settings,
                    mock(ClaimScreensOpener.class), () -> movement[0], () -> {
            }, () -> true, mock(RainsCore.class));
            movement[0] = new de.raindancer.modules.claims.listener.MovementListener(services);
            return services;
        }
    }
}
