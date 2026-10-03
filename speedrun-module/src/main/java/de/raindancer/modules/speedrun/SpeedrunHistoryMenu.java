package de.raindancer.modules.speedrun;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Past runs, newest first — everybody's, or one player's. Each opens its summary. */
public final class SpeedrunHistoryMenu extends PaginatedMenu<SpeedrunRunRecord> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final SpeedrunLobby lobby;
    /** {@code null}: every run. */
    private final UUID whose;
    private final String whoseName;

    public SpeedrunHistoryMenu(SpeedrunLobby lobby, Player viewer, Menu parent, UUID whose, String whoseName) {
        super(viewer, SpeedrunScreens.brandOf(lobby), parent);
        this.lobby = lobby;
        this.whose = whose;
        this.whoseName = whoseName;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>" + (whose == null ? "Past runs" : "Runs of " + whoseName));
    }

    @Override
    public String breadcrumb() {
        return "History";
    }

    @Override
    protected List<SpeedrunRunRecord> entries() {
        SpeedrunHistory history = lobby.toolkit().map(SpeedrunToolkit::history).orElse(null);
        if (history == null) {
            return List.of();
        }
        return whose == null ? history.newestFirst() : history.runsOf(whose);
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.BOOK, "<gray>No runs yet", "<gray>Every run is kept here once it ends.");
    }

    @Override
    protected ItemStack icon(SpeedrunRunRecord run) {
        boolean ranked = run.ranked(lobby.config().rankEditedRuns());
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + run.category().label());
        lore.add("<gray>" + SpeedrunRunSummaryMenu.WHEN.format(Instant.ofEpochMilli(run.startedAt())));
        lore.add("<gray>" + String.join(", ", run.participants().values()));
        lore.add("<gray>" + run.splits().size() + " split(s), " + run.deaths() + " death(s)");
        if (run.resumed()) {
            lore.add("<yellow>Resumed after a restart");
        }
        if (run.clockEdited()) {
            lore.add("<yellow>Clock set by hand");
        }
        lore.add(ranked ? "<green>Ranked" : "<dark_gray>Not ranked");
        lore.add("");
        lore.add("<dark_gray>Click for every split.");
        return Icons.of(run.completed() ? Material.NETHER_STAR : Material.PAPER,
                (run.completed() ? "<gold>" : "<gray>") + SpeedrunTimerDisplay.plain(run.time())
                        + (run.completed() ? "" : " <dark_gray>(" + SpeedrunLobby.friendlyReason(run.outcome()) + ")"),
                lore);
    }

    @Override
    protected void onClick(SpeedrunRunRecord run, InventoryClickEvent event) {
        new SpeedrunRunSummaryMenu(lobby, run, viewer, this).open();
    }
}
