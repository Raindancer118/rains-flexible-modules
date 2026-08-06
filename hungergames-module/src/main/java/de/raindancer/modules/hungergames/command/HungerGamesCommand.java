package de.raindancer.modules.hungergames.command;

import de.raindancer.modules.hungergames.HungerGamesServices;
import de.raindancer.modules.hungergames.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * {@code /hg} — the door to everything.
 *
 * <h2>Why this has so few subcommands</h2>
 * Because almost everything a tournament needs is a page, not a verb. {@code /hg admin} opens the suite;
 * {@code /hg teams} and {@code /hg shop} open the two pages players use; {@code /hg spectate} is for people
 * who are out. Four of the six subcommands here are "open a screen", and that is the design rather than an
 * omission — a gamemaster with forty people waiting should be clicking.
 *
 * <p>The two that are not: {@code /hg status}, because somebody wants to know where the round is without
 * opening an inventory (and because the console can ask it), and {@code /hg end}, because the console has no
 * inventory to hold a confirmation dialog in and a server being shut down needs a way to finish the round
 * cleanly.
 *
 * <h2>Built at bootstrap, run much later</h2>
 * Paper's {@code COMMANDS} lifecycle event fires during the bootstrap phase — before the plugin object
 * exists, let alone this module's services. A handler registered in {@code onEnable} never runs at all: no
 * warning, no exception, the command simply is not there. So this holds a {@link Supplier} and asks at the
 * moment it runs, never at construction.
 */
public final class HungerGamesCommand implements IHungerGamesCommand {

    private final Supplier<HungerGamesServices> services;

    public HungerGamesCommand(Supplier<HungerGamesServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "the tournament: its screens, its state and its ending";
    }

    @Override
    public String permission() {
        // Deliberately none. /hg teams and /hg shop are a player's, and a permission on the root would put
        // the whole command behind a node that only staff hold — so tributes could not pick a team.
        return "";
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return true;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        HungerGamesServices hg = services.get();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "", "help" -> help(hg, sender);
            case "status" -> status(hg, sender);
            case "admin" -> openFor(hg, sender, PermissionNodes.mayOpenTheAdminSuite(sender),
                    hg.screens()::admin);
            case "teams" -> openFor(hg, sender, true, hg.screens()::teams);
            case "shop" -> openFor(hg, sender, true, hg.screens()::shop);
            case "spectate" -> openFor(hg, sender, true, hg.screens()::spectate);
            case "end" -> end(hg, sender);
            default -> hg.messages().send(sender, "hungergames.unknown-subcommand", "what", sub);
        }
    }

    /**
     * Opens a page, when the sender is a player and may.
     *
     * <p>The console check first, because "you are not allowed" is a wrong and confusing answer to give a
     * console that simply has no inventory.
     */
    private void openFor(HungerGamesServices hg, CommandSender sender, boolean allowed,
                         java.util.function.Consumer<Player> page) {
        if (!(sender instanceof Player player)) {
            hg.messages().send(sender, "hungergames.only-a-player");
            return;
        }
        if (!allowed) {
            hg.messages().send(sender, "hungergames.not-allowed");
            return;
        }
        page.accept(player);
    }

    /** Where the round is, in one line. Answerable from the console, and by anybody. */
    private void status(HungerGamesServices hg, CommandSender sender) {
        hg.messages().send(sender, "hungergames.status",
                "phase", hg.session().phase().name(),
                "alive", String.valueOf(hg.session().participants().aliveCount()),
                "registered", String.valueOf(hg.session().participants().all().size()),
                "teams", String.valueOf(hg.session().teams().count()));
    }

    /**
     * Ends the round now, scoring it as a time-out would.
     *
     * <p>Guarded by a permission and by the phase, and <em>not</em> by a confirmation — typing it is the
     * confirmation, the same reasoning as {@code /start}. The button on {@code /hg admin} asks first because
     * a button sits next to other buttons; this was typed on purpose.
     */
    private void end(HungerGamesServices hg, CommandSender sender) {
        if (!PermissionNodes.mayOpenTheAdminSuite(sender)) {
            hg.messages().send(sender, "hungergames.not-allowed");
            return;
        }
        if (!hg.control().endRound()) {
            hg.messages().send(sender, "hungergames.step-refused", "step", "end",
                    "why", "no round is running (currently " + hg.session().phase() + ")");
            return;
        }
        hg.log().info("The round was ended by {} through /hg end.", sender.getName());
    }

    private void help(HungerGamesServices hg, CommandSender sender) {
        for (String line : List.of("hungergames.help-header", "hungergames.help-teams",
                "hungergames.help-shop", "hungergames.help-spectate", "hungergames.help-status")) {
            hg.messages().send(sender, line);
        }
        if (PermissionNodes.mayOpenTheAdminSuite(sender)) {
            hg.messages().send(sender, "hungergames.help-admin");
        }
    }

    @Override
    public List<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length > 1) {
            return List.of();
        }
        String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);

        List<String> offered = new ArrayList<>(List.of("teams", "shop", "spectate", "status", "help"));
        // Suggested only to somebody who may use them. Offering "admin" to every player is how a page they
        // cannot open becomes the thing they type first.
        if (PermissionNodes.mayOpenTheAdminSuite(source.getSender())) {
            offered.add("admin");
            offered.add("end");
        }
        return offered.stream().filter(one -> one.startsWith(typed)).sorted().toList();
    }
}
