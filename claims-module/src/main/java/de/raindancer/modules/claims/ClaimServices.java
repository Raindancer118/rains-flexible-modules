package de.raindancer.modules.claims;

import de.raindancer.modules.claims.listener.MovementListener;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.rules.ClaimArea;
import de.raindancer.modules.claims.rules.ClaimLandProvider;
import de.raindancer.modules.claims.rules.ClaimNames;
import de.raindancer.modules.claims.rules.ClaimRights;
import de.raindancer.modules.claims.rules.Features;
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
import de.raindancer.modules.claims.store.ClaimRegistry;
import de.raindancer.modules.claims.store.ClaimStorage;
import de.raindancer.modules.claims.store.ZoneRegistry;
import de.raindancer.modules.claims.visual.BorderVisualizer;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.prompt.ChatPrompts;
import de.raindancer.core.world.protection.FlagRules;
import de.raindancer.core.world.protection.Land;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/**
 * Everything the claims module has built, in one place, so a listener can be handed what it needs.
 *
 * <h2>Why this is not the god object it replaces</h2>
 * The thing before it was the {@code JavaPlugin} subclass: every listener held a reference to the plugin and
 * reached through it for whichever of twenty services it wanted, which meant every listener depended on all of
 * them and none of them could be built without a server.
 *
 * <p>The difference is that this is <em>data</em>. A record of collaborators, constructed once by the module and
 * handed over; a test builds one with fakes in the fields it cares about. Nothing here reaches back into Bukkit,
 * nothing here is static, and a listener that only needs two of these still says so in its own constructor —
 * this is for the handful that genuinely coordinate half the module.
 *
 * @param settings          read through a supplier, not captured: a reload has to change what happens next,
 *                          not what happens after the next restart
 * @param screens  opening a screen, as an interface. Keeps the listeners and services from depending on the
 *                 menus, which is what let the screens be rebuilt without touching any of this
 */
public record ClaimServices(
        Plugin plugin,
        Server server,
        LogChannel log,
        Messages messages,
        de.raindancer.core.ui.chat.Brand brand,
        ChatPrompts prompts,
        Land land,
        FlagRules flags,
        Features features,
        ClaimRegistry claims,
        ClaimStorage storage,
        ZoneRegistry zones,
        ClaimService claimService,
        ClaimNames names,
        ClaimRights rights,
        ClaimLandProvider provider,
        CostService costs,
        SelectionService selections,
        SelectionStick stick,
        SelectionFlow selectionFlow,
        BorderVisualizer visualizer,
        FenceService fences,
        AmbienceService ambience,
        EntryFeeService entryFees,
        EvictionService eviction,
        EquipService equipment,
        BroadcastService broadcasts,
        Supplier<ClaimSettings> settings,
        ClaimScreensOpener screens,
        Supplier<MovementListener> movementTracker) {

    /**
     * The border tracker.
     *
     * <p>Reached through a supplier because it is built <em>after</em> this record — it takes the record as its
     * own argument. A field would have to be null for a moment, and a listener firing in that moment would find
     * it.
     */
    public MovementListener movement() {
        return movementTracker.get();
    }

    /** The settings as they are right now. */
    public ClaimSettings config() {
        return settings.get();
    }

    /** Where a player is considered to be standing, which is the provider's business to smooth out. */
    public java.util.Optional<Claim> claimAround(Player player) {
        return provider.around(player).map(area -> ((ClaimArea) area).claim());
    }
}
