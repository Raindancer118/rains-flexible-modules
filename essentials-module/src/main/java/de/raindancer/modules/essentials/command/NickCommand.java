package de.raindancer.modules.essentials.command;

import de.raindancer.core.moderation.audit.AuditEntry;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.modules.essentials.EssentialsServices;
import de.raindancer.modules.essentials.screen.BlocklistMenu;
import de.raindancer.modules.essentials.screen.NickMenu;
import de.raindancer.modules.essentials.util.PermissionNodes;
import de.raindancer.modules.essentials.util.Players;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /nick set <name>} — sets a nickname; {@code /nick clear} (or the older {@code /nick off})
 * takes it back off; {@code /nick blocklist} opens the blocklist editor, for whoever may manage it.
 * {@code /nick <name>} directly still works too, for whoever is used to it. Bare {@code /nick} opens
 * {@link NickMenu} instead of clearing anything — the picker for somebody who wants to look at what
 * they are called before deciding, the same shape {@code /invsnap} takes when it is not given a name
 * either.
 *
 * <h2>Somebody else's</h2>
 * Whoever holds {@code essentials.nick.others} may also type {@code /nick <player> <nickname…>} and
 * {@code /nick <player> clear}, for somebody offline as well; the change is stored and applied when they
 * next join. It is that and not a self-nickname when the first word names a player the server knows
 * (by name or by nickname), the sender may do it, and there is something after it — so an admin who
 * wants a nickname that begins with somebody's name says {@code /nick set <name>}.
 *
 * <h2>Why the editor lives under here rather than its own command</h2>
 * A player never opens it, and a player typing {@code /nick} already knows this is where nicknames
 * are decided — so the one door staff need is a subcommand of the one they already know, rather
 * than one more top-level command to remember and to guard against colliding with a nickname
 * somebody genuinely wants to be called "blocklist".
 */
public final class NickCommand implements IEssentialsCommand {

    private static final List<String> WORDS = List.of("set", "clear", "off");

    private final Supplier<EssentialsServices> services;

    public NickCommand(Supplier<EssentialsServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "sets, or removes, what you are called instead of your own name — or, for staff, somebody else's";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        EssentialsServices live = services.get();
        CommandSender sender = source.getSender();
        Optional<OfflinePlayer> other = otherPlayer(live, sender, args);
        if (other.isPresent()) {
            forSomebodyElse(live, sender, other.get(), Arrays.copyOfRange(args, 1, args.length));
            return;
        }
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
            live.nicknames().set(who, who, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
            return;
        }
        live.nicknames().set(who, who, String.join(" ", args));
    }

    /** The player the first word names, when this is the admin form of the command rather than a nickname. */
    private static Optional<OfflinePlayer> otherPlayer(EssentialsServices live, CommandSender sender, String[] args) {
        if (args.length < 2 || !sender.hasPermission(PermissionNodes.NICK_OTHERS)
                || WORDS.contains(args[0].toLowerCase(Locale.ROOT))
                || args[0].equalsIgnoreCase("blocklist")) {
            return Optional.empty();
        }
        return PlayerTargets.find(live.server(), args[0])
                .filter(found -> !(sender instanceof Player who) || !who.getUniqueId().equals(found.getUniqueId()));
    }

    private void forSomebodyElse(EssentialsServices live, CommandSender sender, OfflinePlayer target, String[] rest) {
        if (!live.nicknames().isEnabled()) {
            live.messages().send(sender, "essentials.nick.switched-off");
            return;
        }
        if (rest.length == 1 && (rest[0].equalsIgnoreCase("clear") || rest[0].equalsIgnoreCase("off"))) {
            live.nicknames().clear(sender, target);
            return;
        }
        String[] words = rest.length > 1 && rest[0].equalsIgnoreCase("set")
                ? Arrays.copyOfRange(rest, 1, rest.length) : rest;
        live.nicknames().set(sender, target, String.join(" ", words));
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
        EssentialsServices live = services.get();
        CommandSender sender = source.getSender();
        boolean admin = sender.hasPermission(PermissionNodes.NICK_OTHERS);
        if (args.length == 2 && admin && PlayerTargets.find(live.server(), args[0]).isPresent()) {
            return WORDS.stream().filter(word -> word.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length != 1) {
            return List.of();
        }
        String typed = args[0].toLowerCase(Locale.ROOT);
        List<String> suggestions = new ArrayList<>(WORDS);
        if (sender.hasPermission(PermissionNodes.BLOCKLIST_MANAGE)) {
            suggestions.add("blocklist");
        }
        List<String> matching = new ArrayList<>(suggestions.stream()
                .filter(candidate -> candidate.startsWith(typed)).toList());
        if (admin) {
            matching.addAll(Players.suggestions(live.server(), args[0], live.core().vanish(),
                    sender instanceof Player viewer ? viewer.getUniqueId() : null));
        }
        return matching;
    }

    @Override
    public String permission() {
        return PermissionNodes.NICK;
    }
}
