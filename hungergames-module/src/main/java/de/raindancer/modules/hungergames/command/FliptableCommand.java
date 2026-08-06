package de.raindancer.modules.hungergames.command;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.hungergames.HungerGamesServices;
import de.raindancer.modules.hungergames.service.FliptableService;
import de.raindancer.modules.hungergames.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@code /fliptable confirm} — the evening is over; put the server back to nothing.
 *
 * <h2>What it is for</h2>
 * A finished tournament leaves an arena carved into the world, several hundred chests holding a round that
 * already happened, and a session file describing forty people who are done. The next tournament wants none of
 * it. By hand that is: stop the server, delete three folders and four files in the right order, start it
 * again — which is a thing somebody does wrong at eleven at night, once, and then the server does not come
 * back.
 *
 * <h2>The order here is the whole command</h2>
 * <ol>
 *   <li><b>The folders are read first</b>, from {@link World#getWorldFolder()}, while there is still a server
 *       to ask. Deliberately not a name resolved against {@code getWorldContainer()}: Paper 26 puts a world
 *       created after the overworld at {@code <level-name>/dimensions/<namespace>/<name>}, so a name-based
 *       guess silently misses — which is how Core's own farm-world regeneration deleted nothing and reported
 *       success for a fortnight.</li>
 *   <li><b>Arming comes before the kick</b>, so a second {@code /fliptable confirm} is refused by a latch
 *       rather than by whoever typed it having already been thrown off the server.</li>
 *   <li><b>Everybody is kicked</b> with a sentence saying to come back, not with the default "Server closed".
 *       Forty people finding out the tournament is over by being disconnected without explanation is the
 *       version of this that generates the Discord thread.</li>
 *   <li><b>The round is reset</b> — so anything written between here and the shutdown describes an empty
 *       round rather than a finished one.</li>
 *   <li><b>The shutdown is two seconds later</b>, which is the source's own delay: long enough for the kick
 *       packets to leave, short enough that nobody reconnects into a world about to be deleted.</li>
 * </ol>
 *
 * <p>The deleting itself is {@link FliptableService}'s, in a JVM shutdown hook, for a reason written out
 * there: a world folder removed while Paper is running is partly written back as Paper shuts down, and the
 * next start dies on {@code Overworld settings missing}.
 *
 * <h2>Why every loaded world, and not the three the source named</h2>
 * The source deleted {@code world}, {@code world_nether} and {@code world_the_end} by name. That is the same
 * set on a default server and the <em>wrong</em> set on any server whose {@code level-name} is something else
 * — where it deletes nothing at all and reports that the reset is coming. Asking the server which worlds it
 * has is the same answer on the server this runs on and a correct one everywhere else, and it is the only
 * form that survives the dimensions layout above.
 */
public final class FliptableCommand implements IHungerGamesCommand {

    /**
     * Ticks between the command being confirmed and the server going down.
     *
     * <p>The source's two seconds, kept. Shorter and the kick has not reached everybody; longer and it is a
     * window for somebody to reconnect into a world that is about to stop existing.
     */
    public static final long SHUTDOWN_DELAY_TICKS = 40L;

    private final Supplier<HungerGamesServices> services;

    public FliptableCommand(Supplier<HungerGamesServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "deletes the worlds and the round's state, then stops the server — the configuration stays";
    }

    @Override
    public String permission() {
        return PermissionNodes.ADMIN;
    }

    @Override
    public boolean canUse(CommandSender sender) {
        // An admin's, not a gamemaster's — deliberately narrower than the rest of the run-up. A guest
        // gamemaster runs the round; deleting the server the round was on is not part of running it.
        return PermissionNodes.isAdmin(sender);
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        HungerGamesServices hg = services.get();

        if (!canUse(sender)) {
            hg.messages().send(sender, "hungergames.not-allowed");
            return;
        }

        if (!FliptableService.isConfirmed(args)) {
            // Three lines rather than one, because the three things somebody needs are different: what it
            // does, how to say yes, and that there is no undo. A single sentence carrying all three is one
            // people skim.
            hg.messages().send(sender, "hungergames.fliptable-warning");
            hg.messages().send(sender, "hungergames.fliptable-how");
            hg.messages().send(sender, "hungergames.fliptable-final");
            return;
        }

        List<Path> worlds = worldFolders(hg);
        Path dataFolder = hg.plugin().getDataFolder().toPath();

        if (!FliptableService.armFor(worlds, dataFolder, hg.log())) {
            hg.messages().send(sender, "hungergames.fliptable-already");
            return;
        }

        hg.log().warn("(╯°□°)╯︵ ┻━┻ Fliptable initiated by {} — {} world folder(s) and the round's state "
                + "will be gone once the server has shut down.", sender.getName(), worlds.size());
        hg.messages().send(sender, "hungergames.fliptable-armed");

        Component goodbye = hg.messages().get("hungergames.fliptable-kick");
        for (Player player : hg.server().getOnlinePlayers()) {
            player.kick(goodbye);
        }

        hg.session().resetForNextRound();

        Scheduling.globalLater(hg.plugin(), SHUTDOWN_DELAY_TICKS, () -> {
            hg.log().warn("Shutting down for the reset. The worlds are deleted after Paper has closed them.");
            hg.server().shutdown();
        });
    }

    /**
     * Every loaded world's folder, as the running server knows it.
     *
     * <p>Read here and handed on as paths, because after {@code shutdown()} starts there is no {@code World}
     * left to ask and a name is not enough to find the folder again.
     */
    private List<Path> worldFolders(HungerGamesServices hg) {
        return hg.server().getWorlds().stream()
                .map(World::getWorldFolder)
                .map(folder -> folder.toPath().toAbsolutePath().normalize())
                .toList();
    }

    @Override
    public List<String> suggest(CommandSourceStack source, String[] args) {
        // "confirm" is deliberately not offered. The word is the guard, and a guard that tab-completes is a
        // guard somebody passes with two keystrokes without ever reading the warning it exists for — the
        // same rule the farm-world regeneration command follows.
        return List.of();
    }
}
