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
        lore.add("<gray>" + SpeedrunScreens.text(run.category().label()));
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
                run.participants().values().stream().map(name -> "<gray>" + SpeedrunScreens.text(name)).toList()));
        super.decorate();
    }

    @Override
    protected ItemStack icon(SpeedrunTimeline.Entry entry) {
        List<String> lines = lines(run, entry);
        Material icon = switch (entry.kind()) {
            case SPLIT -> SpeedrunMilestones.builtIn(entry.detail()).map(SpeedrunMilestone::icon).orElse(Material.PAPER);
            case DEATH -> Material.SKELETON_SKULL;
            case PAUSE, UNPAUSE -> Material.CLOCK;
            case CLOCK_EDIT -> Material.COMPARATOR;
            case RESUMED -> Material.RECOVERY_COMPASS;
            case JOINED -> Material.LIME_DYE;
            case LEFT -> Material.GRAY_DYE;
            case FINISH -> Material.NETHER_STAR;
            case CAUGHT, CAUGHT_AWAY -> Material.IRON_BARS;
            case LIFE_LOST -> Material.RED_DYE;
            case HUNTER_DIED -> Material.IRON_SWORD;
            case SIDE_CHANGED -> Material.LIME_BANNER;
        };
        return Icons.of(icon, lines.getFirst(), lines.subList(1, lines.size()));
    }

    /**
     * One timeline entry as MiniMessage: its title first, then its lore. Everything recorded — a
     * name, a death message with a renamed item in it, a mode's milestone label — goes in as text.
     */
    static List<String> lines(SpeedrunRunRecord run, SpeedrunTimeline.Entry entry) {
        String at = SpeedrunTimerDisplay.plain(entry.at());
        String who = entry.who() == null ? "" : SpeedrunScreens.text(run.nameOf(entry.who()));
        String detail = SpeedrunScreens.text(entry.detail());
        String by = entry.other() == null ? "" : SpeedrunScreens.text(run.nameOf(entry.other()));
        return switch (entry.kind()) {
            case SPLIT -> who.isEmpty()
                    ? List.of("<yellow>" + at + " <white>" + SpeedrunScreens.text(run.labelOf(entry.detail())))
                    : List.of("<yellow>" + at + " <white>" + SpeedrunScreens.text(run.labelOf(entry.detail())),
                            "<gray>by " + who);
            case DEATH -> entry.detail().isEmpty() ? List.of("<red>" + at + " <white>" + who + " died")
                    : List.of("<red>" + at + " <white>" + who + " died", "<gray>" + detail);
            case PAUSE -> List.of("<gray>" + at + " Paused", "<gray>Everybody was offline.");
            case UNPAUSE -> List.of("<gray>" + at + " Running again");
            case CLOCK_EDIT -> List.of("<gold>" + at + " Clock set by hand", "<gray>It read " + detail + " before.");
            case RESUMED -> List.of("<gold>" + at + " Resumed", "<gray>Picked up over a world already played in.");
            case JOINED -> List.of("<gray>" + at + " " + who + " joined");
            case LEFT -> List.of("<gray>" + at + " " + who + " left the race");
            case FINISH -> List.of("<gold>" + at + " Over",
                    "<gray>" + SpeedrunScreens.text(SpeedrunLobby.friendlyReason(entry.detail())));
            case CAUGHT -> by.isEmpty() ? List.of("<red>" + at + " <white>" + who + " was caught")
                    : List.of("<red>" + at + " <white>" + who + " was caught", "<gray>by " + by);
            case CAUGHT_AWAY -> List.of("<red>" + at + " <white>" + who + " was away too long",
                    "<gray>Counted as caught.");
            case LIFE_LOST -> List.of("<gold>" + at + " <white>" + who + " lost a life",
                    "<gray>" + detail + " left" + (by.isEmpty() ? "" : ", taken by " + by));
            case HUNTER_DIED -> by.isEmpty() ? List.of("<gray>" + at + " <white>" + who + " (hunting) died")
                    : List.of("<gray>" + at + " <white>" + who + " (hunting) died", "<gray>to " + by);
            case SIDE_CHANGED -> List.of("<gray>" + at + " <white>" + who + " now " + detail);
        };
    }

    @Override
    protected void onClick(SpeedrunTimeline.Entry entry, InventoryClickEvent event) {
        // a timeline is read, not acted on
    }
}
