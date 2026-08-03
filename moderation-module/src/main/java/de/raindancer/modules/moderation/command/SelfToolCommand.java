package de.raindancer.modules.moderation.command;

import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.util.Players;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@code /fly}, {@code /god}, {@code /ungod}, {@code /instakill} — the tools a moderator points at
 * themselves.
 *
 * <h2>Why one class for four commands</h2>
 * Because they differ in a {@link Tool} and nothing else: each toggles one thing, on yourself by default
 * and on somebody else if you name them. Written out four times they would drift, and the drift is
 * always the same three things — one of them forgets the audit line, one of them forgets that naming
 * somebody needs a second permission, and one of them cannot be turned off again.
 *
 * <h2>Why acting on somebody else needs more than acting on yourself</h2>
 * Making <em>yourself</em> invincible is a tool. Making somebody <em>else</em> invincible is a change to
 * another person's game that they did not ask for and may not notice — so it asks the same
 * {@code canAct} question as a punishment does, immunity included, and it is audited.
 */
public final class SelfToolCommand extends StaffCommand {

    /** What the four commands actually do. */
    public enum Tool {

        /** Flight. Off and on again, and remembered by nothing — the game already stores it. */
        FLY("fly", "Lets somebody fly", ModerationPermission.FLY),

        /** Nothing hurts them. */
        GOD("god", "Makes somebody invulnerable", ModerationPermission.GOD),

        /** Whatever they hit dies. */
        INSTAKILL("instakill", "Makes everything somebody hits die in one hit",
                ModerationPermission.INSTAKILL);

        private final String word;
        private final String description;
        private final ModerationPermission permission;

        Tool(String word, String description, ModerationPermission permission) {
            this.word = word;
            this.description = description;
            this.permission = permission;
        }

        public String word() {
            return word;
        }

        public String describe() {
            return description;
        }

        public ModerationPermission permission() {
            return permission;
        }
    }

    private final Tool tool;

    /**
     * Whether this registration turns the thing on, off, or toggles it.
     *
     * <p>{@code /god} toggles and {@code /ungod} switches off. Both exist because "make sure this is
     * off" is a thing somebody needs to be able to say without checking first — a toggle answers
     * "it is on now" when what they wanted was "it is off".
     */
    private final Boolean forceTo;

    public SelfToolCommand(Supplier<ModerationServices> services, Tool tool, Boolean forceTo) {
        super(services, tool.permission());
        this.tool = tool;
        this.forceTo = forceTo;
    }

    @Override
    public String describe() {
        return tool.describe() + (forceTo == null ? "" : forceTo ? " (on)" : " (off)");
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        ModerationServices moderation = services();

        // Themselves, unless they named somebody.
        UUID subject;
        String name;
        if (args.length == 0) {
            if (!(sender instanceof Player self)) {
                moderation.messages().send(sender, "moderation.usage",
                        "usage", "/" + commandWord() + " <player>");
                return;
            }
            subject = self.getUniqueId();
            name = self.getName();
        } else {
            Optional<OfflinePlayer> found = subject(sender, args[0]);
            if (found.isEmpty()) {
                return;
            }
            OfflinePlayer them = found.get();
            if (!them.isOnline()) {
                // None of the three mean anything to somebody who is not here, and all three would be
                // forgotten by the time they arrived — see PlayerPowers on why nothing is persisted.
                moderation.messages().send(sender, "moderation.not-here", "player",
                        Players.nameOf(them));
                return;
            }
            subject = them.getUniqueId();
            name = Players.nameOf(them);

            // Doing it *to* somebody is a change to their game they did not ask for. Same question a
            // punishment asks, immunity included.
            if (!mayAct(sender, subject)) {
                return;
            }
        }
        apply(moderation, sender, subject, name);
    }

    private void apply(ModerationServices moderation, CommandSender sender, UUID subject, String name) {
        boolean nowOn = switch (tool) {
            case FLY -> setFlight(moderation, subject);
            case GOD -> forceTo == null
                    ? moderation.powers().toggleGod(subject)
                    : keep(moderation.powers().god(subject, forceTo), forceTo);
            case INSTAKILL -> forceTo == null
                    ? moderation.powers().toggleInstakill(subject)
                    : keep(moderation.powers().instakill(subject, forceTo), forceTo);
        };
        moderation.staff().recordSelfTool(sender, subject, name, tool, nowOn);

        boolean themselves = sender instanceof Player self && self.getUniqueId().equals(subject);
        moderation.messages().send(sender,
                // "turned-on", not "on": a bare `on:` key in YAML is the boolean true, so the line
                // would be filed under ...instakill.true and the lookup would print its own name.
                "moderation.tool." + tool.word() + (nowOn ? ".turned-on" : ".turned-off")
                        + (themselves ? "" : "-other"),
                "player", name);
        if (!themselves) {
            Player them = moderation.server().getPlayer(subject);
            if (them != null) {
                moderation.messages().send(them,
                        "moderation.tool." + tool.word() + (nowOn ? ".turned-on" : ".turned-off"));
            }
        }
    }

    /**
     * Flight, through Core's {@code PlayerAdmin}.
     *
     * <p>Not stored here: the game already remembers whether somebody may fly, so a second copy would be
     * the one that disagrees after a gamemode change.
     */
    private boolean setFlight(ModerationServices moderation, UUID subject) {
        if (forceTo != null) {
            moderation.players().flight(subject, forceTo);
            return forceTo;
        }
        Player them = moderation.server().getPlayer(subject);
        boolean wanted = them == null || !them.getAllowFlight();
        moderation.players().flight(subject, wanted);
        return wanted;
    }

    /** The state after a forced set, whether or not the set changed anything. */
    private static boolean keep(boolean changedSomething, boolean wanted) {
        return wanted;
    }

    private String commandWord() {
        return forceTo != null && !forceTo ? "un" + tool.word() : tool.word();
    }

    @Override
    public java.util.Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            return Players.suggestions(services().server(), args.length == 1 ? args[0] : "");
        }
        return java.util.List.of();
    }

    /** Lower-cased for a message key. */
    static String keyOf(Tool tool) {
        return tool.word().toLowerCase(Locale.ROOT);
    }
}
