package de.raindancer.modules.economy.service;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.ui.profile.PlayerSwitch;
import de.raindancer.core.ui.scoreboard.ScoreboardPriority;
import de.raindancer.core.ui.scoreboard.Scoreboards;
import de.raindancer.core.ui.scoreboard.Sidebar;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.Account;
import de.raindancer.modules.economy.rules.LeaderboardRule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The sidebar: your balance, your place, and the richest players. Low priority in Core's arbitration, so a
 * minigame's own sidebar wins while it runs and this one comes back after. Each player can hide it.
 */
public final class SidebarService implements IEconomyService {

    public static final PlayerSwitch SHOWN = new PlayerSwitch("rainseconomy", "sidebar", true);
    private static final String OWNER = "economy";
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final RainEconomy economy;
    private final LeaderboardService leaderboard;
    private final Scoreboards scoreboards;
    private final LeaderboardRule rule = new LeaderboardRule();
    private volatile EconomySettings settings;

    public SidebarService(RainEconomy economy, LeaderboardService leaderboard, Scoreboards scoreboards,
                          EconomySettings settings) {
        this.economy = economy;
        this.leaderboard = leaderboard;
        this.scoreboards = scoreboards;
        settings(settings);
    }

    @Override
    public void settings(EconomySettings updated) {
        this.settings = updated == null ? EconomySettings.DEFAULTS : updated;
    }

    /** Redraws everybody's. Core's sidebar is safe from any thread and skips what has not changed. */
    public void refresh(Collection<? extends Player> online) {
        EconomySettings live = settings;
        List<Account> top = rule.top(leaderboard.ranking(), live.sidebarRichest());
        for (Player player : online) {
            if (!live.sidebarEnabled() || !SHOWN.isOn(player)) {
                scoreboards.clear(player.getUniqueId(), OWNER);
                continue;
            }
            scoreboards.show(player.getUniqueId(), OWNER, sidebarFor(player.getUniqueId(), top, live),
                    ScoreboardPriority.LOW);
        }
    }

    private Sidebar sidebarFor(UUID player, List<Account> top, EconomySettings live) {
        Currency currency = live.currency();
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("Balance", NamedTextColor.GRAY));
        lines.add(Component.text(" ").append(currency.render(economy.balance(player))));
        int place = leaderboard.placeOf(player);
        if (place > 0) {
            lines.add(Component.text(" #" + place + " on the server", NamedTextColor.DARK_GRAY));
        }
        if (!top.isEmpty()) {
            lines.add(Component.empty());
            lines.add(Component.text("Richest", NamedTextColor.GRAY));
            for (int i = 0; i < top.size(); i++) {
                Account account = top.get(i);
                lines.add(MINI.deserialize(rule.colourOf(i + 1) + (i + 1) + ". <white>"
                                + MINI.escapeTags(rule.shortName(account.name(), 12)) + " ")
                        .append(currency.renderCompact(account.balance())));
            }
        }
        return Sidebar.of(Component.text().append(currency.renderSymbol()).append(Component.text(" "))
                .append(currency.renderName(true)).build(), lines);
    }

    /** Shows or hides it for one player; answers what it is now. */
    public boolean toggle(Player player) {
        boolean on = SHOWN.toggle(player);
        if (!on) {
            scoreboards.clear(player.getUniqueId(), OWNER);
        }
        return on;
    }

    public void forget(UUID player) {
        scoreboards.clear(player, OWNER);
    }
}
