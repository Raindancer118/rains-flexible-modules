package de.raindancer.modules.speedrun;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

/** {@code /speedruntime <time>} — the running clock set by hand. */
public final class SpeedrunTimeCommand implements ISpeedrunCommand {

    private final Supplier<SpeedrunAdminServices> services;

    public SpeedrunTimeCommand(Supplier<SpeedrunAdminServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        SpeedrunAdminServices live = services.get();
        if (args.length == 0) {
            live.messages().send(source.getSender(), "speedrun.time.usage");
            return;
        }
        var parsed = RunClock.parse(args[0]);
        if (parsed.isEmpty()) {
            live.messages().send(source.getSender(), "speedrun.time.unreadable", "time", args[0]);
            return;
        }
        boolean set = live.lobby().session().map(session -> session.setElapsed(parsed.get())).orElse(false);
        live.messages().send(source.getSender(), set ? "speedrun.time.set" : "speedrun.time.no-run",
                "time", SpeedrunTimerDisplay.plain(parsed.get()));
    }

    @Override
    public String describe() {
        return "set the running clock";
    }
}
