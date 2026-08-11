package de.raindancer.modules.chained;

import de.raindancer.core.RainsCore;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.chained.rules.ChainDistanceRule;
import de.raindancer.modules.chained.service.ChainService;
import de.raindancer.modules.chained.store.ChainPairStore;
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

/**
 * Builds a {@link ChainedServices} with real domain objects where a test cares about real behaviour
 * (the pair store, the distance rule) and Mockito mocks everywhere else, left unstubbed unless a test
 * needs a particular answer.
 *
 * <p>Not a JUnit test itself — surefire's default include pattern does not pick this up.
 */
public final class FakeServices {

    private FakeServices() {
    }

    public static World world() {
        return mock(World.class);
    }

    public static Location at(World world, int x, int y, int z) {
        return new Location(world, x, y, z);
    }

    /**
     * A player whose Folia entity scheduler runs everything handed to it immediately, in place — so a
     * test does not need a real tick loop to observe what {@code Scheduling.entity} eventually does.
     */
    public static Player player(UUID uuid) {
        Player player = mock(Player.class);
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

    public static Builder builder() {
        return new Builder();
    }

    /** Only the collaborators a test actually names are anything other than an unstubbed mock. */
    public static final class Builder {
        private Plugin plugin = mock(Plugin.class);
        private Messages messages = mock(Messages.class);
        private ChainedSettings settings = ChainedSettings.DEFAULTS;
        private ChainPairStore pairs = new ChainPairStore();
        private ChainDistanceRule distance = new ChainDistanceRule();
        private ChainService chain = mock(ChainService.class);

        public Builder settings(ChainedSettings settings) {
            this.settings = settings;
            return this;
        }

        public Builder pairs(ChainPairStore pairs) {
            this.pairs = pairs;
            return this;
        }

        public Builder chain(ChainService chain) {
            this.chain = chain;
            return this;
        }

        @SuppressWarnings("unchecked")
        public ChainedServices build() {
            return new ChainedServices(plugin, mock(Server.class), mock(RainsCore.class),
                    mock(LogChannel.class), messages, mock(Chat.class), mock(Brand.class),
                    () -> settings, mock(SettingsStore.class),
                    pairs, distance, chain,
                    mock(IChainedScreensOpener.class));
        }
    }
}
