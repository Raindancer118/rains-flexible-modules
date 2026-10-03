package de.raindancer.modules.speedrun;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

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
        // Said with the buttons that fix a refusal — the same path the menu's Resume takes.
        new SpeedrunActions(live.lobby(), live.messages()).resume(source.getSender(), already);
    }

    @Override
    public String describe() {
        return "start a run over the world as it stands — nobody moved or cleared — at a given time";
    }
}
