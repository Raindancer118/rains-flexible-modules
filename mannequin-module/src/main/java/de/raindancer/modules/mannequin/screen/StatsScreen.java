package de.raindancer.modules.mannequin.screen;

import de.raindancer.core.ui.menu.ConfirmMenu;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.mannequin.MannequinServices;
import de.raindancer.modules.mannequin.model.Mannequin;
import de.raindancer.modules.mannequin.model.TrainingSession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * The numbers a mannequin has recorded, with a reset button — in the danger slot, flanked by
 * navigation per {@code ScreenGrammarTest}'s convention, and behind a confirmation because
 * clearing a training log is not undoable.
 */
public final class StatsScreen extends Menu implements IMannequinScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final MannequinServices services;
    private final Mannequin mannequin;

    public StatsScreen(MannequinServices services, Player viewer, Mannequin mannequin, Menu parent) {
        super(viewer, services.brand(), parent, 3);
        this.services = services;
        this.mannequin = mannequin;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Stats — " + mannequin.displayName());
    }

    @Override
    public String breadcrumb() {
        return "Stats";
    }

    @Override
    protected void render() {
        TrainingSession session = services.registry().sessionFor(mannequin.id());

        set(de.raindancer.core.ui.menu.MenuLayout.HEADER_SUBJECT, Icons.of(Material.PAPER,
                "<white>" + mannequin.displayName(),
                "<gray>Total damage: <white>"
                        + String.format(Locale.ROOT, "%.1f", session.totalDamage()),
                "<gray>Hits: <white>" + session.hitCount(),
                "<gray>Current combo: <white>" + session.comboStreak(),
                "<gray>Longest combo: <white>" + session.longestCombo(),
                "<gray>Average damage: <white>"
                        + String.format(Locale.ROOT, "%.2f", session.averageDamage())));

        band(de.raindancer.core.ui.menu.MenuLayout.WHO, 4, Icons.of(Material.GOLDEN_SWORD,
                        "<white>Leaderboard",
                        "<gray>Who has hit this mannequin hardest,",
                        "<gray>and with what.",
                        "",
                        "<gray>Click to open."),
                click -> new LeaderboardScreen(services, viewer, mannequin, this).open());

        danger(Icons.of(Material.TNT, "<red>Reset stats",
                        "<gray>Clears this mannequin's tally and its leaderboard."),
                click -> new ConfirmMenu(viewer, brand(), this,
                        "<red>Reset " + mannequin.displayName() + "'s stats?",
                        List.of("<gray>Total damage, hits and combo history are all cleared."),
                        () -> services.registry().resetSession(mannequin.id())).open());
    }

    @Override
    public String describe() {
        return "a mannequin's combat tally, with a confirmed reset";
    }
}
