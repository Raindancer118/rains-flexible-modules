package de.raindancer.modules.moderation.command;

import de.raindancer.core.data.settings.SettingsMenu;
import de.raindancer.core.moderation.punishment.Punishment;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.Standing;
import de.raindancer.modules.moderation.rules.StandingRule;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.util.Players;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /mod} — the way in.
 *
 * <h2>What it is for</h2>
 * The question a moderator actually has is "what is going on with this person", and answering it from
 * six commands means remembering six. {@code /mod <player>} opens everything about them; bare
 * {@code /mod} opens the list of everybody, because the person being looked up is usually offline and
 * their name is the one thing nobody remembers correctly.
 *
 * <h2>What earns a subcommand here</h2>
 * The same rule as everywhere in this repository: typing it is faster than clicking, or it takes an
 * argument a menu cannot ask for. {@code note} earns its place — a sentence is the argument a menu is
 * worst at — and {@code reports} and {@code staff} earn theirs because they are the two pages somebody
 * opens without having a player in mind. Nothing else does; every other action is a click on a page.
 *
 * <h2>Its permission</h2>
 * {@code HISTORY}, rather than a node of its own. Everything this opens either shows a record or leads
 * to a page that does, so a second node would be one an owner had to grant alongside it every time — and
 * the individual buttons are each guarded by their own permission anyway.
 */
public final class ModerationCommand extends StaffCommand {

    private static final List<String> SUBCOMMANDS =
            List.of("info", "config", "reports", "staff", "note", "notes", "xray");

    public ModerationCommand(Supplier<ModerationServices> services) {
        super(services, ModerationPermission.HISTORY);
    }

    @Override
    public String describe() {
        return "everything about a player, and the pages behind it";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        ModerationServices moderation = services();

        if (!(sender instanceof Player viewer)) {
            // The console has no screen. Rather than failing, it is pointed at the commands that do
            // the same jobs in text.
            moderation.messages().send(sender, "moderation.console-use-commands");
            return;
        }
        if (args.length == 0) {
            moderation.screens().pickPlayer(viewer);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "info" -> tellStanding(viewer, args);
            case "config" -> openConfig(viewer);
            case "reports" -> moderation.screens().reports(viewer);
            case "staff" -> moderation.screens().staff(viewer);
            case "xray" -> moderation.screens().xraySuspicion(viewer);
            case "note" -> writeANote(viewer, args);
            case "notes" -> openNotes(viewer, args);
            default -> openPlayer(viewer, args[0]);
        }
    }

    /**
     * {@code /mod info <player>} — where they stand, in a sentence.
     *
     * <p>In chat rather than as a page, because this is the question asked <em>about</em> somebody
     * while doing something else: a name comes up in staff chat and somebody wants one line back. A
     * menu for that is four clicks and a closed inventory.
     */
    private void tellStanding(CommandSender sender, String[] args) {
        ModerationServices moderation = services();
        if (args.length < 2) {
            moderation.messages().send(sender, "moderation.usage", "usage", "/mod info <player>");
            return;
        }
        Optional<OfflinePlayer> found = subject(sender, args[1]);
        if (found.isEmpty()) {
            return;
        }
        OfflinePlayer them = found.get();
        String name = Players.nameOf(them);
        List<Punishment> record = moderation.punishmentService().history(them.getUniqueId());
        StandingRule rule = moderation.standingRule();
        Standing standing = rule.of(record, Instant.now());

        moderation.messages().send(sender, "moderation.standing",
                "player", name,
                "standing", standing.describe(),
                "colour", standing.colour());

        // The numbers underneath, so the one-line answer can be checked rather than believed. Sent
        // even when they are all zero: "nothing on record" is the useful half of a good standing.
        moderation.messages().send(sender, "moderation.standing-detail",
                "entries", rule.entriesOnRecord(record),
                "warnings", rule.recentWarnings(record, Instant.now()),
                "threshold", moderation.config().warnsBeforeBan(),
                "notes", moderation.noteService().countAbout(them.getUniqueId()),
                "reports", moderation.reportService().about(them.getUniqueId()).size());
    }

    /**
     * {@code /mod config} — every setting this module has.
     *
     * <p>Core's settings page, not one of ours. The whole schema — the topics, the ranges, the
     * descriptions, the tab completion and the buttons — is derived from {@code ModerationSettings},
     * so a page written here would be a second copy of something already generated, and it would be
     * the copy that goes stale the first time somebody adds a setting.
     */
    private void openConfig(Player viewer) {
        ModerationServices moderation = services();
        if (!viewer.hasPermission(ModerationPermission.CONFIG.node())) {
            moderation.messages().send(viewer, "moderation.no-permission");
            return;
        }
        new SettingsMenu(viewer, moderation.brand(), moderation.chat(),
                moderation.settingsNavigation(), "moderation", null).open();
    }

    private void openPlayer(Player viewer, String name) {
        ModerationServices moderation = services();
        Optional<OfflinePlayer> found = subject(viewer, name);
        found.ifPresent(them -> moderation.screens().player(viewer, them.getUniqueId(),
                Players.nameOf(them)));
    }

    private void openNotes(Player viewer, String[] args) {
        ModerationServices moderation = services();
        if (args.length < 2) {
            moderation.messages().send(viewer, "moderation.usage", "usage", "/mod notes <player>");
            return;
        }
        if (!viewer.hasPermission(ModerationPermission.NOTES.node())) {
            moderation.messages().send(viewer, "moderation.no-permission");
            return;
        }
        subject(viewer, args[1]).ifPresent(them ->
                moderation.screens().notes(viewer, them.getUniqueId(), Players.nameOf(them)));
    }

    private void writeANote(Player viewer, String[] args) {
        ModerationServices moderation = services();
        if (args.length < 3) {
            moderation.messages().send(viewer, "moderation.usage",
                    "usage", "/mod note <player> <what to remember>");
            return;
        }
        if (!viewer.hasPermission(ModerationPermission.NOTES.node())) {
            moderation.messages().send(viewer, "moderation.no-permission");
            return;
        }
        Optional<OfflinePlayer> found = subject(viewer, args[1]);
        if (found.isEmpty()) {
            return;
        }
        OfflinePlayer them = found.get();
        // No immunity check: a note is not something done *to* somebody, it is something the staff
        // remember. Blocking notes about an immune account would make the one account nobody could
        // keep a record of the one it matters most for.
        moderation.noteService().add(them.getUniqueId(), Players.nameOf(them), viewer.getUniqueId(),
                viewer.getName(), reasonFrom(args, 2));
        moderation.messages().send(viewer, "moderation.note-written",
                "player", Players.nameOf(them));
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            List<String> words = new ArrayList<>(SUBCOMMANDS);
            words.addAll(Players.suggestions(services().server(), args.length == 1 ? args[0] : ""));
            if (args.length == 1) {
                words.removeIf(word -> !word.toLowerCase(Locale.ROOT)
                        .startsWith(args[0].toLowerCase(Locale.ROOT)));
            }
            return words;
        }
        if (args.length == 2 && List.of("note", "notes").contains(args[0].toLowerCase(Locale.ROOT))) {
            return Players.suggestions(services().server(), args[1]);
        }
        return List.of();
    }
}
