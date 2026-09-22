package de.raindancer.modules.chat.command;

import de.raindancer.modules.chat.ChatServices;
import de.raindancer.modules.chat.model.PrivateChat;
import de.raindancer.modules.chat.service.PrivateChatService;
import de.raindancer.modules.chat.service.PrivateChatService.Outcome;
import de.raindancer.modules.chat.util.PrivateChatNotices;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /chat private [add|remove|leave|end|list]} and {@code /chat public} — the half of
 * {@link ChatCommand} every player may use.
 *
 * <p>Kept apart from the staff tools because it has its own vocabulary and its own rules, and a
 * {@code switch} holding both reads as though freezing chat and leaving a conversation had something
 * to do with each other. See {@link PrivateChatService} for what the rules are and why.
 */
final class PrivateChatSubcommand {

    static final List<String> ACTIONS = List.of("add", "remove", "leave", "end", "list");

    private PrivateChatSubcommand() {
    }

    /** {@code /chat private ...}; {@code args[0]} is {@code private}. */
    static void privately(ChatServices live, Player sender, String[] args) {
        PrivateChatService chats = live.privateChat();
        if (args.length < 2) {
            switch (chats.goPrivate(sender.getUniqueId())) {
                case STARTED -> live.messages().send(sender, "chat.private.started");
                case SWITCHED -> live.messages().send(sender, "chat.private.switched");
                default -> live.messages().send(sender, "chat.private.already-private");
            }
            return;
        }
        String[] names = Arrays.copyOfRange(args, 2, args.length);
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "add", "invite" -> add(live, sender, names);
            case "remove", "kick" -> remove(live, sender, names);
            case "leave" -> leave(live, sender);
            case "end", "close" -> end(live, sender);
            case "list", "who" -> list(live, sender);
            default -> live.messages().send(sender, "chat.usage", "usage",
                    "/chat private [add|remove <player>|leave|end|list]");
        }
    }

    /** {@code /chat public} — talk in public again, while still reading the private chat. */
    static void publicly(ChatServices live, Player sender) {
        live.messages().send(sender, live.privateChat().goPublic(sender.getUniqueId())
                ? "chat.private.public" : "chat.private.already-public");
    }

    private static void add(ChatServices live, Player sender, String[] names) {
        if (names.length == 0) {
            live.messages().send(sender, "chat.usage", "usage", "/chat private add <player> [player...]");
            return;
        }
        for (String name : names) {
            Optional<Player> found = live.mentions().visibleNamed(sender, name);
            if (found.isEmpty()) {
                live.messages().send(sender, "chat.not-online", "player", name);
                continue;
            }
            Player target = found.get();
            Outcome outcome = live.privateChat().add(sender.getUniqueId(), target.getUniqueId());
            if (outcome == Outcome.ADDED) {
                live.messages().send(target, "chat.private.you-were-added", "owner", sender.getName());
                PrivateChatNotices.tell(live, live.privateChat().readersOf(sender.getUniqueId()),
                        target.getUniqueId(), "chat.private.added", "player", target.getName());
            } else {
                refuse(live, sender, outcome, target.getName());
            }
        }
    }

    private static void remove(ChatServices live, Player sender, String[] names) {
        if (names.length == 0) {
            live.messages().send(sender, "chat.usage", "usage", "/chat private remove <player>");
            return;
        }
        for (String name : names) {
            Optional<UUID> member = memberNamed(live, sender, name);
            if (member.isEmpty()) {
                refuse(live, sender, live.privateChat().chatOf(sender.getUniqueId()).isEmpty()
                        ? Outcome.NOT_IN_A_CHAT : Outcome.NOT_A_MEMBER, name);
                continue;
            }
            Outcome outcome = live.privateChat().remove(sender.getUniqueId(), member.get());
            if (outcome != Outcome.REMOVED) {
                refuse(live, sender, outcome, name);
                continue;
            }
            Player removed = live.server().getPlayer(member.get());
            if (removed != null) {
                live.messages().send(removed, "chat.private.you-were-removed", "owner", sender.getName());
            }
            PrivateChatNotices.tell(live, live.privateChat().readersOf(sender.getUniqueId()), null,
                    "chat.private.removed", "player", name);
        }
    }

    private static void leave(ChatServices live, Player sender) {
        Optional<PrivateChat> before = live.privateChat().chatOf(sender.getUniqueId());
        Outcome outcome = live.privateChat().leave(sender.getUniqueId());
        switch (outcome) {
            case LEFT -> {
                live.messages().send(sender, "chat.private.you-left");
                PrivateChatNotices.tell(live, before.orElseThrow().members(), sender.getUniqueId(),
                        "chat.private.left", "player", sender.getName());
            }
            case ENDED -> announceEnded(live, sender, before.orElseThrow());
            default -> refuse(live, sender, outcome, sender.getName());
        }
    }

    private static void end(ChatServices live, Player sender) {
        Optional<PrivateChat> chat = live.privateChat().chatOf(sender.getUniqueId());
        if (chat.isEmpty()) {
            refuse(live, sender, Outcome.NOT_IN_A_CHAT, sender.getName());
            return;
        }
        if (!chat.get().isOwner(sender.getUniqueId())) {
            refuse(live, sender, Outcome.NOT_THE_OWNER, sender.getName());
            return;
        }
        live.privateChat().end(sender.getUniqueId()).ifPresent(ended -> announceEnded(live, sender, ended));
    }

    private static void announceEnded(ChatServices live, Player owner, PrivateChat ended) {
        PrivateChatNotices.tell(live, ended.members(), owner.getUniqueId(),
                "chat.private.ended", "player", owner.getName());
        live.messages().send(owner, "chat.private.ended", "player", owner.getName());
    }

    private static void list(ChatServices live, Player sender) {
        Optional<PrivateChat> chat = live.privateChat().chatOf(sender.getUniqueId());
        if (chat.isEmpty()) {
            refuse(live, sender, Outcome.NOT_IN_A_CHAT, sender.getName());
            return;
        }
        List<String> names = new ArrayList<>();
        for (UUID member : chat.get().members()) {
            names.add(nameOf(live, member));
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        live.messages().send(sender, "chat.private.members", "owner", nameOf(live, chat.get().owner()),
                "count", String.valueOf(names.size()), "members", String.join(", ", names));
    }

    /** Suggestions after {@code /chat private}. */
    static List<String> suggest(ChatServices live, Player sender, String[] args) {
        if (args.length == 2) {
            String typed = args[1].toLowerCase(Locale.ROOT);
            return ACTIONS.stream().filter(action -> action.startsWith(typed)).toList();
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        String typed = args[args.length - 1];
        if (action.equals("add") || action.equals("invite")) {
            return live.mentions().namesVisibleTo(sender, typed);
        }
        if (action.equals("remove") || action.equals("kick")) {
            List<String> members = new ArrayList<>();
            for (UUID member : live.privateChat().readersOf(sender.getUniqueId())) {
                String name = nameOf(live, member);
                if (!member.equals(sender.getUniqueId())
                        && name.toLowerCase(Locale.ROOT).startsWith(typed.toLowerCase(Locale.ROOT))) {
                    members.add(name);
                }
            }
            return members;
        }
        return List.of();
    }

    private static Optional<UUID> memberNamed(ChatServices live, Player sender, String name) {
        for (UUID member : live.privateChat().readersOf(sender.getUniqueId())) {
            if (nameOf(live, member).equalsIgnoreCase(name)) {
                return Optional.of(member);
            }
        }
        return Optional.empty();
    }

    /** A member's name — every member is online, since going offline takes them out. */
    private static String nameOf(ChatServices live, UUID who) {
        Player online = live.server().getPlayer(who);
        if (online != null) {
            return online.getName();
        }
        OfflinePlayer offline = live.server().getOfflinePlayer(who);
        return offline == null || offline.getName() == null ? who.toString() : offline.getName();
    }

    private static void refuse(ChatServices live, Player sender, Outcome outcome, String name) {
        String owner = live.privateChat().chatOf(sender.getUniqueId())
                .map(chat -> nameOf(live, chat.owner())).orElse(sender.getName());
        switch (outcome) {
            case ALREADY_A_MEMBER -> live.messages().send(sender, "chat.private.already-a-member", "player", name);
            case IN_ANOTHER_CHAT -> live.messages().send(sender, "chat.private.in-another-chat", "player", name);
            case LEFT_THIS_CHAT -> live.messages().send(sender, "chat.private.left-this-chat", "player", name);
            case NOT_YOURSELF -> live.messages().send(sender, "chat.private.not-yourself");
            case NOT_THE_OWNER -> live.messages().send(sender, "chat.private.not-the-owner", "owner", owner);
            case NOT_A_MEMBER -> live.messages().send(sender, "chat.private.not-a-member", "player", name);
            case NOT_IN_A_CHAT -> live.messages().send(sender, "chat.private.not-in-a-chat");
            default -> {
                // Every success is answered where it happens; nothing else reaches here.
            }
        }
    }
}
