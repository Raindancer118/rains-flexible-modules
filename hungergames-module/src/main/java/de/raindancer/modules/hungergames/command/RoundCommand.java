package de.raindancer.modules.hungergames.command;

import de.raindancer.modules.hungergames.HungerGamesServices;
import de.raindancer.modules.hungergames.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * One step of the run-up: {@code /init}, {@code /startup} or {@code /start}.
 *
 * <h2>Why the three are one class</h2>
 * Because they are the same command three times over — check the phase, do the step, say what happened — and
 * the version that had them written out separately had them drift: {@code /init} reported its refusal and
 * {@code /startup} returned silently, so a gamemaster who typed the second one out of order got nothing at
 * all and typed it again.
 *
 * <p>What differs between them is the verb and the permission, and both arrive as constructor arguments. The
 * shape is deliberate: adding a fourth step is a line in {@code HungerGamesCommands}, not a fourth copy of
 * this.
 *
 * <h2>Why these are typed and the rest is clicked</h2>
 * A tournament is run from {@code /hg admin}, which is a menu, because a gamemaster with forty people waiting
 * should be clicking rather than spelling. These three earn their place beside it because they are a
 * <em>sequence</em>: they are typed in order, they are typed by somebody who already knows which comes next,
 * and the middle of a countdown is not when to be finding a button.
 *
 * <p>They are also the part the console can do. A server started by a script can build its arena without
 * anybody being logged in, and a menu cannot be opened by a console.
 */
public final class RoundCommand implements IHungerGamesCommand {

    /** What this step does, given the services and whoever asked. Empty means it worked. */
    @FunctionalInterface
    public interface Step {
        Optional<String> run(HungerGamesServices services, CommandSender who);
    }

    private final Supplier<HungerGamesServices> services;
    private final String verb;
    private final String permission;
    private final Step step;

    public RoundCommand(Supplier<HungerGamesServices> services, String verb, String permission, Step step) {
        this.services = services;
        this.verb = verb;
        this.permission = permission;
        this.step = step;
    }

    /**
     * {@code /init} — build the arena.
     *
     * <p>Takes the player count as an argument because it decides how many platforms are pasted, and because
     * the alternative the source used — asking in chat afterwards — meant the arena existed before anybody
     * had said how big it should be.
     */
    public static RoundCommand init(Supplier<HungerGamesServices> services) {
        return new RoundCommand(services, "init", PermissionNodes.ADMIN, (hg, who) -> {
            int count = hg.session().participants().all().size();
            return hg.control().init(uuidOf(who), Math.max(count, 2));
        });
    }

    /** {@code /startup} — tributes down the tubes and up onto their platforms. */
    public static RoundCommand startup(Supplier<HungerGamesServices> services) {
        return new RoundCommand(services, "startup", PermissionNodes.GAMEMASTER,
                (hg, who) -> hg.control().startup(uuidOf(who)));
    }

    /**
     * {@code /start} — the countdown, and then the round.
     *
     * <p>The one irreversible step of the three, and the only one this class does not guard with a
     * confirmation: typing {@code /start} <em>is</em> the confirmation. That is a real difference from the
     * button on {@code /hg admin}, which asks first — a button is next to other buttons and a command was
     * typed on purpose. The console has no inventory to open a dialog in, which is the other half of why.
     */
    public static RoundCommand start(Supplier<HungerGamesServices> services) {
        return new RoundCommand(services, "start", PermissionNodes.GAMEMASTER,
                (hg, who) -> hg.control().start(uuidOf(who)));
    }

    /** Whoever asked, as a UUID — or a nil UUID for the console, which is not a player and has none. */
    private static java.util.UUID uuidOf(CommandSender who) {
        return who instanceof Player player ? player.getUniqueId() : new java.util.UUID(0, 0);
    }

    @Override
    public String describe() {
        return "the " + verb + " step of a round's run-up";
    }

    @Override
    public String permission() {
        return permission;
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return sender.hasPermission(permission);
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        HungerGamesServices hg = services.get();

        if (!canUse(sender)) {
            hg.messages().send(sender, "hungergames.not-allowed");
            return;
        }

        Optional<String> refused = step.run(hg, sender);
        if (refused.isPresent()) {
            // Said out loud, always. A step that refused silently is one somebody types again, and the
            // second one is typed while they are already wondering whether the plugin is broken.
            hg.messages().send(sender, "hungergames.step-refused", "step", verb, "why", refused.get());
            return;
        }
        hg.messages().send(sender, "hungergames.step-done", "step", verb);
        hg.log().info("/{} run by {}.", verb, sender.getName());
    }

    @Override
    public List<String> suggest(CommandSourceStack source, String[] args) {
        // Nothing to suggest: none of the three takes an argument. Answering with an empty list rather than
        // leaving the default is what stops Paper offering player names for a command that ignores them.
        return List.of();
    }

    /** For the module's own diagnostic, and for the test that checks the three are distinct. */
    public String verb() {
        return verb;
    }

    /** The three, in the order they are run. */
    public static List<RoundCommand> theRunUp(Supplier<HungerGamesServices> services) {
        return List.of(init(services), startup(services), start(services));
    }
}
