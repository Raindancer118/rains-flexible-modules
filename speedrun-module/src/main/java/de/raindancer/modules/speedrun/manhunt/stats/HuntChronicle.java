package de.raindancer.modules.speedrun.manhunt.stats;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.speedrun.SpeedrunHistory;
import de.raindancer.modules.speedrun.SpeedrunMode;
import de.raindancer.modules.speedrun.SpeedrunOutcome;
import de.raindancer.modules.speedrun.SpeedrunRun;
import de.raindancer.modules.speedrun.SpeedrunRunRecord;
import de.raindancer.modules.speedrun.SpeedrunSession;
import de.raindancer.modules.speedrun.manhunt.ManhuntSettings;
import de.raindancer.modules.speedrun.manhunt.hud.HuntTicker;
import de.raindancer.modules.speedrun.manhunt.model.Hunt;
import de.raindancer.modules.speedrun.manhunt.service.HuntDeathListener;
import de.raindancer.modules.speedrun.manhunt.service.HuntWatcher;
import de.raindancer.modules.speedrun.manhunt.service.HunterHoldListener;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * A hunt's own numbers and its Manhunt-only screen furniture: a {@link HuntLog} and a {@link HuntTicker}
 * for each hunt, everybody's results handed to the lobby's one history as the run ends, and the
 * hunt's summary in chat.
 *
 * <p>Never lets its own trouble reach the hunt: every callback swallows and logs what it throws,
 * because the hunt's own ending — compasses back, spectators stood up — runs right after it.
 */
public final class HuntChronicle implements HuntWatcher {

    private static final LogChannel log = Log.of("manhunt");

    @FunctionalInterface
    public interface Tickers {
        HuntTicker create(Hunt hunt, HuntLog record, SpeedrunRun run, HunterHoldListener hold, int headStartSeconds);
    }

    @FunctionalInterface
    public interface Recorders {
        void listen(Hunt hunt, SpeedrunRun run, HuntChronicle chronicle);
    }

    private final Plugin plugin;
    private final Supplier<ManhuntSettings> settings;
    private final Messages messages;
    private final ChatButtons buttons;
    private final LongSupplier clock;
    private final Tickers tickers;
    private final Recorders recorders;
    private final Supplier<Optional<SpeedrunRunRecord>> lastRun;
    private final ToIntFunction<SpeedrunRunRecord> numberOf;
    private final AtomicReference<HuntLog> current = new AtomicReference<>();
    private final AtomicReference<HuntTicker> ticker = new AtomicReference<>();

    /**
     * @param lastRun  the run the lobby kept last — the hunt that just ended, by the time its summary
     *                 is said
     * @param numberOf that run's number in the history, for the summary's button
     */
    public HuntChronicle(Plugin plugin, Supplier<ManhuntSettings> settings, Messages messages, ChatButtons buttons,
                         LongSupplier clock, Tickers tickers, Recorders recorders,
                         Supplier<Optional<SpeedrunRunRecord>> lastRun, ToIntFunction<SpeedrunRunRecord> numberOf) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.buttons = buttons;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tickers = tickers;
        this.recorders = recorders;
        this.lastRun = lastRun == null ? Optional::empty : lastRun;
        this.numberOf = numberOf == null ? run -> 0 : numberOf;
    }

    public Optional<HuntLog> currentLog() {
        return Optional.ofNullable(current.get());
    }

    @Override
    public void started(Hunt started, SpeedrunRun run, HunterHoldListener hold, int headStartSeconds) {
        safely("starting the record", () -> {
            SpeedrunSession session = run.session();
            HuntLog record = new HuntLog(clock, names(started.runners()), names(started.hunters()),
                    session.timeline(), session::elapsed);
            current.set(record);
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

    /**
     * Everybody's numbers and the winning side, for the history — asked by the lobby as the run ends,
     * before the hunt itself is wound down. Rated only with {@code stats-enabled}.
     */
    @Override
    public Optional<SpeedrunMode.Results> results(Hunt hunt, SpeedrunSession session, SpeedrunOutcome outcome) {
        HuntLog record = current.get();
        if (record == null) {
            return Optional.empty();
        }
        String winner = winnerOf(outcome == null ? null : outcome.reason());
        return Optional.of(new SpeedrunMode.Results(record.results(winner), winner, settings.get().statsEnabled()));
    }

    @Override
    public void ended(Hunt over, Optional<SpeedrunOutcome> outcome) {
        HuntTicker beat = ticker.getAndSet(null);
        current.set(null);
        safely("stopping the HUD", () -> {
            if (beat != null) {
                beat.stop();
            }
        });
        if (outcome.isEmpty() || !settings.get().summaryInChat()) {
            return;
        }
        safely("saying the summary", () -> lastRun.get()
                .filter(run -> run.outcome().equals(outcome.get().reason()))
                .ifPresent(run -> tellSummary(over, HuntSummary.of(run), numberOf.applyAsInt(run))));
    }

    /** {@link SpeedrunHistory#HUNTERS} for the last Runner caught, {@link SpeedrunHistory#RUNNERS} for the goal, else nobody. */
    public static String winnerOf(String reason) {
        if (HuntDeathListener.HUNTERS_WIN.equals(reason)) {
            return SpeedrunHistory.HUNTERS;
        }
        return reason != null && reason.startsWith("advancement:") ? SpeedrunHistory.RUNNERS : "";
    }

    public List<Component> summaryLines(HuntSummary summary, int number) {
        SpeedrunRunRecord run = summary.run();
        List<Component> lines = new ArrayList<>();
        lines.add(messages.prefixed("manhunt.summary.header-" + summary.winner().name().toLowerCase(Locale.ROOT),
                "number", String.valueOf(number), "time", HuntSummary.clock(run.time().toMillis())));
        for (HuntSummary.Catch caught : summary.catches()) {
            lines.add(caught.byName() == null
                    ? messages.get("manhunt.summary.catch-away", "time", HuntSummary.clock(caught.atMillis()),
                            "runner", caught.runnerName())
                    : messages.get("manhunt.summary.catch", "time", HuntSummary.clock(caught.atMillis()),
                            "runner", caught.runnerName(), "by", caught.byName()));
        }
        summary.hunterMvp().ifPresent(mvp -> lines.add(messages.get("manhunt.summary.mvp-hunter",
                "name", mvp.name(), "catches", String.valueOf(mvp.catches()))));
        summary.runnerMvp().ifPresent(mvp -> lines.add(messages.get("manhunt.summary.mvp-runner",
                "name", mvp.name(), "time", HuntSummary.clock(mvp.survivedMillis()))));
        summary.explorer().ifPresent(mvp -> lines.add(messages.get("manhunt.summary.explorer",
                "name", mvp.name(), "blocks", String.valueOf(Math.round(mvp.distance())))));
        return lines;
    }

    private void tellSummary(Hunt over, HuntSummary summary, int number) {
        List<Component> lines = summaryLines(summary, number);
        for (UUID id : over.everybody()) {
            Player player = plugin.getServer().getPlayer(id);
            if (player == null) {
                continue;
            }
            lines.forEach(player::sendMessage);
            if (buttons != null && number > 0) {
                player.sendMessage(messages.get("manhunt.summary.more").append(Component.space())
                        .append(buttons.label(messages.raw("manhunt.summary.open-button"))
                                .tooltip(messages.raw("manhunt.summary.open-tooltip"))
                                .runs("/manhunt summary " + number).render()));
            }
        }
    }

    private Map<UUID, String> names(Set<UUID> ids) {
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
