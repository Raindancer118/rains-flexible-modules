package de.raindancer.modules.hungergames.command;

import de.raindancer.core.platform.command.PlayerLookup;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.modules.hungergames.HungerGamesServices;
import de.raindancer.modules.hungergames.service.AccountNames;
import de.raindancer.modules.hungergames.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@code /allow <player>} — puts somebody on the list of tributes for this tournament.
 *
 * <h2>Why this is typed rather than clicked</h2>
 * Because of <em>when</em> it is used. The list is filled in before the evening starts, from a sign-up sheet
 * or a Discord thread, by somebody working through forty names — and most of those people are not online
 * yet. A player picker can only offer who is connected, which is exactly the wrong set.
 *
 * <p>So this takes a name, nickname or selector, resolves it against everybody the server knows (online or
 * not), and otherwise stores the name as given. A
 * server that will not have a Mojang lookup at 3am when the internet is being difficult still has a
 * tournament to run.
 *
 * <h2>What this command does NOT do, and why that is the headline</h2>
 * <b>It does not grant operator status.</b> The plugin this is ported from opped whoever was let into a
 * round, because that was the quickest way to make the run-up commands work — and everybody who ever played
 * on that server was an operator afterwards. Every one of them could have edited the world, banned each
 * other, or turned the server off.
 *
 * <p>The three permission nodes in {@code PermissionNodes} exist precisely so the run-up needs none of that.
 * Being a tribute is being on a list; it confers nothing.
 */
public final class AllowCommand implements IHungerGamesCommand {

    private final Supplier<HungerGamesServices> services;

    public AllowCommand(Supplier<HungerGamesServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "puts somebody on the list of tributes — and grants them nothing else at all";
    }

    @Override
    public String permission() {
        return PermissionNodes.GAMEMASTER;
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return PermissionNodes.mayOpenTheAdminSuite(sender);
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        HungerGamesServices hg = services.get();

        if (!canUse(sender)) {
            hg.messages().send(sender, "hungergames.not-allowed");
            return;
        }
        if (args.length == 0) {
            hg.messages().send(sender, "hungergames.allow-usage");
            return;
        }

        List<String> added = new ArrayList<>();
        List<String> already = new ArrayList<>();

        // Every name on the line, not just the first. Somebody working through a sign-up sheet pastes them
        // in batches, and a command that took one per invocation made that forty commands.
        for (String typed : args) {
            PlayerLookup found = PlayerTargets.lookup(hg.server(), sender, typed);
            if (found.kind() == PlayerLookup.Kind.SELECTOR_REFUSED) {
                hg.messages().send(sender, "hungergames.allow-selector-refused", "who", typed);
                continue;
            }
            if (found.isEmpty() && found.kind() == PlayerLookup.Kind.SELECTOR) {
                hg.messages().send(sender, "hungergames.allow-selector-empty", "who", typed);
                continue;
            }
            if (found.isEmpty()) {
                // Somebody not seen on this server yet, named as given — see the class note.
                // Not a Mojang lookup: run before an event, in bulk, possibly offline, that would freeze the server.
                record(hg, AccountNames.derivedId(typed), typed, added, already);
                continue;
            }
            // Whoever the text meant — by selector, name or nickname, here or not — goes on the list as
            // that player: their UUID and their real name. A nickname only points at somebody; storing it
            // would let whoever later registers under that spelling walk in on somebody else's slot.
            for (OfflinePlayer who : found.matches()) {
                String real = who.getName() != null ? who.getName()
                        : found.kind() == PlayerLookup.Kind.NAME ? typed : who.getUniqueId().toString();
                record(hg, who.getUniqueId(), real, added, already);
            }
        }

        if (!added.isEmpty()) {
            hg.messages().send(sender, "hungergames.allowed",
                    "players", String.join(", ", added),
                    "total", String.valueOf(hg.session().participants().all().size()));
            hg.log().info("{} added {} tribute(s): {}", sender.getName(), added.size(),
                    String.join(", ", added));
        }
        if (!already.isEmpty()) {
            // Named rather than counted. Somebody pasting a list needs to know *which* were already there,
            // because the usual reason is that they pasted the same block twice.
            hg.messages().send(sender, "hungergames.allowed-already",
                    "players", String.join(", ", already));
        }
    }

    private static void record(HungerGamesServices hg, UUID uuid, String name, List<String> added,
                               List<String> already) {
        if (hg.session().whitelistAdd(uuid, name)) {
            added.add(name);
        } else {
            already.add(name);
        }
    }

    @Override
    public List<String> suggest(CommandSourceStack source, String[] args) {
        // Whoever is online and is not a tribute yet. Only a help — the whole point of this command is that
        // it accepts names that are not in that list.
        HungerGamesServices hg = services.get();
        String typed = args.length == 0 ? "" : args[args.length - 1];

        return PlayerTargets.suggest(hg.server(), source.getSender(), typed,
                        player -> !hg.session().isWhitelisted(player.getUniqueId()))
                .stream()
                .sorted()
                .toList();
    }
}
