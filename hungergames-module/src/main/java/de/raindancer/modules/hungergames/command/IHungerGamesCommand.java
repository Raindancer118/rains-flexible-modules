package de.raindancer.modules.hungergames.command;

import io.papermc.paper.command.brigadier.BasicCommand;

/**
 * A command belonging to this module.
 *
 * <h2>The one thing every command here has to get right</h2>
 * It is built during Paper's bootstrap phase, long before the module enables — the {@code COMMANDS} lifecycle
 * event fires then, and a handler registered in {@code onEnable} never runs at all, silently. So a command
 * <b>must not capture anything the module builds</b>; it holds a supplier and asks when it is run.
 *
 * <p>Which means the state to design for is "command registered, module not running": somebody can type it
 * before the module has started, after it failed to start, and after it has stopped. The host wraps every one
 * of these in {@code ModuleCommands.guarded}, so that answers with one red line naming the module rather than
 * a {@link NullPointerException} in the console.
 *
 * <h2>What earns a subcommand here</h2>
 * Typing it beats clicking, or it takes an argument a menu cannot ask for. A tournament is run from
 * {@code /hg admin}, which is a menu, and that is deliberate: forty people are waiting and a gamemaster
 * should be clicking, not spelling. What earns its place beside it is what a menu cannot do —
 * {@code /allow <player>}, which takes somebody who is not online yet; {@code /hg team <name> <colour>}, which
 * takes two arguments at once; and the run-up verbs {@code /init}, {@code /startup} and {@code /start}, which
 * are the sequence and are typed in order.
 *
 * <p>And one earns it for a third reason: the console has no inventory to open a dialog in, so anything the
 * menu guards with a confirmation page needs a way to say yes that is not "typing the command was the yes".
 */
public interface IHungerGamesCommand extends BasicCommand {

    /** What this command is for, shown in help and in the diagnostic that lists them. */
    String describe();
}
