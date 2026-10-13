package de.raindancer.modules.oneblock.service;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.profile.PlayerSwitch;
import de.raindancer.core.ui.scoreboard.ScoreboardPriority;
import de.raindancer.core.ui.scoreboard.Scoreboards;
import de.raindancer.core.ui.scoreboard.Sidebar;
import de.raindancer.core.ui.text.Markup;
import de.raindancer.modules.oneblock.OneBlockSettings;
import de.raindancer.modules.oneblock.model.IslandView;
import de.raindancer.modules.oneblock.model.Progress;
import de.raindancer.modules.oneblock.rules.RankNameRule;
import de.raindancer.modules.oneblock.util.ProgressBar;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntFunction;

/**
 * The island server's sidebar: the island you are on, its phase and how far to the next, the team. Low in
 * Core's arbitration, so a game's own sidebar wins while it runs. Every line is a message key; each player can hide it.
 */
public final class SidebarService implements IOneBlockService {

    public static final PlayerSwitch SHOWN = new PlayerSwitch("rainsoneblock", "sidebar", true);
    private static final String OWNER = "oneblock";
    private static final int BAR_CELLS = 10;

    private final Messages messages;
    private final Scoreboards scoreboards;
    private final RankNameRule ranks = new RankNameRule();

    public SidebarService(Messages messages, Scoreboards scoreboards) {
        this.messages = messages;
        this.scoreboards = scoreboards;
    }

    /** Redraws everybody's; server thread, for BentoBox. Core skips what has not changed. */
    public void refresh(Iterable<? extends org.bukkit.entity.Player> online, IslandHost host) {
        for (org.bukkit.entity.Player player : online) {
            UUID id = player.getUniqueId();
            if (!SHOWN.isOn(player)) {
                clear(id);
                continue;
            }
            show(id, draw(host.view(id), room -> de.raindancer.core.ui.scoreboard.SidebarSections.linesFor(id, room)));
        }
    }


    public void forget(UUID player) {
        clear(player);
    }

    /** Nothing in the settings decides what the sidebar says. */
    @Override
    public void settings(OneBlockSettings settings) {
    }

    public void show(UUID player, Sidebar sidebar) {
        scoreboards.show(player, OWNER, sidebar, ScoreboardPriority.LOW);
    }

    public void clear(UUID player) {
        scoreboards.clear(player, OWNER);
    }

    /** @param sections other plugins' lines for the room left, added only when they fit whole */
    public Sidebar draw(Optional<IslandView> island, IntFunction<List<Component>> sections) {
        List<Component> lines = new ArrayList<>();
        lines.add(line("oneblock.sidebar.island"));
        if (island.isEmpty()) {
            lines.add(line("oneblock.sidebar.no-island"));
            lines.add(line("oneblock.sidebar.no-island-hint"));
        } else {
            IslandView view = island.get();
            lines.add(whose(view));
            lines.add(line("oneblock.sidebar.you", "rank", messages.raw(ranks.keyFor(view.rank()))));
            view.progress().ifPresent(progress -> phase(lines, progress));
            lines.add(Component.empty());
            lines.add(view.mostMembers() < 0
                    ? line("oneblock.sidebar.team-unlimited", "members", String.valueOf(view.members()),
                    "online", String.valueOf(view.online()))
                    : line("oneblock.sidebar.team", "members", String.valueOf(view.members()),
                    "most", String.valueOf(view.mostMembers()), "online", String.valueOf(view.online())));
        }
        List<Component> more = sections.apply(Sidebar.MAX_LINES - lines.size());
        if (lines.size() + more.size() <= Sidebar.MAX_LINES) {
            lines.addAll(more);
        }
        return Sidebar.of(messages.get("oneblock.sidebar.title"), lines);
    }

    private Component whose(IslandView view) {
        if (view.yours()) {
            return line(view.standingOn() ? "oneblock.sidebar.yours" : "oneblock.sidebar.yours-away");
        }
        return view.owner() == null ? line("oneblock.sidebar.unowned")
                : line("oneblock.sidebar.theirs", "player", view.owner());
    }

    private void phase(List<Component> lines, Progress progress) {
        lines.add(Component.empty());
        lines.add(line("oneblock.sidebar.phase"));
        lines.add(line("oneblock.sidebar.phase-name", "phase", progress.phase()));
        lines.add(line("oneblock.sidebar.progress", "bar", Markup.of(ProgressBar.mini(progress.percent(), BAR_CELLS)),
                "percent", String.valueOf(Math.clamp(progress.percent(), 0, 100))));
        lines.add(progress.next().isBlank() ? line("oneblock.sidebar.last-phase")
                : line("oneblock.sidebar.next", "phase", progress.next(),
                "blocks", String.valueOf(progress.toNext())));
        lines.add(line("oneblock.sidebar.blocks", "blocks", String.format("%,d", progress.blocks())));
    }

    private Component line(String key, Object... values) {
        return messages.get(key, values);
    }
}
