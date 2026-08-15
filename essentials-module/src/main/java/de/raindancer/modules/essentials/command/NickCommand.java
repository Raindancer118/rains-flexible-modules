package de.raindancer.modules.essentials.command;

import de.raindancer.core.moderation.audit.AuditEntry;
import de.raindancer.modules.essentials.EssentialsServices;
import de.raindancer.modules.essentials.model.Nickname;
import de.raindancer.modules.essentials.screen.BlocklistMenu;
import de.raindancer.modules.essentials.util.PermissionNodes;
import de.raindancer.modules.essentials.util.Players;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@code /nick [name]} — sets a nickname; {@code /nick off} takes it back off;
 * {@code /nick blocklist} opens the blocklist editor, for whoever may manage it.
 *
 * <h2>Why the editor lives under here rather than its own command</h2>
 * A player never opens it, and a player typing {@code /nick} already knows this is where nicknames
 * are decided — so the one door staff need is a subcommand of the one they already know, rather
 * than one more top-level command to remember and to guard against colliding with a nickname
 * somebody genuinely wants to be called "blocklist".
 */
public final class NickCommand implements IEssentialsCommand {

    private final Supplier<EssentialsServices> services;

    public NickCommand(Supplier<EssentialsServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "sets, or removes, what you are called instead of your own name";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        EssentialsServices live = services.get();
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player who)) {
            live.messages().send(sender, "essentials.only-a-player");
            return;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("blocklist")) {
            openBlocklist(live, who);
            return;
        }
        if (!live.nicknames().isEnabled()) {
            live.messages().send(who, "essentials.nick.switched-off");
            return;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("off")) {
            live.nicknames().clear(who);
            return;
        }
        String typed = String.join(" ", args);
        String plain = Nickname.of(typed).plain();
        boolean nameInUse = Players.realNameInUse(live.server(), plain)
                && !plain.equalsIgnoreCase(who.getName());
        live.nicknames().set(who, typed, nameInUse);
    }

    private void openBlocklist(EssentialsServices live, Player who) {
        if (!who.hasPermission(PermissionNodes.BLOCKLIST_MANAGE)) {
            live.messages().send(who, "essentials.no-permission");
            return;
        }
        live.core().audit().record(AuditEntry.of("essentials", "opened the nickname blocklist editor")
                .by(who.getUniqueId(), who.getName()));
        new BlocklistMenu(live, who, null).open();
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        if (source.getSender() instanceof Player who
                && who.hasPermission(PermissionNodes.BLOCKLIST_MANAGE)
                && "blocklist".startsWith(args[0].toLowerCase(java.util.Locale.ROOT))) {
            return List.of("blocklist");
        }
        return List.of();
    }

    @Override
    public String permission() {
        return PermissionNodes.NICK;
    }
}
