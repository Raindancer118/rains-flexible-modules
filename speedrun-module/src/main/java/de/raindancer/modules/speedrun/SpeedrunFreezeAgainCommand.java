package de.raindancer.modules.speedrun;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.function.Supplier;

/**
 * {@code /freezeagain [player]} — undoes a {@code /lemmemove}, putting the movement freeze back on
 * somebody it was lifted for. See {@link SpeedrunLemmemoveCommand} for the freeze itself.
 *
 * <h2>Why an undo is worth a command</h2>
 * A release is permanent until the lobby resets: {@link SpeedrunLobby#release} has no expiry, so an
 * admin who lifted the freeze for the wrong name, or for somebody who has since got themselves
 * unstuck, had no way back short of resetting the world around everybody. The release is also what
 * lets somebody walk away mid-countdown, which is exactly what it should not still be doing once the
 * reason for it is over.
 *
 * <p>Undoing a release nobody had is said out loud rather than answered with a cheerful "done":
 * a typo'd name is the likeliest way to get here, and a confirmation for something that did not
 * happen is how an admin walks away believing a head start has been taken back when it has not.
 */
public final class SpeedrunFreezeAgainCommand extends SpeedrunFreezeCommand {

    public SpeedrunFreezeAgainCommand(Supplier<SpeedrunAdminServices> services) {
        super(services);
    }

    @Override
    protected String messageKey() {
        return "freezeagain";
    }

    @Override
    protected void actOn(SpeedrunAdminServices live, CommandSender sender, Player target) {
        boolean was = live.lobby().refreeze(target.getUniqueId());
        live.messages().send(sender, key(was ? "done" : "not-released"), "player", target.getName());
    }

    @Override
    public String describe() {
        return "undo a /lemmemove and freeze somebody again";
    }
}
