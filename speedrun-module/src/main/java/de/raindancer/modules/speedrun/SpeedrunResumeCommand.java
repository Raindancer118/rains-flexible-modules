package de.raindancer.modules.speedrun;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * {@code /speedrunresume [time]} — a run started over the world as it stands, after a restart lost the
 * old one: everybody in the run's three worlds races on from where they are, with what they carry.
 * See {@link SpeedrunLobby#resume}.
 */
public final class SpeedrunResumeCommand implements ISpeedrunCommand {

    private final Supplier<SpeedrunAdminServices> services;

    public SpeedrunResumeCommand(Supplier<SpeedrunAdminServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        SpeedrunAdminServices live = services.get();
        Duration already = Duration.ZERO;
        if (args.length > 0) {
            var parsed = RunClock.parse(args[0]);
            if (parsed.isEmpty()) {
                live.messages().send(source.getSender(), "speedrun.time.unreadable", "time", args[0]);
                return;
            }
            already = parsed.get();
        }
        SpeedrunWorlds worlds = SpeedrunWorlds.around(live.lobby().config().worldName());
        Set<UUID> present = Bukkit.getOnlinePlayers().stream()
                .filter(player -> worlds.contains(player.getWorld().getName()))
                .map(Player::getUniqueId)
                .filter(id -> !live.lobby().isSpectator(id))
                .collect(Collectors.toUnmodifiableSet());
        SpeedrunLobby.StartOutcome outcome = live.lobby().resume(present, already);
        live.messages().send(source.getSender(), switch (outcome) {
            case STARTED -> "speedrun.resume.done";
            case NOT_READY -> "speedrun.resume.not-ready";
            case NO_PARTICIPANTS -> "speedrun.start.no-participants";
            case NO_END_CONDITION -> "speedrun.start.no-end-condition";
            case WORLD_MISSING -> "speedrun.start.world-missing";
            default -> "speedrun.resume.refused";
        }, "players", String.valueOf(present.size()), "time", SpeedrunTimerDisplay.plain(already));
    }

    @Override
    public String describe() {
        return "start a run over the world as it stands — nobody moved or cleared — at a given time";
    }
}
