package de.raindancer.modules.speedrun;

import de.raindancer.modules.speedrun.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

/** {@code /speedrunreset} — force-ends whatever is happening and deletes and remakes the world from
 *  scratch. See {@link SpeedrunLobby#forceReset} for exactly what this does. */
public final class SpeedrunResetCommand implements ISpeedrunCommand {

    private final Supplier<SpeedrunAdminServices> services;

    public SpeedrunResetCommand(Supplier<SpeedrunAdminServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        SpeedrunAdminServices live = services.get();
        SpeedrunLobby.ResetOutcome outcome = live.lobby().forceReset();
        live.messages().send(source.getSender(), switch (outcome) {
            case RESET -> "speedrun.reset.done";
            case COUNTDOWN_IN_PROGRESS -> "speedrun.reset.countdown-in-progress";
        });
    }

    @Override
    public @NotNull String permission() {
        return PermissionNodes.ADMIN;
    }

    @Override
    public String describe() {
        return "force-end the current run and regenerate the world";
    }
}
