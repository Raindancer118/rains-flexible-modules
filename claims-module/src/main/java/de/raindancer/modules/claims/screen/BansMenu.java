package de.raindancer.modules.claims.screen;

import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.modules.claims.model.ClaimBan;
import de.raindancer.modules.claims.model.ClaimFeature;
import de.raindancer.core.moderation.punishment.Durations;
import de.raindancer.core.ui.choose.PlayerChooser;
import de.raindancer.core.ui.choose.PlayerEntry;
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
                            "<dark_gray>click to choose who"),
                    click -> askKick());
        }
        toolbar(4, Icons.of(Material.CLOCK, "<gold>Time somebody out",
                        "<gray>Bars them for a while, then lets them back in on its own.",
                        "<dark_gray>click to choose who, then type how long"),
                click -> askTimeout());
        toolbar(7, Icons.of(Material.IRON_AXE, "<red>Bar somebody",
                        "<gray>Kicks them out and keeps them out until you lift it.",
                        "<dark_gray>click to choose who, then type a reason if you like"),
                click -> askBan());
    }

    /** Who a ban or a timeout may not fall on: an owner, for the same reason the claim already refuses it. */
    private List<UUID> unbannable() {
        return new ArrayList<>(claim.owners());
    }

    private void askKick() {
        viewer.closeInventory();
        new PlayerChooser(viewer, services.brand(), this, "Kick somebody out",
                unbannable(), this::onKickChosen).open();
    }

    private void onKickChosen(PlayerEntry person) {
        Player online = services.server().getPlayer(person.id());
        if (online == null) {
            services.messages().send(viewer, "error.player-offline", "player", person.name());
            open();
            return;
        }
        services.eviction().evict(online, claim, "protection.evicted-kicked");
        services.messages().send(viewer, "claim.kicked",
                "player", online.getName(), "claim", claim.name());
        services.broadcasts().kicked(claim, online.getName(), viewer.getName());
        open();
    }

    private void askBan() {
        viewer.closeInventory();
        new PlayerChooser(viewer, services.brand(), this, "Bar somebody",
                unbannable(), this::askBanReason).open();
    }

    private void askBanReason(PlayerEntry person) {
        services.messages().send(viewer, "claim.ask-ban-reason");
        boolean asked = services.prompts().ask(viewer.getUniqueId(), "Claims", PROMPT_TIMEOUT,
                typed -> {
                    String reason = typed.trim().equals("-") ? "" : typed.trim();
                    claim.ban(ClaimBan.permanent(person.id(), viewer.getUniqueId(), reason));
                    services.claimService().saveAsync(claim);
                    services.broadcasts().banned(claim, person.name(), viewer.getName(), reason);
                    services.messages().send(viewer, "claim.banned",
                            "player", person.name(), "claim", claim.name());
                    open();
                },
                this::open);
        if (!asked) {
            services.messages().send(viewer, "selection.already-being-asked");
        }
    }

    private void askTimeout() {
        viewer.closeInventory();
        new PlayerChooser(viewer, services.brand(), this, "Time somebody out",
                unbannable(), this::askTimeoutDuration).open();
    }

    private void askTimeoutDuration(PlayerEntry person) {
        services.messages().send(viewer, "claim.ask-timeout-duration");
        boolean asked = services.prompts().ask(viewer.getUniqueId(), "Claims", PROMPT_TIMEOUT,
                typed -> {
                    String[] parts = typed.trim().split("\\s+", 2);
                    Optional<Duration> duration = Durations.parse(parts[0]);
                    if (duration.isEmpty()) {
                        services.messages().send(viewer, "error.bad-duration", "input", parts[0]);
                        open();
                        return;
                    }
                    String reason = parts.length > 1 && !parts[1].equals("-") ? parts[1] : "";
                    claim.ban(ClaimBan.timeout(person.id(), viewer.getUniqueId(),
                            duration.get().toMillis(), reason));
                    services.claimService().saveAsync(claim);
                    String formatted = Durations.describe(duration.get());
                    services.broadcasts().timedOut(claim, person.name(), viewer.getName(), formatted);
                    services.messages().send(viewer, "claim.timed-out",
                            "player", person.name(), "claim", claim.name(), "duration", formatted);
                    open();
                },
                this::open);
        if (!asked) {
            services.messages().send(viewer, "selection.already-being-asked");
        }
    }
}
