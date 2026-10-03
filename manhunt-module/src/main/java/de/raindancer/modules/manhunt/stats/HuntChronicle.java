package de.raindancer.modules.manhunt.stats;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.hud.Announcer;
import de.raindancer.modules.manhunt.hud.HuntTicker;
import de.raindancer.modules.manhunt.model.Hunt;
import de.raindancer.modules.manhunt.service.HuntDeathListener;
import de.raindancer.modules.manhunt.service.HuntWatcher;
import de.raindancer.modules.manhunt.service.HunterHoldListener;
import de.raindancer.modules.speedrun.SpeedrunOutcome;
import de.raindancer.modules.speedrun.SpeedrunRun;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Keeps a hunt's record and its HUD: a {@link HuntLog} and a {@link HuntTicker} for each hunt, and at
 * the end the stats, the history and the summary in chat.
 *
 * <p>Never lets its own trouble reach the hunt: every callback swallows and logs what it throws,
 * because the hunt's own ending — compasses back, spectators stood up — runs right after it.
 */
public final class HuntChronicle implements HuntWatcher {

    private static final LogChannel log = Log.of("manhunt");

    /** Builds a hunt's ticker — the module's real one, or a test's. */
    @FunctionalInterface
    public interface Tickers {
        HuntTicker create(Hunt hunt, HuntLog record, SpeedrunRun run, HunterHoldListener hold, int headStartSeconds);
    }

    /** What a hunt is listened to with — the module's {@link HuntRecorder}, or nothing in a test. */
    @FunctionalInterface
    public interface Recorders {
        void listen(Hunt hunt, SpeedrunRun run, HuntChronicle chronicle);
    }

    private final Plugin plugin;
    private final Supplier<ManhuntSettings> settings;
    private final StatsStore stats;
    private final HistoryStore history;
    private final Messages messages;
    private final ChatButtons buttons;
    private final Announcer announcer;
    private final LongSupplier clock;
    private final Tickers tickers;
    private final Recorders recorders;
    private final AtomicReference<HuntLog> current = new AtomicReference<>();
    private final AtomicReference<Hunt> hunt = new AtomicReference<>();
    private final AtomicReference<HuntTicker> ticker = new AtomicReference<>();

    public HuntChronicle(Plugin plugin, Supplier<ManhuntSettings> settings, StatsStore stats, HistoryStore history,
                         Messages messages, ChatButtons buttons, Announcer announcer, LongSupplier clock,
                         Tickers tickers, Recorders recorders) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.stats = Objects.requireNonNull(stats, "stats");
        this.history = Objects.requireNonNull(history, "history");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.buttons = buttons;
        this.announcer = announcer;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tickers = tickers;
        this.recorders = recorders;
    }

    public StatsStore stats() {
        return stats;
    }

    public HistoryStore history() {
        return history;
    }

    /** The running hunt's log, for its timeline page. */
    public Optional<HuntLog> currentLog() {
        return Optional.ofNullable(current.get());
    }

    @Override
    public void started(Hunt started, SpeedrunRun run, HunterHoldListener hold, int headStartSeconds) {
        safely("starting the record", () -> {
            HuntLog record = new HuntLog(clock, names(started.runners()), names(started.hunters()));
            current.set(record);
            hunt.set(started);
            if (recorders != null) {
                recorders.listen(started, run, this);
            }
            if (tickers != null) {
                HuntTicker beat = tickers.create(started, record, run, hold, headStartSeconds);
                ticker.set(beat);
                beat.start();
            }
        });
    }

    /** A milestone the recorder saw; announced only the first time. */
    public void reached(Milestone milestone, Player who) {
        HuntLog record = current.get();
        Hunt playing = hunt.get();
        if (record == null || playing == null) {
            return;
        }
        String name = who == null ? null : who.getName();
        if (record.milestone(milestone, who == null ? null : who.getUniqueId(), name) && announcer != null) {
            safely("announcing a milestone",
                    () -> announcer.milestone(playing, milestone, name, record.milestoneAt(milestone).orElse(0L)));
        }
    }

    public void portal(UUID who) {
        HuntLog record = current.get();
        if (record != null) {
            record.portal(who);
        }
    }

    @Override
    public void died(Hunt dying, UUID who, String name, UUID by, String byName, Death death, int livesLeft) {
        HuntLog record = current.get();
        if (record == null) {
            return;
        }
        switch (death) {
            case CAUGHT -> record.caught(who, name, by, byName);
            case LIFE_LOST -> record.lifeLost(who, name, by, byName, livesLeft);
            case HUNTER_DIED -> record.hunterDied(who, name, by, byName);
        }
    }

    @Override
    public void caughtAway(Hunt from, UUID who, String name) {
        HuntLog record = current.get();
        if (record != null) {
            record.caughtAway(who, name);
        }
    }

    @Override
    public void left(Hunt from, UUID who) {
        HuntLog record = current.get();
        if (record != null) {
            record.left(who, nameOf(who));
        }
    }

    @Override
    public void sideChanged(Hunt in, UUID who, boolean nowRunner, boolean latecomer) {
        HuntLog record = current.get();
        if (record == null) {
            return;
        }
        if (latecomer) {
            record.joined(who, nameOf(who), nowRunner);
        } else {
            record.sideChanged(who, nameOf(who), nowRunner);
        }
    }

    @Override
    public void ended(Hunt over, Optional<SpeedrunOutcome> outcome) {
        HuntTicker beat = ticker.getAndSet(null);
        HuntLog record = current.getAndSet(null);
        hunt.set(null);
        safely("stopping the HUD", () -> {
            if (beat != null) {
                beat.stop();
            }
        });
        if (record == null) {
            return;
        }
        safely("keeping the record", () -> {
            String reason = outcome.map(SpeedrunOutcome::reason).orElse("abandoned");
            HuntRecord finished = record.finish(history.nextNumber(), reason, winnerOf(reason));
            ManhuntSettings config = settings.get();
            Runnable save = () -> {
                if (config.statsEnabled()) {
                    stats.record(finished);
                }
                history.add(finished, config.historyKeptClamped());
            };
            if (plugin.isEnabled()) {
                Scheduling.async(plugin, () -> safely("saving the record", save));
            } else {
                save.run();
            }
            if (config.summaryInChat() && outcome.isPresent()) {
                tellSummary(over, HuntSummary.of(finished), config.historyKeptClamped() > 0);
            }
        });
    }

    /** Who won, by what ended it. */
    public static HuntRecord.Winner winnerOf(String reason) {
        if (HuntDeathListener.HUNTERS_WIN.equals(reason)) {
            return HuntRecord.Winner.HUNTERS;
        }
        return reason != null && reason.startsWith("advancement:") ? HuntRecord.Winner.RUNNERS : HuntRecord.Winner.NOBODY;
    }

    /** The summary as chat lines, for the end of a hunt and {@code /manhunt summary}. */
    public List<Component> summaryLines(HuntSummary summary) {
        HuntRecord record = summary.record();
        List<Component> lines = new ArrayList<>();
        lines.add(messages.prefixed("manhunt.summary.header-" + record.winner().name().toLowerCase(java.util.Locale.ROOT),
                "number", String.valueOf(record.number()), "time", HuntSummary.clock(record.durationMillis())));
        for (HuntSummary.Catch caught : summary.catches()) {
            lines.add(caught.byName() == null
                    ? messages.get("manhunt.summary.catch-away", "time", HuntSummary.clock(caught.atMillis()),
                            "runner", caught.runnerName())
                    : messages.get("manhunt.summary.catch", "time", HuntSummary.clock(caught.atMillis()),
                            "runner", caught.runnerName(), "by", caught.byName()));
        }
        for (HuntSummary.Split split : summary.splits()) {
            lines.add(messages.get("manhunt.summary.split", "time", HuntSummary.clock(split.atMillis()),
                    "milestone", messages.raw("manhunt.milestone-name." + split.milestone().id()),
                    "who", split.whoName() == null ? "—" : split.whoName()));
        }
        summary.hunterMvp().ifPresent(mvp -> lines.add(messages.get("manhunt.summary.mvp-hunter",
                "name", mvp.name(), "catches", String.valueOf(mvp.catches()))));
        summary.runnerMvp().ifPresent(mvp -> lines.add(messages.get("manhunt.summary.mvp-runner",
                "name", mvp.name(), "time", HuntSummary.clock(mvp.survivedMillis()))));
        summary.explorer().ifPresent(mvp -> lines.add(messages.get("manhunt.summary.explorer",
                "name", mvp.name(), "blocks", String.valueOf(Math.round(mvp.distance())))));
        return lines;
    }

    private void tellSummary(Hunt over, HuntSummary summary, boolean kept) {
        List<Component> lines = summaryLines(summary);
        for (UUID id : over.everybody()) {
            Player player = plugin.getServer().getPlayer(id);
            if (player == null) {
                continue;
            }
            lines.forEach(player::sendMessage);
            if (kept && buttons != null) {
                player.sendMessage(messages.get("manhunt.summary.more").append(Component.space())
                        .append(buttons.label(messages.raw("manhunt.summary.open-button"))
                                .tooltip(messages.raw("manhunt.summary.open-tooltip"))
                                .runs("/manhunt summary " + summary.record().number()).render()));
            }
        }
    }

    private Map<UUID, String> names(java.util.Set<UUID> ids) {
        Map<UUID, String> names = new LinkedHashMap<>();
        for (UUID id : ids) {
            names.put(id, nameOf(id));
        }
        return names;
    }

    private String nameOf(UUID id) {
        Player online = plugin.getServer().getPlayer(id);
        if (online != null) {
            return online.getName();
        }
        String name = plugin.getServer().getOfflinePlayer(id).getName();
        return name == null ? "somebody" : name;
    }

    private static void safely(String what, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException broken) {
            log.error(broken, "Manhunt's record failed while {}; the hunt itself is unaffected.", what);
        }
    }
}
