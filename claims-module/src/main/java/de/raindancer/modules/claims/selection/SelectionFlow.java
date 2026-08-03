package de.raindancer.modules.claims.selection;

import de.raindancer.modules.claims.ClaimSettings;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.modules.claims.model.ClaimShape;
import de.raindancer.modules.claims.model.NoClaimZone;
import de.raindancer.modules.claims.rules.ClaimRightsRule;
import de.raindancer.modules.claims.service.ClaimService;
import de.raindancer.modules.claims.store.ClaimRegistry;
import de.raindancer.modules.claims.store.ZoneRegistry;
import de.raindancer.modules.claims.visual.BorderVisualizer;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.prompt.ChatPrompts;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * What happens when somebody finishes drawing a shape.
 *
 * <p>Shared by the marking tool, the selection screen and the commands, so all three behave identically — the
 * alternative is three slightly different ideas of when a claim may be created, and the one that is wrong is
 * whichever a player used last.
 *
 * <h2>Explicit collaborators</h2>
 * The version this replaces reached through the plugin for sixteen different services. That is convenient to
 * write and impossible to test: every one of those is a running server. Here they are constructor arguments, so
 * the flow can be exercised without one.
 */
public final class SelectionFlow {

    private final org.bukkit.plugin.Plugin plugin;
    private final Server server;
    private final LogChannel log;
    private final Messages messages;
    private final ChatPrompts prompts;
    private final SelectionService selections;
    private final SelectionStick stick;
    private final ClaimRegistry claims;
    private final ClaimService claimService;
    private final ZoneRegistry zones;
    private final Runnable saveZones;
    private final BorderVisualizer visualizer;
    private final ClaimRightsRule rights;

    /** Read fresh each time rather than captured: a reload must change what happens next, not next restart. */
    private final Supplier<ClaimSettings> settings;

    /** How long somebody has to type a name before the prompt gives up. */
    private static final Duration NAME_PROMPT_TIMEOUT = Duration.ofSeconds(24);

    public SelectionFlow(org.bukkit.plugin.Plugin plugin, Server server, LogChannel log,
                         Messages messages, ChatPrompts prompts,
                         SelectionService selections, SelectionStick stick, ClaimRegistry claims,
                         ClaimService claimService, ZoneRegistry zones, Runnable saveZones,
                         BorderVisualizer visualizer, ClaimRightsRule rights,
                         Supplier<ClaimSettings> settings) {
        this.plugin = plugin;
        this.server = server;
        this.log = log;
        this.messages = messages;
        this.prompts = prompts;
        this.selections = selections;
        this.stick = stick;
        this.claims = claims;
        this.claimService = claimService;
        this.zones = zones;
        this.saveZones = saveZones;
        this.visualizer = visualizer;
        this.rights = rights;
        this.settings = settings;
    }

    /** Completes the selection. False when it was refused and the tool was kept. */
    public boolean finish(Player player) {
        Optional<Selection> maybeSelection = selections.selection(player);
        if (maybeSelection.isEmpty()) {
            messages.send(player, "selection.none");
            return false;
        }
        Selection selection = maybeSelection.get();
        if (!selection.isComplete()) {
            messages.send(player, "selection.incomplete",
                    "needed", selection.mode() == Selection.Mode.RECTANGLE ? "2" : "3",
                    "have", String.valueOf(selection.pointCount()));
            return false;
        }
        World world = server.getWorld(selection.worldId());
        if (world == null) {
            // The world was unloaded between the first corner and the last. Not hypothetical on a server
            // with a farm world that regenerates.
            messages.send(player, "selection.world-gone");
            return false;
        }

        int[] vertical = selections.resolveVerticalRange(selection);
        ClaimShape shape = selection.toShape(vertical[0], vertical[1]);

        return switch (selection.purpose()) {
            case NEW_CLAIM -> finishNewClaim(player, selection, world, shape);
            case RESIZE_CLAIM -> finishResize(player, selection, world, shape);
            case NO_CLAIM_ZONE -> finishZone(player, selection, world, shape);
            case ADMIN_RESHAPE -> finishAdminReshape(player, selection, world, shape);
        };
    }

    private boolean finishNewClaim(Player player, Selection selection, World world, ClaimShape shape) {
        String name = selection.pendingName();
        if (name == null || name.isBlank()) {
            // Asked for rather than invented silently, with a suggestion in the prompt so somebody who does
            // not care can accept it.
            String suggestion = claimService.suggestName(player);
            messages.send(player, "selection.ask-name", "suggestion", suggestion);
            boolean asked = prompts.ask(player.getUniqueId(), "Claims", NAME_PROMPT_TIMEOUT,
                    typed -> onTheirOwnThread(player, () -> {
                        selection.pendingName(typed);
                        finish(player);
                    }),
                    () -> messages.send(player, "selection.name-aborted"));
            if (!asked) {
                // Somebody else already has the next line they type. Saying so beats waiting for an answer
                // that will go to the other plugin.
                messages.send(player, "selection.already-being-asked");
            }
            return false;
        }

        ClaimService.Result result = claimService.create(player, world, shape, name);
        if (!result.success()) {
            reportFailure(player, result);
            // Cleared, so the next attempt asks again instead of retrying a name that was refused.
            selection.pendingName(null);
            return false;
        }

        Claim claim = result.claim();
        messages.send(player, "claim.created",
                "claim", claim.name(),
                "area", String.valueOf(shape.areaBlocks()),
                "min-y", String.valueOf(shape.minY()),
                "max-y", String.valueOf(shape.maxY()));
        cleanUp(player);
        visualizer.showClaim(player, claim, settings.get().visualDurationSeconds());
        return true;
    }

    private boolean finishResize(Player player, Selection selection, World world, ClaimShape shape) {
        Optional<Claim> maybeClaim = claims.byId(selection.targetClaimId());
        if (maybeClaim.isEmpty()) {
            messages.send(player, "claim.gone");
            cleanUp(player);
            return false;
        }
        Claim claim = maybeClaim.get();
        if (!rights.canManage(claim, player, ClaimAdminPermission.MANAGE_SHAPE)) {
            messages.send(player, "error.no-claim-permission");
            return false;
        }
        if (!claim.worldId().equals(world.getUID())) {
            messages.send(player, "selection.wrong-world", "claim", claim.name());
            return false;
        }

        ClaimService.Result result = claimService.resize(player, claim, world, shape);
        if (!result.success()) {
            reportFailure(player, result);
            return false;
        }
        messages.send(player, "claim.resized",
                "claim", claim.name(),
                "area", String.valueOf(shape.areaBlocks()),
                "min-y", String.valueOf(shape.minY()),
                "max-y", String.valueOf(shape.maxY()));
        reportSettlement(player, claim, result.detail());
        cleanUp(player);
        visualizer.showClaim(player, claim, settings.get().visualDurationSeconds());
        return true;
    }

    /**
     * An admin forcing somebody else's claim into a new shape.
     *
     * <p>Goes through {@code ClaimService.adminReshape} rather than {@link #finishResize}: the ordinary path
     * runs a claim's full validation, and an admin reaching for the stick on a claim they do not own is usually
     * doing so <em>because</em> the ordinary rules refuse what needs to happen — a claim grown past the current
     * size limit that has to shrink, one drawn into ground that is now a no-claim zone. Only the overlap check
     * still applies, and only when the server insists claims never touch.
     */
    private boolean finishAdminReshape(Player player, Selection selection, World world, ClaimShape shape) {
        Optional<Claim> maybeClaim = claims.byId(selection.targetClaimId());
        if (maybeClaim.isEmpty()) {
            messages.send(player, "claim.gone");
            cleanUp(player);
            return false;
        }
        if (!rights.isServerAdmin(player)) {
            // Not reachable through the normal command surface, which gates this itself — but the stick can
            // outlive a permission change, so the check belongs here too.
            messages.send(player, "error.no-permission");
            cleanUp(player);
            return false;
        }
        Claim claim = maybeClaim.get();
        if (!claim.worldId().equals(world.getUID())) {
            messages.send(player, "selection.wrong-world", "claim", claim.name());
            return false;
        }

        Optional<ClaimService.Failure> failure = claimService.adminReshape(claim, shape);
        if (failure.isPresent()) {
            messages.send(player, "error.overlaps-claim", "detail", "");
            return false;
        }
        messages.send(player, "admin.claim-reshaped",
                "claim", claim.name(),
                "area", String.valueOf(shape.areaBlocks()),
                "min-y", String.valueOf(shape.minY()),
                "max-y", String.valueOf(shape.maxY()));
        cleanUp(player);
        visualizer.showClaim(player, claim, settings.get().visualDurationSeconds());
        return true;
    }

    private boolean finishZone(Player player, Selection selection, World world, ClaimShape shape) {
        if (!rights.isServerAdmin(player)) {
            messages.send(player, "error.no-permission");
            return false;
        }
        String name = selection.targetZoneName();
        if (name == null || name.isBlank()) {
            messages.send(player, "zone.ask-name");
            if (!prompts.ask(player.getUniqueId(), "Claims", NAME_PROMPT_TIMEOUT,
                    typed -> onTheirOwnThread(player, () -> {
                        selection.targetZoneName(typed);
                        finish(player);
                    }),
                    () -> messages.send(player, "zone.name-aborted"))) {
                messages.send(player, "selection.already-being-asked");
            }
            return false;
        }
        if (zones.byName(name).isPresent()) {
            zones.remove(name);
            messages.send(player, "zone.replaced", "zone", name);
        }
        zones.add(new NoClaimZone(name, world.getUID(), world.getName(), shape,
                System.currentTimeMillis()));
        saveZones.run();
        messages.send(player, "zone.created",
                "zone", name,
                "area", String.valueOf(shape.areaBlocks()),
                "min-y", String.valueOf(shape.minY()),
                "max-y", String.valueOf(shape.maxY()));
        cleanUp(player);
        zones.byName(name).ifPresent(zone ->
                visualizer.showZone(player, zone, settings.get().visualDurationSeconds()));
        return true;
    }

    /**
     * Tells the player how the price difference was settled.
     *
     * <p>A refund goes into the claim's bank rather than straight into the inventory, so nothing is lost when
     * the inventory is full or the owner resized from a menu while standing somewhere awkward.
     */
    private void reportSettlement(Player player, Claim claim, String detail) {
        if (detail == null || detail.isBlank()) {
            return;
        }
        String[] parts = detail.split(":", 2);
        if (parts.length != 2) {
            return;
        }
        String amount = parts[1];
        switch (parts[0]) {
            case "refunded" -> messages.send(player, "claim.resize-refunded",
                    "amount", amount, "claim", claim.name(),
                    "type", claim.paidCostType().displayName());
            case "charged" -> messages.send(player, "claim.resize-charged",
                    "amount", amount, "claim", claim.name(),
                    "type", claim.paidCostType().displayName());
            default -> {
                // Some other settlement wording. Nothing to say about it.
            }
        }
        if (settings.get().debug()) {
            log.debug("Resize settlement for {}: {}", claim.name(), detail);
        }
    }

    /**
     * Runs something on the thread that owns this player.
     *
     * <p>A chat prompt's answer arrives on Paper's async chat thread — {@code AsyncChatEvent} is asynchronous,
     * which is the whole point of its name. Everything finishing a claim does from there is main-thread work:
     * taking the price out of an inventory, setting experience levels, taking the marking tool back, starting
     * the border task. Doing it on the chat thread is an inventory corrupted on Paper and a hard thread-check
     * failure on Folia.
     *
     * <p>Found by review rather than by a crash, which is the ordinary way with these: it works perfectly until
     * two people name a claim at the same moment.
     */
    private void onTheirOwnThread(Player player, Runnable work) {
        de.raindancer.core.platform.util.Scheduling.entity(plugin, player, work);
    }

    /** Takes the tool back and forgets the selection — the "it vanishes when you are done" behaviour. */
    public void cleanUp(Player player) {
        stick.revoke(player);
        selections.clear(player);
        // The corner glow lives exactly as long as the selection does.
        visualizer.clearMarkers(player);
    }

    public void cancel(Player player) {
        boolean had = selections.hasSelection(player);
        cleanUp(player);
        visualizer.stop(player);
        messages.send(player, had ? "selection.cancelled" : "selection.none");
    }

    /** Says why a claim could not be made, in the player's own terms rather than as an enum name. */
    public void reportFailure(Player player, ClaimService.Result result) {
        String key = switch (result.failure()) {
            case WORLD_DISABLED -> "error.world-disabled";
            case SELECTION_INCOMPLETE -> "selection.incomplete";
            case NAME_INVALID -> "error.name-invalid";
            case NAME_TAKEN -> "error.name-taken";
            case TOO_MANY_CLAIMS -> "error.too-many-claims";
            case TOO_SMALL -> "error.claim-too-small";
            case TOO_LARGE -> "error.claim-too-large";
            case TOO_MANY_VERTICES -> "error.too-many-vertices";
            case OVERLAPS_CLAIM -> "error.overlaps-claim";
            case IN_NO_CLAIM_ZONE -> "error.in-no-claim-zone";
            case CANNOT_AFFORD -> "error.cannot-afford";
            case UNDERGROUND_DISALLOWED -> "error.underground-disallowed";
            // A rule the module did not write. Its own key is the honest thing to show, and the
            // detail carries whatever it wanted to say.
            case OTHER -> "error.generic";
            case null -> "error.generic";
        };
        messages.send(player, key, "detail", result.detail() == null ? "" : result.detail());
    }

    /** Starts a fresh selection and hands out the tool. */
    public void begin(Player player, Selection.Mode mode, Selection.Purpose purpose, String pendingName,
                      Claim resizeTarget, String zoneName) {
        Selection selection = selections.begin(player, mode, purpose);
        selection.pendingName(pendingName);
        selection.targetClaimId(resizeTarget == null ? null : resizeTarget.id());
        selection.targetZoneName(zoneName);
        // Revoked first: somebody starting a second selection with the tool already in hand would otherwise
        // end up with two of them and no way to tell which is which.
        stick.revoke(player);
        stick.give(player, purpose, mode);
        messages.send(player, "selection.started",
                "mode", mode == Selection.Mode.RECTANGLE ? "rectangle" : "polygon");
    }
}
