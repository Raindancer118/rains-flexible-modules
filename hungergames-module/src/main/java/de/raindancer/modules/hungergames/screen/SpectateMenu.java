package de.raindancer.modules.hungergames.screen;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.hungergames.store.GameSession;
import de.raindancer.modules.hungergames.service.SpectatorService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * Where a spectator can go: the living, online tributes — and nowhere else.
 *
 * <h2>Why an eliminated or offline tribute never appears here</h2>
 * {@link SpectatorService#teleportTo} already refuses either case, so a button for one would be a click
 * that silently does nothing — worse than not offering it. Filtering the list to exactly the tributes a
 * click here would actually reach is {@link #onlineLivingTributes}, pulled out on its own so it can be
 * tested without a server: given a session and a predicate for "is this UUID online", which UUIDs come
 * back is ordinary set arithmetic.
 *
 * <h2>Why this has no parent</h2>
 * One of the module's five entry points ({@code IHungerGamesScreensOpener#spectate}) — a spectator's own
 * compass opens this directly, not by clicking through the admin suite. {@code ScreenGrammarTest} lists it
 * among the pages that are allowed to skip a parent for exactly that reason.
 */
public final class SpectateMenu extends PaginatedMenu<UUID> implements IHungerGamesScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final GameSession session;
    private final SpectatorService spectator;

    public SpectateMenu(Player viewer, Brand brand, GameSession session, SpectatorService spectator) {
        super(viewer, brand, null);
        this.session = session;
        this.spectator = spectator;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<green>Living tributes");
    }

    @Override
    public String breadcrumb() {
        return "Spectate";
    }

    @Override
    protected List<UUID> entries() {
        return onlineLivingTributes(session, uuid -> Bukkit.getPlayer(uuid) != null);
    }

    /**
     * Every tribute a teleport here would actually reach: alive, in {@code session}, and passing
     * {@code isOnline}. Pure given both, and the reason a test can check this filtering without a server.
     */
    public static List<UUID> onlineLivingTributes(GameSession session, java.util.function.Predicate<UUID> isOnline) {
        return session.participants().alive().stream().filter(isOnline).sorted().toList();
    }

    @Override
    protected ItemStack icon(UUID target) {
        Player online = Bukkit.getPlayer(target);
        String name = online != null ? online.getName()
                : session.participants().nameOf(target).orElse(target.toString());
        String team = session.teams().teamOf(target).map(t -> t.name()).orElse("No team");
        return Icons.head(target, "<green>" + name, "<gray>" + team, "<yellow>Click: teleport to them");
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.BARRIER, "<gray>Nobody to watch",
                "<gray>No living tribute is online right now.");
    }

    @Override
    protected void onClick(UUID target, InventoryClickEvent event) {
        if (spectator.teleportTo(viewer, target)) {
            viewer.closeInventory();
        } else {
            // Refused: the tribute stopped being a valid target between the render and this click (they
            // were eliminated, or left) — said aloud rather than doing nothing, and the page is refreshed
            // so the stale button is gone.
            refresh();
        }
    }

    @Override
    public String describe() {
        return "where a spectator may go: the living tributes who are actually reachable";
    }
}
