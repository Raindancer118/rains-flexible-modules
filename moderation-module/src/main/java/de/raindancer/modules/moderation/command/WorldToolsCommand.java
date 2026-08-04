package de.raindancer.modules.moderation.command;

import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.Supplier;

/**
 * {@code /worldtools} — the door to the ore and creature tools.
 *
 * <h2>Why it takes no arguments</h2>
 * Because every one of them is a thing a menu asks better. Which ore, how big, what turns up, how many
 * and how far apart are five values, and a command line with five of them is one nobody types
 * correctly twice. The one argument that would matter — <em>where</em> — is not typed at all: it is
 * wherever the moderator is looking, which is what a crosshair is for.
 *
 * <p>So this earns its place under the third clause of what earns a command: nothing else reaches the
 * page.
 *
 * <h2>Why the console is refused</h2>
 * Not a permission decision — the console outranks everybody here. It is that the whole feature is
 * aimed, and the console is not standing anywhere. A console command would need coordinates, a world
 * and a target, which is the five-argument command line this page exists to avoid.
 */
public final class WorldToolsCommand extends StaffCommand {

    public WorldToolsCommand(Supplier<ModerationServices> services) {
        // Guarded by the *lower* of the two nodes, deliberately: a mod holds spawn.ore and an admin
        // holds both, so both reach the page, and the buttons inside it are what tell them apart. The
        // alternative — guarding on spawn.mobs — refuses a mod at the door and hides the ore vein they
        // are allowed to use.
        super(services, ModerationPermission.SPAWN_ORE);
    }

    @Override
    public String describe() {
        return "bury ore, or call up creatures, where you are looking";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player player)) {
            services().messages().send(sender, "moderation.world.players-only");
            return;
        }
        services().screens().worldTools(player);
    }

    /** Nothing to complete: the page asks everything. */
    @Override
    public java.util.Collection<String> suggest(CommandSourceStack source, String[] args) {
        return List.of();
    }
}
