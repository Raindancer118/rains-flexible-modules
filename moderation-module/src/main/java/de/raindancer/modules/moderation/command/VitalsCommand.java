package de.raindancer.modules.moderation.command;

import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.util.Players;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@code /heal}, {@code /feed}, {@code /hurt}, {@code /starve} — the four that change somebody's body.
 *
 * <h2>Why not {@code SelfToolCommand}</h2>
 * Because those three are <em>states</em> and these four are <em>events</em>. Flight is on or off and
 * asking twice changes nothing; hurting somebody twice hurts them twice. Folding an event into a toggle
 * gives a button whose second click does the opposite of its own label, and there is no label that is
 * right about both.
 *
 * <h2>Why hurt and starve sit a rank higher than heal and feed</h2>
 * Restoring somebody is unremarkable and undoes itself. Taking half of somebody's health from a menu,
 * silently, is a way to kill a player in a fight they were winning — so it belongs to an admin by
 * default. A server that disagrees can hand it to one particular mod, which is what the per-person
 * toggles are for.
 */
public final class VitalsCommand extends StaffCommand {

    /** How much {@code /hurt} takes: half of a full bar. Enough to matter, not enough to kill outright. */
    private static final double HURT_HEARTS = 10.0;

    /** How far {@code /starve} drops them — to the point where sprinting stops, not to zero. */
    private static final int STARVE_TO = 6;

    /** The four. */
    public enum Vital {

        HEAL("heal", "Restores somebody to full health", ModerationPermission.HEAL,
                Material.GOLDEN_APPLE, false),
        FEED("feed", "Fills somebody's hunger bar", ModerationPermission.FEED,
                Material.COOKED_BEEF, false),
        HURT("hurt", "Takes half of somebody's health", ModerationPermission.HURT,
                Material.IRON_SWORD, true),
        STARVE("starve", "Empties most of somebody's hunger bar", ModerationPermission.STARVE,
                Material.ROTTEN_FLESH, true);

        private final String word;
        private final String description;
        private final ModerationPermission permission;
        private final Material icon;
        private final boolean harmful;

        Vital(String word, String description, ModerationPermission permission, Material icon,
              boolean harmful) {
            this.word = word;
            this.description = description;
            this.permission = permission;
            this.icon = icon;
            this.harmful = harmful;
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

        public Material icon() {
            return icon;
        }

        /** Whether this takes something away — so a screen can colour it, and confirm it. */
        public boolean harmful() {
            return harmful;
        }

        /** Where the wording lives. Never a bare {@code on}/{@code off}: those are YAML booleans. */
        public String messageKey() {
            return "moderation.vitals." + word;
        }
    }

    private final Vital vital;

    public VitalsCommand(Supplier<ModerationServices> services, Vital vital) {
        super(services, vital.permission());
        this.vital = vital;
    }

    @Override
    public String describe() {
        return vital.describe();
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        ModerationServices moderation = services();

        UUID subject;
        String name;
        if (args.length == 0) {
            if (!(sender instanceof Player self)) {
                moderation.messages().send(sender, "moderation.usage",
                        "usage", "/" + vital.word() + " <player>");
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
                // None of the four mean anything to somebody who is not here — and unlike a ban, there
                // is nothing sensible to queue: healing somebody the moment they log in is not healing.
                moderation.messages().send(sender, "moderation.not-here", "player",
                        Players.nameOf(them));
                return;
            }
            subject = them.getUniqueId();
            name = Players.nameOf(them);

            // Doing it to somebody else asks the same question a punishment asks, immunity included.
            // Healing is harmless, but hurting is not, and the two share this path deliberately: a
            // guard that only covers the dangerous half is a guard somebody will move the line past.
            if (!mayAct(sender, subject)) {
                return;
            }
        }
        apply(moderation, sender, subject, name);
    }

    private void apply(ModerationServices moderation, CommandSender sender, UUID subject, String name) {
        switch (vital) {
            case HEAL -> moderation.players().heal(subject);
            case FEED -> moderation.players().feed(subject);
            case HURT -> moderation.players().damage(subject, HURT_HEARTS);
            case STARVE -> moderation.players().food(subject, STARVE_TO);
        }
        moderation.staff().recordVital(sender, subject, name, vital);

        boolean themselves = sender instanceof Player self && self.getUniqueId().equals(subject);
        moderation.messages().send(sender,
                vital.messageKey() + (themselves ? ".done" : ".done-other"), "player", name);
        if (!themselves) {
            // They are told. Somebody whose health drops by half with no explanation reports a bug, and
            // somebody healed without knowing it walks back into the fight they were losing.
            Player them = moderation.server().getPlayer(subject);
            if (them != null) {
                moderation.messages().send(them, vital.messageKey() + ".received",
                        "player", sender.getName());
            }
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            return Players.suggestions(services().server(), args.length == 1 ? args[0] : "");
        }
        return List.of();
    }
}
