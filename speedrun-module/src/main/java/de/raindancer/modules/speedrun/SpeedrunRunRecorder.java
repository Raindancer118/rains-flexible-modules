package de.raindancer.modules.speedrun;

import de.raindancer.core.ui.chat.ChatButton;
import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.speedrun.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What happens to a run once it is over: it goes into the history, every racer is told where it
 * stands — a personal best, a record, how far off, or why it is not ranked — and offered what to do
 * next: the run's summary, its seed to copy, the same seed again.
 */
final class SpeedrunRunRecorder {

    /** How long the buttons under a finished run keep working. */
    static final Duration BUTTONS_LAST = Duration.ofMinutes(30);

    private final SpeedrunLobby lobby;
    private final SpeedrunToolkit kit;

    SpeedrunRunRecorder(SpeedrunLobby lobby, SpeedrunToolkit kit) {
        this.lobby = lobby;
        this.kit = kit;
    }

    /** Keeps {@code session} in the history and tells everybody who raced in it. */
    SpeedrunRunRecord record(SpeedrunSession session, SpeedrunSplitTracker splits, SpeedrunOutcome outcome,
                             SpeedrunCategory category, long seed, long startedAt, boolean completed,
                             SpeedrunMode mode) {
        if (completed) {
            splits.reach(SpeedrunMilestones.FINISH.id(), null);
        }
        Map<UUID, String> names = new LinkedHashMap<>();
        for (UUID id : session.participants()) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(id);
            names.put(id, player.getName() == null ? id.toString().substring(0, 8) : player.getName());
        }
        Optional<SpeedrunMode.Results> results = Optional.empty();
        if (mode != null) {
            try {
                results = mode.results(session, outcome);
            } catch (RuntimeException broken) {
                Log.of("speedrun").error(broken, "The game mode '{}' failed to say how the run went; it is kept "
                        + "without per-player results.", mode.id());
            }
        }
        SpeedrunRunRecord record = new SpeedrunRunRecord(UUID.randomUUID().toString(), category, startedAt,
                outcome.elapsed(), outcome.reason(), completed, seed, names, session.timeline().entries(),
                splits.labels(), results.map(SpeedrunMode.Results::players).orElse(List.of()),
                results.map(SpeedrunMode.Results::winner).orElse(""));
        SpeedrunHistory history = kit.history();
        if (history == null) {
            return record;
        }
        Optional<SpeedrunRunRecord> recordBefore = history.record(category);
        Map<UUID, Optional<SpeedrunRunRecord>> bestBefore = new HashMap<>();
        names.keySet().forEach(id -> bestBefore.put(id, history.personalBest(id, category)));
        history.add(record, results.map(SpeedrunMode.Results::rated).orElse(false));
        boolean ranked = record.ranked(lobby.config().rankEditedRuns());
        for (UUID id : names.keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                tell(player, record, ranked, bestBefore.get(id), recordBefore);
            }
        }
        return record;
    }

    private void tell(Player player, SpeedrunRunRecord run, boolean ranked,
                      Optional<SpeedrunRunRecord> bestBefore, Optional<SpeedrunRunRecord> recordBefore) {
        if (kit.messages() == null) {
            return;
        }
        String time = SpeedrunTimerDisplay.plain(run.time());
        String category = run.category().label();
        if (!ranked) {
            String why = !run.completed() ? "the goal was not reached"
                    : run.resumed() ? "it was resumed after a restart" : "its clock was set by hand";
            kit.messages().send(player, "speedrun.result.not-ranked", "reason", why);
        } else if (recordBefore.isEmpty() || run.time().compareTo(recordBefore.get().time()) < 0) {
            kit.messages().send(player, "speedrun.result.record", "time", time, "category", category);
        } else if (bestBefore.isEmpty() || run.time().compareTo(bestBefore.get().time()) < 0) {
            kit.messages().send(player, "speedrun.result.personal-best", "time", time, "category", category);
        } else {
            kit.messages().send(player, "speedrun.result.behind",
                    "best", SpeedrunTimerDisplay.plain(bestBefore.get().time()),
                    "behind", SpeedrunTimerDisplay.plain(run.time().minus(bestBefore.get().time())),
                    "category", category);
        }
        Component seedLine = kit.messages().prefixed("speedrun.result.seed", "seed", String.valueOf(run.seed()));
        ChatButtons buttons = kit.buttons();
        if (buttons == null) {
            player.sendMessage(seedLine);
            return;
        }
        UUID viewer = player.getUniqueId();
        List<ChatButton> row = new ArrayList<>();
        row.add(buttons.label("<aqua>[Summary]</aqua>").tooltip("<gray>Every split, death and pause of this run")
                .forOnly(viewer).expiringIn(BUTTONS_LAST).repeatable()
                .does(clicker -> openSummary(clicker, run)));
        row.add(buttons.label("<gray>[Copy seed]</gray>").tooltip("<gray>Copies the seed to your clipboard")
                .copies(String.valueOf(run.seed())));
        if (player.hasPermission(PermissionNodes.ADMIN)) {
            row.add(buttons.label("<gold>[Same seed again]</gold>")
                    .tooltip("<gray>The next reset remakes this exact map. It is ranked as a set seed.")
                    .forOnly(viewer).expiringIn(BUTTONS_LAST)
                    .does(clicker -> {
                        Player who = Bukkit.getPlayer(clicker);
                        if (who == null || !SpeedrunAccess.SEEDS.allows(lobby, who)) {
                            return;   // asked again at the click: the button outlives the moment it was sent
                        }
                        lobby.replaySeedNextReset();
                        kit.messages().send(who, "speedrun.seed.same-next");
                    }));
        }
        player.sendMessage(seedLine.append(Component.text(" ")).append(buttons.row(row.toArray(ChatButton[]::new))));
    }

    private void openSummary(UUID clicker, SpeedrunRunRecord run) {
        Player player = Bukkit.getPlayer(clicker);
        if (player != null) {
            Scheduling.entity(kit.plugin(), player,
                    () -> new SpeedrunRunSummaryMenu(lobby, run, player, null).open());
        }
    }
}
