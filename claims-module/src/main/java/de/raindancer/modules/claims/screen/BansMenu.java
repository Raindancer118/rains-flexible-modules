package de.raindancer.modules.claims.screen;

import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.modules.claims.model.ClaimBan;
import de.raindancer.modules.claims.model.ClaimFeature;
import de.raindancer.core.moderation.punishment.Durations;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.modules.claims.model.ClaimBan;
import de.raindancer.modules.claims.ClaimServices;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Who is kept out, and until when.
 *
 * <p>A timeout and a ban are the same thing with and without an end, which is why they are one list rather than
 * two screens — and why the remaining time is on the button instead of in a separate "timeouts" page nobody would
 * think to open.
 */
public final class BansMenu extends PaginatedMenu<ClaimBan> implements IClaimScreen {

    /** How long somebody has to answer before the question is withdrawn. */
    private static final Duration PROMPT_TIMEOUT = Duration.ofSeconds(20);

    private final ClaimServices services;
    private final Claim claim;

    public BansMenu(ClaimServices services, Player viewer, Claim claim, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.claim = claim;
    }

    @Override
    protected Component title() {
        return Component.text("Kept out");
    }

    @Override
    protected List<ClaimBan> entries() {
        return new ArrayList<>(claim.bans().values());
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.LIME_STAINED_GLASS_PANE, "<green>Nobody is barred",
                "<gray>Bar somebody with <white>/claim ban <name></white>.");
    }

    @Override
    protected ItemStack icon(ClaimBan ban) {
        List<String> lore = new ArrayList<>();
        lore.add(ban.permanent()
                ? "<red>Barred for good"
                : "<gold>" + Durations.describe(Duration.ofMillis(ban.remainingMillis())) + " left");
        lore.add("<gray>" + (ban.reason().isBlank() ? "no reason given" : ban.reason()));
        lore.add("");
        lore.add("<dark_gray>click to let them back in");
        return Icons.head(ban.uuid(), "<red>" + services.names().nameOfOwner(ban.uuid()), lore);
    }

    @Override
    protected void onClick(ClaimBan ban, InventoryClickEvent event) {
        if (!services.rights().canManage(claim, viewer, ClaimAdminPermission.MANAGE_BANS)) {
            services.messages().send(viewer, "error.no-claim-permission");
            return;
        }
        claim.unban(ban.uuid());
        services.claimService().saveAsync(claim);
        services.broadcasts().lifted(claim, services.names().nameOfOwner(ban.uuid()),
                viewer.getName());
        services.messages().send(viewer, "claim.ban-lifted",
                "player", services.names().nameOfOwner(ban.uuid()), "claim", claim.name());
        refresh();
    }

    /**
     * The routes this list was missing entirely: reading the ban list was all it ever did, so keeping
     * somebody out — the whole point of the page — had no button on it at all.
     *
     * <p>Kick is its own button rather than folded into the ban flow, because it needs neither a reason nor
     * an expiry and is gone as soon as the player is; showing it only when {@link ClaimFeature#KICK} is
     * offered keeps a server that took the feature away from having a button that does nothing.
     */
    @Override
    protected void render() {
        super.render();
        if (!services.rights().canManage(claim, viewer, ClaimAdminPermission.MANAGE_BANS)) {
            return;
        }
        if (services.features().isOffered(ClaimFeature.KICK)) {
            toolbar(1, Icons.of(Material.LEATHER_BOOTS, "<yellow>Kick somebody out",
                            "<gray>Escorts them to the nearest safe spot beyond the border.",
                            "<dark_gray>they may walk straight back in",
                            "",
                            "<dark_gray>type their name in chat"),
                    click -> askKick());
        }
        toolbar(4, Icons.of(Material.CLOCK, "<gold>Time somebody out",
                        "<gray>Bars them for a while, then lets them back in on its own.",
                        "<dark_gray>type their name, then how long — <white>name duration</white>"),
                click -> askTimeout());
        toolbar(7, Icons.of(Material.IRON_AXE, "<red>Bar somebody",
                        "<gray>Kicks them out and keeps them out until you lift it.",
                        "<dark_gray>type their name, then a reason if you like"),
                click -> askBan());
    }

    private void askKick() {
        viewer.closeInventory();
        services.messages().send(viewer, "claim.ask-kick");
        boolean asked = services.prompts().ask(viewer.getUniqueId(), "Claims", PROMPT_TIMEOUT,
                typed -> {
                    Optional<UUID> subject = resolveName(typed.trim());
                    if (subject.isEmpty()) {
                        services.messages().send(viewer, "error.no-such-player", "player", typed);
                        open();
                        return;
                    }
                    UUID who = subject.get();
                    if (claim.isOwner(who)) {
                        services.messages().send(viewer, "claim.cannot-kick-an-owner", "player", typed);
                        open();
                        return;
                    }
                    Player online = services.server().getPlayer(who);
                    if (online == null) {
                        services.messages().send(viewer, "error.player-offline", "player", typed);
                        open();
                        return;
                    }
                    services.eviction().evict(online, claim, "protection.evicted-kicked");
                    services.messages().send(viewer, "claim.kicked",
                            "player", online.getName(), "claim", claim.name());
                    services.broadcasts().kicked(claim, online.getName(), viewer.getName());
                    open();
                },
                this::open);
        if (!asked) {
            services.messages().send(viewer, "selection.already-being-asked");
        }
    }

    private void askBan() {
        viewer.closeInventory();
        services.messages().send(viewer, "claim.ask-ban");
        boolean asked = services.prompts().ask(viewer.getUniqueId(), "Claims", PROMPT_TIMEOUT,
                typed -> {
                    String[] parts = typed.trim().split("\\s+", 2);
                    Optional<UUID> subject = resolveName(parts[0]);
                    if (subject.isEmpty()) {
                        services.messages().send(viewer, "error.no-such-player", "player", parts[0]);
                        open();
                        return;
                    }
                    UUID who = subject.get();
                    if (claim.isOwner(who)) {
                        services.messages().send(viewer, "claim.cannot-ban-an-owner", "player", parts[0]);
                        open();
                        return;
                    }
                    String reason = parts.length > 1 ? parts[1] : "";
                    claim.ban(ClaimBan.permanent(who, viewer.getUniqueId(), reason));
                    services.claimService().saveAsync(claim);
                    String name = services.names().nameOfOwner(who);
                    services.broadcasts().banned(claim, name, viewer.getName(), reason);
                    services.messages().send(viewer, "claim.banned",
                            "player", name, "claim", claim.name());
                    open();
                },
                this::open);
        if (!asked) {
            services.messages().send(viewer, "selection.already-being-asked");
        }
    }

    private void askTimeout() {
        viewer.closeInventory();
        services.messages().send(viewer, "claim.ask-timeout");
        boolean asked = services.prompts().ask(viewer.getUniqueId(), "Claims", PROMPT_TIMEOUT,
                typed -> {
                    String[] parts = typed.trim().split("\\s+", 3);
                    if (parts.length < 2) {
                        services.messages().send(viewer, "claim.who", "usage", "name duration");
                        open();
                        return;
                    }
                    Optional<UUID> subject = resolveName(parts[0]);
                    if (subject.isEmpty()) {
                        services.messages().send(viewer, "error.no-such-player", "player", parts[0]);
                        open();
                        return;
                    }
                    UUID who = subject.get();
                    if (claim.isOwner(who)) {
                        services.messages().send(viewer, "claim.cannot-ban-an-owner", "player", parts[0]);
                        open();
                        return;
                    }
                    Optional<Duration> duration = Durations.parse(parts[1]);
                    if (duration.isEmpty()) {
                        services.messages().send(viewer, "error.bad-duration", "input", parts[1]);
                        open();
                        return;
                    }
                    String reason = parts.length > 2 ? parts[2] : "";
                    claim.ban(ClaimBan.timeout(who, viewer.getUniqueId(), duration.get().toMillis(), reason));
                    services.claimService().saveAsync(claim);
                    String formatted = Durations.describe(duration.get());
                    String name = services.names().nameOfOwner(who);
                    services.broadcasts().timedOut(claim, name, viewer.getName(), formatted);
                    services.messages().send(viewer, "claim.timed-out",
                            "player", name, "claim", claim.name(), "duration", formatted);
                    open();
                },
                this::open);
        if (!asked) {
            services.messages().send(viewer, "selection.already-being-asked");
        }
    }

    /** A name to a uuid, online or not — offline included, or a ban cannot be laid on somebody who left. */
    private Optional<UUID> resolveName(String name) {
        Player online = services.server().getPlayerExact(name);
        if (online != null) {
            return Optional.of(online.getUniqueId());
        }
        var seen = services.server().getOfflinePlayer(name);
        return seen.hasPlayedBefore() ? Optional.of(seen.getUniqueId()) : Optional.empty();
    }
}
