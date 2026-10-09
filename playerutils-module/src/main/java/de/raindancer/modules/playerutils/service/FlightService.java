package de.raindancer.modules.playerutils.service;

import de.raindancer.core.world.movement.Footing;
import de.raindancer.core.RainsCore;
import de.raindancer.core.moderation.players.Outcome;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.playerutils.PlayerUtilsSettings;
import de.raindancer.modules.playerutils.rules.FlightRule;
import de.raindancer.modules.playerutils.store.FlightMarks;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Flight that is <em>given</em> rather than toggled for the moment: it outlasts a relog, a death, a world
 * change and a gamemode change, because the game drops {@code allowFlight} on every one of them and a player
 * who was given flight should not have to ask again each time. Taken away mid-air, the landing is soft.
 */
public final class FlightService implements IPlayerUtilsService {

    /** How long a soft landing is held open for somebody who never lands — a minute of falling is plenty. */
    private static final long SOFT_LANDING_TICKS = 20L * 60;

    public record Change(Outcome outcome, boolean nowOn) {
    }

    private final Plugin plugin;
    private final RainsCore core;
    private final FlightRule rule;
    private final Set<UUID> landing = ConcurrentHashMap.newKeySet();
    private volatile PlayerUtilsSettings settings;

    public FlightService(Plugin plugin, RainsCore core, FlightRule rule, PlayerUtilsSettings settings) {
        this.plugin = plugin;
        this.core = core;
        this.rule = rule;
        this.settings = settings;
    }

    @Override
    public void settings(PlayerUtilsSettings fresh) {
        this.settings = fresh;
    }

    public Change set(Player target, String state) {
        boolean now = target.getAllowFlight();
        boolean wanted = rule.wanted(state, now);
        boolean persist = settings.flightPersists();
        Scheduling.onOwner(plugin, target, () -> FlightMarks.grant(target, wanted && persist));
        if (wanted == now) {
            return new Change(Outcome.NOTHING_TO_DO, now);
        }
        if (!wanted && settings.softLanding() && !Footing.grounded(target)) {
            landing.add(target.getUniqueId());
            Scheduling.entityLater(plugin, target, SOFT_LANDING_TICKS, () -> landing.remove(target.getUniqueId()));
        }
        Outcome outcome = core.players().flight(target.getUniqueId(), wanted);
        return new Change(outcome, wanted);
    }

    /** Puts given flight back after the game took it. On the player's own thread. */
    public boolean restore(Player player) {
        if (!settings.flightPersists()) {
            return false;
        }
        if (!rule.shouldRestore(FlightMarks.isGranted(player), player.getGameMode().name(), player.getAllowFlight())) {
            return false;
        }
        player.setAllowFlight(true);
        return true;
    }

    /** Whether this fall is the soft landing after flight was taken away — and uses it up if so. */
    public boolean landsSoftly(UUID player) {
        return landing.remove(player);
    }

    public boolean isGranted(Player player) {
        return FlightMarks.isGranted(player);
    }

    public void forget(UUID player) {
        landing.remove(player);
    }

    @Override
    public String describe() {
        return "flight that is given, kept, and landed softly";
    }
}
