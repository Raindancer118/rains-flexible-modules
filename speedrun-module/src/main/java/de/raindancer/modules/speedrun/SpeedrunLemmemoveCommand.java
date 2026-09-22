package de.raindancer.modules.speedrun;

import de.raindancer.modules.speedrun.util.PermissionNodes;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.function.Supplier;

/**
 * {@code /lemmemove [player]} — exempts a player from the speedrun movement freeze (the READY-state
 * one and the countdown's own) without touching their place in the race. See {@link SpeedrunLobby#release}
 * for exactly what this does and does not do; it keeps racing, it only lifts the freeze.
 *
 * <p>Bare, it releases whoever typed it. With a name, it releases somebody else instead — gated on
 * {@link PermissionNodes#LEMMEMOVE_OTHERS} rather than {@link PermissionNodes#LEMMEMOVE_SELF}, since
 * that is the form that can actually hand somebody a head start over the racers who did not ask for one.
 * That resolution is {@link SpeedrunFreezeCommand}'s, shared with the undo.
 *
 * @see SpeedrunFreezeAgainCommand
 */
public final class SpeedrunLemmemoveCommand extends SpeedrunFreezeCommand {

    public SpeedrunLemmemoveCommand(Supplier<SpeedrunAdminServices> services) {
        super(services);
    }

    @Override
    protected String messageKey() {
        return "lemmemove";
    }

    @Override
    protected void actOn(SpeedrunAdminServices live, CommandSender sender, Player target) {
        live.lobby().release(target.getUniqueId());
        live.messages().send(sender, key("done"), "player", target.getName());
    }

    @Override
    public String describe() {
        return "escape the speedrun movement freeze";
    }
}
