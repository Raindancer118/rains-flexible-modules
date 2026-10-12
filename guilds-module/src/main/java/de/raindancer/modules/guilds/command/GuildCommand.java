package de.raindancer.modules.guilds.command;

import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.social.team.TeamId;
import de.raindancer.modules.guilds.GuildsServices;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.GuildOutcome;
import de.raindancer.modules.guilds.model.Invitation;
import de.raindancer.modules.guilds.model.JoinMode;
import de.raindancer.modules.guilds.model.Rank;
import de.raindancer.modules.guilds.model.Relation;
import de.raindancer.modules.guilds.screen.BrowseMenu;
import de.raindancer.modules.guilds.screen.GuildMenu;
import de.raindancer.modules.guilds.service.GuildChatService;
import de.raindancer.modules.guilds.service.GuildNotifier;
import de.raindancer.modules.guilds.util.PermissionNodes;
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
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@code /guild} — the menu with no arguments; every subcommand is something faster typed than clicked, or that
 * takes an argument a menu cannot ask for. {@code /guild admin …} is staff's.
 */
public final class GuildCommand implements IGuildsCommand {

    static final List<String> SUBCOMMANDS = List.of("create", "info", "list", "invite", "accept", "decline", "join",
            "leave", "kick", "promote", "demote", "rank", "leader", "chat", "c", "officer", "oc", "ally-chat", "ac",
            "home", "sethome", "deposit", "withdraw", "ally", "unally", "rival", "unrival", "rename", "tag", "style",
            "motd", "joining", "disband");
    static final List<String> STAFF = List.of("admin");

    private final Supplier<GuildsServices> services;

    public GuildCommand(Supplier<GuildsServices> services) {
        this.services = services;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        GuildsServices live = services.get();
        CommandSender sender = source.getSender();
        String first = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (first.equals("admin")) {
            admin(live, sender, Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "guilds.only-a-player");
            return;
        }
        UUID me = player.getUniqueId();
        String rest = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "";
        switch (first) {
            case "" -> GuildMenu.forViewer(live, player).open();
            case "create", "found" -> {
                if (!player.hasPermission(PermissionNodes.FOUND)) {
                    live.messages().send(player, GuildOutcome.NOT_ALLOWED.messageKey());
                } else if (args.length < 3) {
                    live.messages().send(player, "guilds.usage.found");
                } else {
                    String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length - 1));
                    say(live, player, live.guilds().found(me, name, args[args.length - 1]));
                }
            }
            case "info" -> info(live, player, rest);
            case "list", "top" -> new BrowseMenu(live, player, null).open();
            case "invite" -> withPlayer(live, player, rest, target -> say(live, player, live.guilds().invite(me, target)));
            case "accept" -> withInvitation(live, player, rest, true);
            case "decline", "deny" -> withInvitation(live, player, rest, false);
            case "join", "apply" -> withGuild(live, player, rest, guild -> say(live, player, live.guilds().request(me, guild.id())));
            case "leave" -> say(live, player, live.guilds().leave(me, live.bank()));
            case "kick" -> withPlayer(live, player, rest, target -> say(live, player, live.guilds().kick(me, target)));
            case "promote" -> withPlayer(live, player, rest, target -> say(live, player, live.guilds().step(me, target, true)));
            case "demote" -> withPlayer(live, player, rest, target -> say(live, player, live.guilds().step(me, target, false)));
            case "rank" -> {
                if (args.length < 3) {
                    live.messages().send(player, "guilds.usage.rank");
                    return;
                }
                String typedRank = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                withPlayer(live, player, args[1], target -> say(live, player, live.guilds().guildOf(me)
                        .flatMap(guild -> rankNamed(guild, typedRank))
                        .map(rank -> live.guilds().setRank(me, target, rank.id())).orElse(GuildOutcome.NO_SUCH_RANK)));
            }
            case "leader" -> withPlayer(live, player, rest, target -> say(live, player, live.guilds().handOver(me, target)));
            case "chat", "c" -> chat(live, player, GuildChatService.Kind.GUILD, rest);
            case "officer", "oc" -> chat(live, player, GuildChatService.Kind.OFFICER, rest);
            case "ally-chat", "ac" -> chat(live, player, GuildChatService.Kind.ALLY, rest);
            case "home" -> say(live, player, live.homes().go(player));
            case "sethome" -> say(live, player, live.homes().set(player));
            case "deposit" -> say(live, player, live.bank().parse(rest).map(amount -> live.bank().deposit(me, amount))
                    .orElse(GuildOutcome.INVALID_AMOUNT));
            case "withdraw" -> say(live, player, live.bank().parse(rest).map(amount -> live.bank().withdraw(me, amount))
                    .orElse(GuildOutcome.INVALID_AMOUNT));
            case "ally" -> withGuild(live, player, rest, guild -> say(live, player,
                    live.guilds().guildOf(me).filter(own -> live.relations().alliancesAskedOf(own).stream()
                                    .anyMatch(asked -> asked.guild().equals(guild.id().value())))
                            .map(own -> live.relations().acceptAlliance(me, guild.id()))
                            .orElseGet(() -> live.relations().askAlliance(me, guild.id()))));
            case "unally" -> withGuild(live, player, rest, guild -> say(live, player, live.relations().endAlliance(me, guild.id())));
            case "rival", "enemy" -> withGuild(live, player, rest, guild -> say(live, player, live.relations().declareRival(me, guild.id())));
            case "unrival" -> withGuild(live, player, rest, guild -> say(live, player, live.relations().endRivalry(me, guild.id())));
            case "rename" -> say(live, player, live.guilds().rename(me, rest));
            case "tag" -> say(live, player, live.guilds().retag(me, rest));
            case "style" -> style(live, player, args);
            case "motd" -> say(live, player, live.guilds().motd(me, rest));
            case "joining" -> {
                Optional<JoinMode> mode = JoinMode.named(rest);
                if (mode.isEmpty()) {
                    live.messages().send(player, "guilds.usage.joining");
                } else {
                    say(live, player, live.guilds().joinMode(me, mode.get()));
                }
            }
            case "disband" -> {
                Optional<Guild> own = live.guilds().guildOf(me);
                if (own.isEmpty()) {
                    say(live, player, GuildOutcome.NO_GUILD);
                } else if (!rest.equalsIgnoreCase("confirm")) {
                    live.messages().send(player, "guilds.disband-confirm", "guild", GuildNotifier.title(own.get()));
                } else {
                    say(live, player, live.guilds().disband(me, own.get().id(), false, live.bank()));
                }
            }
            default -> live.messages().send(player, "guilds.usage.all");
        }
    }

    /** {@code /guild style tag|name <colours… bold…> | reset}. */
    private static void style(GuildsServices live, Player player, String[] args) {
        if (args.length < 3 || !(args[1].equalsIgnoreCase("tag") || args[1].equalsIgnoreCase("name"))) {
            live.messages().send(player, "guilds.usage.style", "level", live.guilds().styleFromLevel());
            return;
        }
        boolean tag = args[1].equalsIgnoreCase("tag");
        List<String> words = Arrays.asList(Arrays.copyOfRange(args, 2, args.length));
        if (words.size() == 1 && words.getFirst().equalsIgnoreCase("reset")) {
            say(live, player, live.guilds().style(player.getUniqueId(), tag, de.raindancer.core.ui.text.NameStyle.NONE));
            return;
        }
        de.raindancer.core.ui.text.TypedStyles.Read read = de.raindancer.core.ui.text.TypedStyles.read(words, word -> null);
        if (read.style().isEmpty()) {
            live.messages().send(player, "guilds.style-unreadable", "problem", read.problem());
            return;
        }
        GuildOutcome outcome = live.guilds().style(player.getUniqueId(), tag, read.style().get());
        if (outcome == GuildOutcome.STYLE_LOCKED) {
            live.messages().send(player, "guilds.style-locked", "level", live.guilds().styleFromLevel());
            return;
        }
        say(live, player, outcome);
    }

    private static void say(GuildsServices live, CommandSender sender, GuildOutcome outcome) {
        live.messages().send(sender, outcome.messageKey());
    }

    private static Optional<Rank> rankNamed(Guild guild, String typed) {
        return guild.ranks().stream().filter(rank -> rank.name().equalsIgnoreCase(typed.strip())).findFirst()
                .or(() -> guild.rank(typed.strip().toLowerCase(Locale.ROOT)));
    }

    private static void withPlayer(GuildsServices live, Player player, String typed, java.util.function.Consumer<UUID> then) {
        if (typed.isBlank()) {
            live.messages().send(player, "guilds.usage.player");
            return;
        }
        Optional<OfflinePlayer> found = PlayerTargets.find(live.server(), player, typed.strip());
        if (found.isEmpty()) {
            live.messages().send(player, "guilds.never-here", "player", typed.strip());
            return;
        }
        then.accept(found.get().getUniqueId());
    }

    private static void withGuild(GuildsServices live, CommandSender sender, String typed, java.util.function.Consumer<Guild> then) {
        Optional<Guild> found = live.book().find(typed);
        if (found.isEmpty()) {
            live.messages().send(sender, "guilds.no-such-guild", "guild", typed.strip());
            return;
        }
        then.accept(found.get());
    }

    private static void withInvitation(GuildsServices live, Player player, String typed, boolean accept) {
        List<Invitation> waiting = live.guilds().invitationsOf(player.getUniqueId());
        Optional<TeamId> chosen;
        if (typed.isBlank()) {
            if (waiting.size() != 1) {
                live.messages().send(player, waiting.isEmpty() ? GuildOutcome.NOT_INVITED.messageKey() : "guilds.usage.which-invitation",
                        "count", waiting.size());
                return;
            }
            chosen = Optional.of(new TeamId(waiting.getFirst().guild()));
        } else {
            chosen = live.book().find(typed).map(Guild::id);
        }
        if (chosen.isEmpty()) {
            live.messages().send(player, "guilds.no-such-guild", "guild", typed.strip());
            return;
        }
        say(live, player, accept ? live.guilds().acceptInvitation(player.getUniqueId(), chosen.get())
                : live.guilds().declineInvitation(player.getUniqueId(), chosen.get()));
    }

    private static void chat(GuildsServices live, Player player, GuildChatService.Kind kind, String text) {
        if (text.isBlank()) {
            live.messages().send(player, "guilds.usage.chat");
            return;
        }
        if (!live.chat().say(kind, player, text)) {
            say(live, player, live.guilds().guildOf(player.getUniqueId()).isEmpty() ? GuildOutcome.NO_GUILD : GuildOutcome.NOT_ALLOWED);
        }
    }

    private static void info(GuildsServices live, Player player, String typed) {
        Optional<Guild> found = typed.isBlank() ? live.guilds().guildOf(player.getUniqueId()) : live.book().find(typed);
        if (found.isEmpty()) {
            live.messages().send(player, typed.isBlank() ? GuildOutcome.NO_GUILD.messageKey() : "guilds.no-such-guild",
                    "guild", typed.strip());
            return;
        }
        Guild guild = found.get();
        long allies = guild.relations().values().stream().filter(relation -> relation == Relation.ALLY).count();
        live.messages().send(player, "guilds.info", "guild", GuildNotifier.title(guild), "tag", GuildNotifier.tag(guild),
                "level", live.guilds().level(guild), "members", guild.size(), "cap", live.guilds().memberCap(guild),
                "leader", live.notifier().name(guild.leader()), "allies", allies, "joining", guild.joinMode().title());
    }

    // ------------------------------------------------------------------ staff

    private static void admin(GuildsServices live, CommandSender sender, String[] args) {
        if (!sender.hasPermission(PermissionNodes.ADMIN)) {
            live.messages().send(sender, GuildOutcome.NOT_ALLOWED.messageKey());
            return;
        }
        UUID staff = sender instanceof Player player ? player.getUniqueId() : new UUID(0, 0);
        String what = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (args.length < 2) {
            live.messages().send(sender, "guilds.usage.admin");
            return;
        }
        Optional<Guild> guild = live.book().find(args[1]);
        if (guild.isEmpty()) {
            live.messages().send(sender, "guilds.no-such-guild", "guild", args[1]);
            return;
        }
        TeamId id = guild.get().id();
        switch (what) {
            case "disband" -> {
                if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
                    live.messages().send(sender, "guilds.disband-confirm", "guild", GuildNotifier.title(guild.get()));
                } else {
                    say(live, sender, live.guilds().disband(staff, id, true, live.bank()));
                }
            }
            case "rename" -> say(live, sender, args.length < 3 ? GuildOutcome.NAME_TOO_SHORT
                    : live.guilds().staffRename(staff, id, String.join(" ", Arrays.copyOfRange(args, 2, args.length))));
            case "xp" -> {
                try {
                    say(live, sender, live.guilds().staffSetXp(staff, id, Long.parseLong(args[2])));
                } catch (RuntimeException notANumber) {
                    say(live, sender, GuildOutcome.INVALID_AMOUNT);
                }
            }
            case "leader" -> {
                Optional<OfflinePlayer> heir = args.length < 3 ? Optional.empty() : PlayerTargets.find(live.server(), sender, args[2]);
                say(live, sender, heir.map(found -> live.guilds().staffLeader(staff, id, found.getUniqueId()))
                        .orElse(GuildOutcome.NOT_A_MEMBER));
            }
            default -> live.messages().send(sender, "guilds.usage.admin");
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        CommandSender sender = source.getSender();
        GuildsServices live;
        try {
            live = services.get();
        } catch (IllegalStateException notRunning) {
            return List.of();
        }
        if (args.length <= 1) {
            options.addAll(SUBCOMMANDS);
            if (sender.hasPermission(PermissionNodes.ADMIN)) {
                options.addAll(STAFF);
            }
        } else if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "invite", "kick", "promote", "demote", "rank", "leader" ->
                        live.server().getOnlinePlayers().forEach(player -> options.add(player.getName()));
                case "join", "apply", "info", "ally", "unally", "rival", "unrival", "accept", "decline" ->
                        live.book().all().forEach(guild -> options.add(guild.tag()));
                case "joining" -> Arrays.stream(JoinMode.values()).forEach(mode -> options.add(mode.key()));
                case "admin" -> options.addAll(List.of("disband", "rename", "xp", "leader"));
                default -> {
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("admin")) {
            live.book().all().forEach(guild -> options.add(guild.tag()));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("rank") && sender instanceof Player player) {
            live.guilds().guildOf(player.getUniqueId()).ifPresent(guild -> guild.ranks().forEach(rank -> options.add(rank.id())));
        }
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
    }

    @Override
    public String permission() {
        return PermissionNodes.USE;
    }
}
