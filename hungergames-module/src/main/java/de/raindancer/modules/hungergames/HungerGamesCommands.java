package de.raindancer.modules.hungergames;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.hungergames.command.AllowCommand;
import de.raindancer.modules.hungergames.command.HungerGamesCommand;
import de.raindancer.modules.hungergames.command.RoundCommand;
import de.raindancer.modules.hungergames.util.PermissionNodes;

import java.util.List;

/**
 * The commands, built at bootstrap and pointed at services that do not exist yet.
 *
 * <h2>Why this class exists at all</h2>
 * Paper fires its {@code COMMANDS} lifecycle event during the bootstrap phase — before the plugin object
 * exists, let alone this module's services. A handler registered in {@code onEnable} never runs at all: no
 * warning, no exception, the command simply does not exist. So the handlers must be built early and must not
 * capture anything.
 *
 * <p>Hence the supplier. The commands hold a way to <em>ask</em> for the services, and {@link #ready} fills
 * it in when the module enables. Between the two, {@code ModuleCommands.guarded} answers with one red line
 * saying the module has not started rather than a {@link NullPointerException} in the console.
 *
 * <h2>Why the list is short</h2>
 * Six commands for a plugin with twenty-four screens, and that is the point. A tournament is run by clicking:
 * a gamemaster with forty people waiting should be navigating a page, not spelling a subcommand. What earns a
 * place here is what a menu cannot do —
 *
 * <ul>
 *   <li><b>{@code /allow}</b> takes people who are <em>not online yet</em>, which is exactly the set a player
 *       picker cannot offer. It is used before the evening starts, in bulk, off a sign-up sheet.</li>
 *   <li><b>{@code /init}, {@code /startup}, {@code /start}</b> are a sequence, typed in order by somebody who
 *       already knows what comes next — and they are the part a console can do, so a scripted server can
 *       build its arena with nobody logged in.</li>
 *   <li><b>{@code /hg}</b> is the door to the pages, plus the two things that are not pages: what the round is
 *       doing, and ending it from a console that has no inventory to hold a confirmation in.</li>
 * </ul>
 *
 * <h2>The names, and the one that is deliberately generic</h2>
 * {@code /init}, {@code /startup} and {@code /start} are short, generic names on a shared server, and they are
 * kept because they are what the old plugin's gamemasters have in their fingers. {@code /hg} carries aliases
 * for all three, so a server where another plugin wins the race still has a way in — and
 * {@code BundleJarTest} fails the build if any of them collides with another module in the same jar, which is
 * the case where the loser would be silent rather than namespaced.
 */
public final class HungerGamesCommands {

    private static volatile HungerGamesServices services;

    private HungerGamesCommands() {
    }

    /**
     * What the module declares at bootstrap.
     *
     * <p>Cheap, repeatable and dependent on nothing — Paper may ask more than once, and it asks before
     * {@code Bukkit.getServer()} answers anything useful.
     */
    public static List<ModuleCommand> declared() {
        return List.of(
                ModuleCommand.of("hg", "The tournament: its screens, its state and its ending",
                                new HungerGamesCommand(HungerGamesCommands::require))
                        .aliased("hungergames")
                        .taking("teams — pick a team",
                                // No "shop": the sponsor shop opens at a beacon and nowhere else.
                                "spectate — watch a living tribute",
                                "give <item> [amount] [player] — a custom item, for testing",
                                "status — where the round is",
                                "admin — the page a tournament is run from",
                                "end — score the round now"),

                ModuleCommand.of("allow", "Puts somebody on the list of tributes — and nothing else",
                                new AllowCommand(HungerGamesCommands::require))
                        .taking("<player> [player…] — several at once, online or not")
                        .needing(PermissionNodes.GAMEMASTER),

                ModuleCommand.of("init", "Builds the arena for a fresh round",
                                RoundCommand.init(HungerGamesCommands::require))
                        .needing(PermissionNodes.ADMIN),
                ModuleCommand.of("startup", "Tributes down the tubes and onto their platforms",
                                RoundCommand.startup(HungerGamesCommands::require))
                        .needing(PermissionNodes.GAMEMASTER),
                ModuleCommand.of("start", "Runs the countdown, then releases every tribute",
                                RoundCommand.start(HungerGamesCommands::require))
                        // The old plugin's own alias, and it was left behind in the port. Kept because it is
                        // what a gamemaster's fingers know: somebody who has opened three hundred rounds with
                        // /st types it under pressure, and the alternative is reading a red "unknown command"
                        // line instead of watching the countdown they meant to start.
                        .aliased("st")
                        .needing(PermissionNodes.GAMEMASTER));
    }

    /** Called by the module once it has built everything. */
    public static void ready(HungerGamesServices built) {
        services = built;
    }

    /** Called when the module stops, so a command run afterwards is refused rather than half-answered. */
    public static void forget() {
        services = null;
    }

    /**
     * The services, or an exception naming the real problem.
     *
     * <p>Unreachable in practice: the host wraps every command in {@code ModuleCommands.guarded}, which
     * answers with one red line if the module is not running. This is what that guard would otherwise have
     * to catch, and it says something true rather than throwing a {@link NullPointerException} out of a
     * field nobody can see.
     */
    private static HungerGamesServices require() {
        HungerGamesServices built = services;
        if (built == null) {
            throw new IllegalStateException(
                    "the Hunger Games module is not running, so its commands cannot answer");
        }
        return built;
    }
}
