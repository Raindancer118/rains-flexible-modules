package de.raindancer.modules.moderation.command;

import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.model.Reason;
import de.raindancer.modules.moderation.model.Sentence;
import de.raindancer.modules.moderation.util.Players;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /ban}, {@code /mute}, {@code /freeze} — one class, three registrations.
 *
 * <h2>Why one class</h2>
 * Because the three differ in a {@link PunishmentKind} and nothing else. The version this replaces had
 * them as separate files and they had drifted: {@code /mute} took a length and {@code /ban} did not,
 * one of them wrote to the vanilla ban list and the other did not, and only one of them wrote an audit
 * line. Everything they share is here and everything Core does is in {@code PunishmentService}.
 *
 * <h2>Reading the arguments</h2>
 * {@code /ban <player> [length|reason-id] [reason...]}. The second word is tried three ways, in order:
 *
 * <ol>
 *   <li>a length — {@code 2h}, {@code 7d}, {@code perm};</li>
 *   <li>a reason from the catalogue, which brings its own length off the ladder;</li>
 *   <li>the start of a free-text reason, with the configured default length.</li>
 * </ol>
 *
 * <p>Free text is allowed on purpose, and does not climb a ladder. That trade — a countable record for
 * the presets, a way out for everything they do not cover — is the whole design.
 */
public final class PunishCommand extends StaffCommand {

    private final PunishmentKind kind;

    public PunishCommand(Supplier<ModerationServices> services, PunishmentKind kind,
                         ModerationPermission permission) {
        super(services, permission);
        this.kind = kind;
    }

    @Override
    public String describe() {
        return "puts somebody " + kind.past() + ", for a while or for good";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        ModerationServices moderation = services();

        if (args.length == 0) {
            // A bare command opens the screen for it rather than reciting a syntax. Somebody who
            // typed "/<the command>" has already said what they want to do; answering with the grammar they
            // plainly do not have to hand is the least useful reply available. The console still gets
            // the usage line, having no screen to open.
            if (sender instanceof org.bukkit.entity.Player staff) {
                new de.raindancer.modules.moderation.screen.PlayerPickerMenu(moderation, staff, null,
                        (who, name) -> new de.raindancer.modules.moderation.screen.PunishMenu(moderation, staff, null, who, name,
                                kind).open()).open();
                return;
            }
            moderation.messages().send(sender, "moderation.usage",
                    "usage", "/" + kind.name().toLowerCase(Locale.ROOT)
                            + " <player> [length] [reason]");
            return;
        }
        Optional<OfflinePlayer> found = subject(sender, args[0]);
        if (found.isEmpty()) {
            return;
        }
        OfflinePlayer them = found.get();
        if (!mayAct(sender, them.getUniqueId())) {
            return;
        }

        Sentence sentence;
        String reason;
        Optional<Sentence> typed = args.length > 1 ? Sentence.parse(args[1]) : Optional.empty();
        Optional<Reason> preset = args.length > 1
                ? moderation.reasons().byId(args[1]) : Optional.empty();

        if (typed.isPresent()) {
            sentence = typed.get();
            reason = reasonFrom(args, 2);
        } else if (preset.isPresent() && preset.get().kind() == kind) {
            // The ladder decides, and the console line says what it decided — so a moderator who
            // disagrees knows to type a length rather than discovering it afterwards.
            sentence = moderation.punishmentService().suggest(preset.get(), them.getUniqueId());
            reason = preset.get().label()
                    + (args.length > 2 ? " — " + reasonFrom(args, 2) : "");
        } else {
            sentence = defaultLength(moderation);
            reason = reasonFrom(args, 1);
        }

        // The ban cap, and the reason it is checked here rather than only in the menu: a mod who
        // types /ban somebody perm must be refused too. A limit the command does not know about is a
        // limit that only applies to people who click.
        if (de.raindancer.modules.moderation.rules.BanLimitRule.appliesTo(kind)) {
            var allowed = moderation.banLimitRule().mayBanFor(actorOf(sender), sentence);
            if (allowed.isRefused()) {
                allowed.refusal().ifPresent(reasonKey -> moderation.messages().send(sender, reasonKey,
                        "detail", allowed.detail() == null ? "" : allowed.detail()));
                return;
            }
        }
        moderation.punishmentService().punish(actorOf(sender), actorNameOf(sender),
                them.getUniqueId(), Players.nameOf(them), kind, sentence, reason);
        moderation.messages().send(sender, "moderation.punished",
                "player", Players.nameOf(them), "what", kind.past(),
                "length", kind.isLasting() ? sentence.describe() : "once");
    }

    /**
     * The configured default, or for ever if somebody has written something unreadable in the file.
     *
     * <p>Permanent is the safe end for a setting nobody can parse: a moderator sees the length in the
     * answer and can lift it, whereas a silent five minutes is a ban that expires before anybody
     * notices it was wrong.
     */
    private Sentence defaultLength(ModerationServices moderation) {
        String configured = switch (kind) {
            case MUTE -> moderation.config().defaultMuteLength();
            case FREEZE -> moderation.config().defaultFreezeLength();
            default -> moderation.config().defaultBanLength();
        };
        return Sentence.parse(configured).orElseGet(Sentence::forEver);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            return super.suggest(source, args);
        }
        if (args.length == 2) {
            List<String> words = new ArrayList<>(
                    List.of("30m", "1h", "12h", "1d", "7d", "30d", "perm"));
            services().reasons().forKind(kind).forEach(reason -> words.add(reason.id()));
            String typed = args[1].toLowerCase(Locale.ROOT);
            words.removeIf(word -> !word.startsWith(typed));
            return words;
        }
        return List.of();
    }
}
