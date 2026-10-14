package de.raindancer.modules.guilds.service;

import de.raindancer.core.platform.network.Arrivals;
import de.raindancer.core.platform.network.NetworkTravel;
import de.raindancer.core.platform.util.Cooldowns;
import de.raindancer.core.platform.util.Times;
import de.raindancer.core.world.teleport.Companions;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.core.world.teleport.TravelReason;
import de.raindancer.core.world.teleport.TravelWatcher;
import de.raindancer.core.world.teleport.Trip;
import de.raindancer.modules.guilds.GuildsSettings;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.GuildEvent;
import de.raindancer.modules.guilds.model.GuildOutcome;
import de.raindancer.modules.guilds.model.GuildPermission;
import de.raindancer.modules.guilds.model.HomeSpot;
import de.raindancer.modules.guilds.rules.LevelRule;
import de.raindancer.modules.guilds.rules.RankRule;
import de.raindancer.modules.guilds.store.GuildBook;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The guild home: unlocked by level, set by those allowed, reached through Core's {@code Travel}. A home is in a
 * world of one server and carries that server's id; from another server of the network it is reached by sending
 * the player there, to land at it.
 */
public final class HomeService implements IGuildsService {

    private final GuildBook book;
    private final GuildNotifier notify;
    private final Server server;
    private final Travel travel;
    private final LongSupplier clock;
    private final RankRule ranks = new RankRule();
    private final Cooldowns<UUID> waits;
    private volatile GuildsSettings settings;
    private volatile LevelRule levels;
    private volatile Supplier<String> self = () -> "";
    private volatile Supplier<Optional<NetworkTravel>> networkTravel = Optional::empty;

    /**
     * Makes homes the network's: a home set here carries {@code self}, and one on another server is reached
     * through {@code travel}. {@code self} is blank on a server of its own, where every world is here.
     */
    public void network(Supplier<String> self, Supplier<Optional<NetworkTravel>> travel) {
        this.self = self == null ? () -> "" : self;
        this.networkTravel = travel == null ? Optional::empty : travel;
    }

    public HomeService(GuildBook book, GuildNotifier notify, Server server, Travel travel, LongSupplier clock,
                       GuildsSettings settings) {
        this.book = book;
        this.notify = notify;
        this.server = server;
        this.travel = travel;
        this.clock = clock;
        this.waits = new Cooldowns<>(clock);
        settings(settings);
    }

    @Override
    public void settings(GuildsSettings settings) {
        this.settings = settings;
        this.levels = GuildService.levelsFrom(settings);
        waits.every(Duration.ofSeconds(settings.homeCooldown()));
    }

    private volatile java.util.function.ToIntFunction<Guild> travelPercent = guild -> 100;

    /** What is left of the warm-up and the wait, in percent — the Pathfinder perk. */
    public void travelPercent(java.util.function.ToIntFunction<Guild> percent) {
        this.travelPercent = percent == null ? guild -> 100 : percent;
    }

    public boolean unlocked(Guild guild) {
        return levels.hasHome(levels.levelOf(guild.xp()));
    }

    public int unlockedAt() {
        return levels.homeFromLevel();
    }

    public GuildOutcome set(Player player) {
        Optional<Guild> found = book.guildOf(player.getUniqueId());
        if (found.isEmpty()) {
            return GuildOutcome.NO_GUILD;
        }
        Guild guild = found.get();
        GuildOutcome may = ranks.may(guild, player.getUniqueId(), GuildPermission.SET_HOME);
        if (!may.isSuccess()) {
            return may;
        }
        if (!unlocked(guild)) {
            return GuildOutcome.HOME_LOCKED;
        }
        Location at = player.getLocation();
        HomeSpot spot = new HomeSpot(at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch(),
                self.get());
        GuildOutcome changed = book.update(guild.id(), now -> now.withHome(Optional.of(spot)));
        if (changed.isSuccess()) {
            book.log(GuildEvent.of(guild.id().value(), clock.getAsLong(), GuildEvent.Kind.HOME_SET,
                    player.getUniqueId(), null, 0, spot.describe()));
            notify.members(guild, "guilds.home.set-told", "player", notify.name(player.getUniqueId()), "where", spot.describe());
        }
        return changed;
    }

    /** Sets off. Refusals come back as an outcome; the journey itself is told by the watcher. */
    public GuildOutcome go(Player player) {
        Optional<Guild> found = book.guildOf(player.getUniqueId());
        if (found.isEmpty()) {
            return GuildOutcome.NO_GUILD;
        }
        Guild guild = found.get();
        GuildOutcome may = ranks.may(guild, player.getUniqueId(), GuildPermission.USE_HOME);
        if (!may.isSuccess()) {
            return may;
        }
        if (!unlocked(guild)) {
            return GuildOutcome.HOME_LOCKED;
        }
        if (guild.home().isEmpty()) {
            return GuildOutcome.NO_HOME;
        }
        int share = Math.max(0, Math.min(100, travelPercent.applyAsInt(guild)));
        // The Pathfinder perk shortens the wait: only the part of it that is left beyond the shortened one counts.
        Optional<Duration> left = waits.remaining(player.getUniqueId())
                .map(remaining -> remaining.minus(Duration.ofSeconds(settings.homeCooldown()).multipliedBy(100 - share).dividedBy(100)))
                .filter(remaining -> !remaining.isNegative() && !remaining.isZero());
        if (left.isPresent() && !player.hasPermission("rainsguilds.home.nowait")) {
            notify.tell(player.getUniqueId(), "guilds.home.wait", "time", Times.describe(left.get()));
            return GuildOutcome.SUCCESS;
        }
        HomeSpot home = guild.home().get();
        if (!home.isOn(self.get())) {
            return goThere(player, guild, home);
        }
        World world = server.getWorld(home.world());
        if (world == null) {
            return GuildOutcome.NO_HOME;
        }
        Location destination = new Location(world, home.x(), home.y(), home.z(), home.yaw(), home.pitch());
        Trip trip = Trip.to(guild.name()).after(settings.homeWarmup() * share / 100).bringing(Companions.WHAT_YOU_LEAD);
        travel.go(player, destination, trip, new Arriving(guild));
        return GuildOutcome.SUCCESS;
    }

    /** A home in a world of another server: the player is sent there, to land at it. */
    private GuildOutcome goThere(Player player, Guild guild, HomeSpot home) {
        Optional<NetworkTravel> network = networkTravel.get();
        if (network.isEmpty()) {
            return GuildOutcome.NO_HOME;
        }
        waits.start(player.getUniqueId());
        network.get().send(player, home.server(), Optional.of(new Arrivals.Destination(home.world(), home.x(), home.y(),
                home.z(), home.yaw(), home.pitch())));
        notify.tell(player.getUniqueId(), "guilds.home.arrived", "guild", GuildNotifier.title(guild));
        return GuildOutcome.SUCCESS;
    }

    public void forget(UUID player) {
        waits.forget(player);
    }

    private final class Arriving implements TravelWatcher {

        private final Guild guild;

        private Arriving(Guild guild) {
            this.guild = guild;
        }

        @Override
        public void counting(Player traveller, int secondsLeft, Trip trip) {
            notify.tell(traveller.getUniqueId(), "guilds.home.warming-up", "seconds", secondsLeft);
        }

        @Override
        public void arrived(Player traveller, Location where, Trip trip) {
            waits.start(traveller.getUniqueId());
            notify.tell(traveller.getUniqueId(), "guilds.home.arrived", "guild", GuildNotifier.title(guild));
        }

        @Override
        public void cancelled(Player traveller, TravelReason why, Trip trip) {
            notify.tell(traveller.getUniqueId(), "guilds.home.cancelled", "why", why.name().toLowerCase(Locale.ROOT).replace('_', ' '));
        }

        @Override
        public void refused(Player traveller, TravelReason why, Trip trip) {
            notify.tell(traveller.getUniqueId(), "guilds.home.refused", "why", why.name().toLowerCase(Locale.ROOT).replace('_', ' '));
        }
    }
}
