package de.raindancer.modules.essentials.command;

import de.raindancer.core.moderation.audit.AuditEntry;
import de.raindancer.modules.essentials.EssentialsServices;
import de.raindancer.modules.essentials.model.Nickname;
import de.raindancer.modules.essentials.screen.BlocklistMenu;
import de.raindancer.modules.essentials.screen.NickMenu;
import de.raindancer.modules.essentials.util.PermissionNodes;
import de.raindancer.modules.essentials.util.Players;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@code /nick set <name>} — sets a nickname; {@code /nick clear} (or the older {@code /nick off})
 * takes it back off; {@code /nick blocklist} opens the blocklist editor, for whoever may manage it.
 * {@code /nick <name>} directly still works too, for whoever is used to it. Bare {@code /nick} opens
 * {@link NickMenu} instead of clearing anything — the picker for somebody who wants to look at what
 * they are called before deciding, the same shape {@code /invsnap} takes when it is not given a name
 * either.
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
        if (args.length == 0) {
            new NickMenu(live, who, null).open();
            return;
        }
        if (!live.nicknames().isEnabled()) {
            live.messages().send(who, "essentials.nick.switched-off");
            return;
        }
        if (args[0].equalsIgnoreCase("off") || args[0].equalsIgnoreCase("clear")) {
            live.nicknames().clear(who);
            return;
        }
        if (args[0].equalsIgnoreCase("set")) {
            if (args.length < 2) {
                live.messages().send(who, "essentials.usage", "usage", "/nick set <name>");
                return;
            }
            setNickname(live, who, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
            return;
        }
        setNickname(live, who, String.join(" ", args));
    }

    private void setNickname(EssentialsServices live, Player who, String typed) {
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
        String typed = args[0].toLowerCase(java.util.Locale.ROOT);
        List<String> suggestions = new ArrayList<>(List.of("set", "clear", "off"));
        if (source.getSender() instanceof Player who
                && who.hasPermission(PermissionNodes.BLOCKLIST_MANAGE)) {
            suggestions.add("blocklist");
        }
        return suggestions.stream().filter(candidate -> candidate.startsWith(typed)).toList();
    }

    @Override
    public String permission() {
        return PermissionNodes.NICK;
    }
}
