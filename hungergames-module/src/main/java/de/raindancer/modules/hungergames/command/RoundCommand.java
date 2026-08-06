package de.raindancer.modules.hungergames.command;

import de.raindancer.modules.hungergames.HungerGamesServices;
import de.raindancer.modules.hungergames.service.GameControlService;
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
    private final boolean reportsItsOwnOutcome;

    public RoundCommand(Supplier<HungerGamesServices> services, String verb, String permission, Step step) {
        this(services, verb, permission, step, false);
    }

    /**
     * @param reportsItsOwnOutcome whether the step tells whoever asked how it went, so this command must not
     *                             also announce it as done. True for {@code /init}, whose blocks are placed on
     *                             a later tick — see {@link #reportsItsOwnOutcome()}
     */
    public RoundCommand(Supplier<HungerGamesServices> services, String verb, String permission, Step step,
                        boolean reportsItsOwnOutcome) {
        this.services = services;
        this.verb = verb;
        this.permission = permission;
        this.step = step;
        this.reportsItsOwnOutcome = reportsItsOwnOutcome;
    }

    /**
     * Whether the step says for itself how it went.
     *
     * <p>It exists because of a line a gamemaster actually read: "✓ /init done", and a moment later "The arena
     * could not be built". Both were true from where they were written — the command had been told the job was
     * accepted, and the build had been told it failed — and together they are nonsense.
     *
     * <p>{@code /init} places several hundred thousand blocks on the tick of the thread that owns them, which
     * is not the tick the command runs on. So it cannot know the outcome, and must not claim one:
     * {@code ArenaBuildService.Told} reports it when it is actually known. {@code /startup} and {@code /start}
     * finish, or refuse, inside the call, and still report here.
     */
    public boolean reportsItsOwnOutcome() {
        return reportsItsOwnOutcome;
    }

    /**
     * Whether a refusal is always spoken aloud.
     *
     * <p>True for every step, and asserted by a test rather than left as a habit. A step that refused silently
     * is one somebody types again, and the second attempt is made while they are already wondering whether the
     * plugin is broken.
     */
    public boolean saysWhyItRefused() {
        return true;
    }

    /**
     * A tribute count typed after the command, if there is a usable one.
     *
     * <p>Empty for nothing typed, for something that is not a number, and for a number outside what a round
     * can hold — never a default, and never clamped. Both would build an arena for a number the gamemaster did
     * not choose, which is the bug this whole method exists because of: the port had been deriving the count
     * from the whitelist, so a server whose sign-up sheet was not filled in yet got an arena for two and was
     * never asked.
     */
    public static Optional<Integer> countIn(String[] args) {
        if (args == null || args.length == 0 || args[0] == null || args[0].isBlank()) {
            return Optional.empty();
        }
        try {
            int count = Integer.parseInt(args[0].strip());
            if (count < GameControlService.MIN_PLAYERS || count > GameControlService.MAX_PLAYERS) {
                return Optional.empty();
            }
            return Optional.of(count);
        } catch (NumberFormatException notANumber) {
            return Optional.empty();
        }
    }

    /**
     * {@code /init} — build the arena.
     *
     * <p>Takes the player count as an argument because it decides how many platforms are pasted, and because
     * the alternative the source used — asking in chat afterwards — meant the arena existed before anybody
     * had said how big it should be.
     */
    public static RoundCommand init(Supplier<HungerGamesServices> services) {
        return new RoundCommand(services, "init", PermissionNodes.ADMIN,
                (hg, who) -> Optional.of("no tribute count was given"), true);
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

        // /init needs a number, and asks for one rather than assuming. See countIn.
        if (verb.equals("init")) {
            Optional<Integer> given = countIn(args);
            if (given.isPresent()) {
                buildFor(hg, sender, given.get());
            } else if (args.length > 0) {
                hg.messages().send(sender, "hungergames.init-not-a-count",
                        "typed", args[0],
                        "least", String.valueOf(GameControlService.MIN_PLAYERS),
                        "most", String.valueOf(GameControlService.MAX_PLAYERS));
            } else {
                askHowMany(hg, sender);
            }
            return;
        }

        Optional<String> refused = step.run(hg, sender);
        if (refused.isPresent()) {
            // Said out loud, always. A step that refused silently is one somebody types again, and the
            // second one is typed while they are already wondering whether the plugin is broken.
            hg.messages().send(sender, "hungergames.step-refused", "step", verb, "why", refused.get());
            return;
        }
        if (!reportsItsOwnOutcome) {
            hg.messages().send(sender, "hungergames.step-done", "step", verb);
        }
        hg.log().info("/{} run by {}.", verb, sender.getName());
    }

    /**
     * Asks how many, by opening Core's number chooser.
     *
     * <p>The old plugin asked in chat: type a number, press enter. That worked and it is the worse of the two
     * — a typo is a wasted attempt, the range has to be stated in words and remembered, and a chat prompt is
     * a thing a gamemaster can walk away from without noticing it is still waiting.
     *
     * <p>{@code AmountChooser} is Core's, opens at however many tributes are registered, carries the range as
     * a real limit rather than as a sentence, and steps by 1, 10 and 100. Nothing happens until Accept, so
     * backing out of it builds nothing — which is the right behaviour for a page that pastes an arena.
     *
     * <p>A console cannot be shown a page and is told to say the number on the command line instead, which is
     * what a start-up script wants anyway.
     */
    private void askHowMany(HungerGamesServices hg, CommandSender sender) {
        if (!(sender instanceof Player player)) {
            hg.messages().send(sender, "hungergames.init-needs-a-count",
                    "least", String.valueOf(GameControlService.MIN_PLAYERS),
                    "most", String.valueOf(GameControlService.MAX_PLAYERS));
            return;
        }
        int registered = hg.session().participants().all().size();
        // Opened at the register's own size, because that is usually the right answer and the one a
        // gamemaster is about to nudge. Below the floor it opens at the floor — AmountChooser brings a start
        // value inside its range rather than refusing to open.
        new de.raindancer.core.ui.choose.AmountChooser(player, hg.brand(), null,
                "Tributes to build platforms for",
                Math.max(registered, GameControlService.MIN_PLAYERS),
                GameControlService.MIN_PLAYERS, GameControlService.MAX_PLAYERS,
                count -> buildFor(hg, player, count))
                .open();
    }

    /**
     * Starts the build for a count that has actually been chosen.
     *
     * <p>Nothing is announced as done here. The blocks are placed on the tick of the thread that owns them,
     * and {@code ArenaBuildService.Told} says how it went once that is known — see
     * {@link #reportsItsOwnOutcome()} for the contradictory pair of lines that taught us this.
     */
    private void buildFor(HungerGamesServices hg, CommandSender who, int count) {
        Optional<String> refused = hg.control().init(uuidOf(who), count);
        if (refused.isPresent()) {
            hg.messages().send(who, "hungergames.step-refused", "step", "init", "why", refused.get());
            return;
        }
        hg.log().info("/init run by {} for {} tribute(s).", who.getName(), count);
    }

    @Override
    public List<String> suggest(CommandSourceStack source, String[] args) {
        if (!verb.equals("init") || args.length > 1) {
            // Nothing to suggest: /startup and /start take no argument, and answering with an empty list
            // rather than leaving the default is what stops Paper offering player names for a command that
            // ignores them.
            return List.of();
        }
        // A handful of realistic tournament sizes, plus however many are registered. Not every number from
        // two to a hundred: a completion list nobody can read is the same as none.
        HungerGamesServices hg = services.get();
        int registered = hg.session().participants().all().size();
        List<String> offered = new java.util.ArrayList<>(List.of("2", "8", "12", "16", "24", "32", "48"));
        if (registered >= GameControlService.MIN_PLAYERS && !offered.contains(String.valueOf(registered))) {
            offered.add(String.valueOf(registered));
        }
        String typed = args.length == 0 ? "" : args[0];
        return offered.stream().filter(one -> one.startsWith(typed)).sorted().toList();
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
