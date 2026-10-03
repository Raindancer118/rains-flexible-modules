package de.raindancer.modules.speedrun;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.menu.PaginatedMenu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * One past run, start to finish: every split with who reached it, every death, every pause and
 * every hand on the clock, in the order it happened. The header says what kind of run it was and
 * whether it is ranked — and if not, why.
 */
public final class SpeedrunRunSummaryMenu extends PaginatedMenu<SpeedrunTimeline.Entry> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final SpeedrunLobby lobby;
    private final SpeedrunRunRecord run;

    public SpeedrunRunSummaryMenu(SpeedrunLobby lobby, SpeedrunRunRecord run, Player viewer, Menu parent) {
        super(viewer, SpeedrunScreens.brandOf(lobby), parent);
        this.lobby = lobby;
        this.run = run;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Run " + SpeedrunTimerDisplay.plain(run.time()));
    }

    @Override
    public String breadcrumb() {
        return "Run";
    }

    @Override
    protected List<SpeedrunTimeline.Entry> entries() {
        return run.timeline();
    }

    @Override
    protected void decorate() {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + run.category().label());
        lore.add("<gray>" + WHEN.format(Instant.ofEpochMilli(run.startedAt())) + " · "
                + run.playerCount() + " racing");
        lore.add("<gray>Seed <white>" + run.seed());
        lore.add("<gray>" + run.deaths() + " death(s), " + run.pauses() + " pause(s)");
        lore.add("");
        boolean ranked = run.ranked(lobby.config().rankEditedRuns());
        if (ranked) {
            lore.add("<green>Ranked.");
        } else if (!run.completed()) {
            lore.add("<yellow>Not ranked: the goal was not reached.");
        } else {
            if (run.resumed()) {
                lore.add("<yellow>Not ranked: resumed after a restart.");
            }
            if (run.clockEdited()) {
                lore.add("<yellow>Not ranked: the clock was set by hand.");
            }
        }
        set(MenuLayout.HEADER_SUBJECT, Icons.of(run.completed() ? Material.NETHER_STAR : Material.BARRIER,
                (run.completed() ? "<gold>" : "<gray>") + SpeedrunTimerDisplay.plain(run.time()), lore));
        set(MenuLayout.HEADER_RIGHT, Icons.of(Material.PLAYER_HEAD, "<white>Who raced",
                run.participants().values().stream().map(name -> "<gray>" + name).toList()));
        super.decorate();
    }

    @Override
    protected ItemStack icon(SpeedrunTimeline.Entry entry) {
        String at = SpeedrunTimerDisplay.plain(entry.at());
        String who = entry.who() == null ? "" : run.nameOf(entry.who());
        return switch (entry.kind()) {
            case SPLIT -> Icons.of(SpeedrunMilestones.builtIn(entry.detail()).map(SpeedrunMilestone::icon)
                            .orElse(Material.PAPER),
                    "<yellow>" + at + " <white>" + run.labelOf(entry.detail()),
                    who.isEmpty() ? List.of() : List.of("<gray>by " + who));
            case DEATH -> Icons.of(Material.SKELETON_SKULL, "<red>" + at + " <white>" + who + " died",
                    entry.detail().isEmpty() ? List.of() : List.of("<gray>" + entry.detail()));
            case PAUSE -> Icons.of(Material.CLOCK, "<gray>" + at + " Paused", "<gray>Everybody was offline.");
            case UNPAUSE -> Icons.of(Material.CLOCK, "<gray>" + at + " Running again");
            case CLOCK_EDIT -> Icons.of(Material.COMPARATOR, "<gold>" + at + " Clock set by hand",
                    "<gray>It read " + entry.detail() + " before.");
            case RESUMED -> Icons.of(Material.RECOVERY_COMPASS, "<gold>" + at + " Resumed",
                    "<gray>Picked up over a world already played in.");
            case JOINED -> Icons.of(Material.LIME_DYE, "<gray>" + at + " " + who + " joined");
            case LEFT -> Icons.of(Material.GRAY_DYE, "<gray>" + at + " " + who + " left the race");
            case FINISH -> Icons.of(Material.NETHER_STAR, "<gold>" + at + " Over",
                    "<gray>" + SpeedrunLobby.friendlyReason(entry.detail()));
        };
    }

    @Override
    protected void onClick(SpeedrunTimeline.Entry entry, InventoryClickEvent event) {
        // a timeline is read, not acted on
    }
}
