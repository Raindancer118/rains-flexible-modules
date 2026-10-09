package de.raindancer.modules.moderation.service;

import de.raindancer.core.moderation.punishment.Punishment;
import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.core.moderation.rules.RulePenalty;
import de.raindancer.core.moderation.rules.ServerRule;
import de.raindancer.core.moderation.rules.ServerRules;
import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.moderation.ModerationSettings;
import de.raindancer.modules.moderation.model.Sentence;
import de.raindancer.modules.moderation.rules.BanLimitRule;
import de.raindancer.modules.moderation.rules.RuleBreachRule;
import de.raindancer.modules.moderation.rules.StaffRule;
import de.raindancer.modules.moderation.store.RuleOffences;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Punishing somebody for breaking one of the server's rules ({@code /rules}): the rule's own ladder decides what,
 * their count of offences against it decides which rung, and the punishment goes through
 * {@link PunishmentService} like any other — mirrored, announced, audited, kicked if it has to be.
 */
public final class RuleBreachService implements IModerationService {

    private final PunishmentService punishments;
    private final RuleOffences offences;
    private final StaffRule staff;
    private final Supplier<BanLimitRule> banLimit;
    private final Messages messages;
    private final FineService fines;
    private final RuleBreachRule rule = new RuleBreachRule();

    public RuleBreachService(PunishmentService punishments, RuleOffences offences, StaffRule staff,
                             Supplier<BanLimitRule> banLimit, Messages messages, FineService fines) {
        this.punishments = punishments;
        this.offences = offences;
        this.staff = staff;
        this.banLimit = banLimit;
        this.messages = messages;
        this.fines = fines;
    }

    @Override
    public void settings(ModerationSettings settings) {
        // Nothing read yet; the ladders are the rules' own.
    }

    /** The rules as players see them; empty when no plugin keeps any. */
    public List<ServerRule> rules() {
        return ServerRules.current();
    }

    public int offencesAgainst(UUID subject, ServerRule rule) {
        return offences.count(subject, rule.id());
    }

    /** What breaking it now would cost them; empty for a rule without a ladder. */
    public Optional<RuleBreachRule.Breach> next(UUID subject, ServerRule broken, String note) {
        return rule.breach(broken, offencesAgainst(subject, broken), note);
    }

    /** Whether this actor may hand this rung to this player: the same nodes and ban limit as doing it by hand. */
    public Verdict mayHandOut(UUID actor, UUID subject, RuleBreachRule.Breach breach) {
        Verdict allowed = staff.canAct(actor, subject, RuleBreachRule.permissionFor(breach.penalty()));
        if (allowed.isRefused() || breach.penalty().kind() != PunishmentKind.BAN) {
            return allowed;
        }
        return banLimit.get().mayBanFor(actor, sentenceOf(breach));
    }

    /**
     * Hands out the next rung for breaking this rule, saying everything to {@code by}.
     *
     * @param actor null for the console
     * @return what was recorded; empty when it was refused, and {@code by} was told why
     */
    public Optional<Punishment> breakRule(CommandSender by, UUID actor, String actorName, UUID subject,
                                          String subjectName, ServerRule broken, String note) {
        Optional<RuleBreachRule.Breach> next = next(subject, broken, note);
        if (next.isEmpty()) {
            messages.send(by, "moderation.rules.no-ladder", "number", broken.number(), "title", broken.title());
            return Optional.empty();
        }
        RuleBreachRule.Breach breach = next.get();
        Verdict allowed = mayHandOut(actor, subject, breach);
        if (allowed.isRefused()) {
            messages.send(by, allowed.reason(), "detail", allowed.detail() == null ? "" : allowed.detail());
            return Optional.empty();
        }
        RulePenalty penalty = breach.penalty();
        Money fine = penalty.fine() > 0 ? Fees.amount(String.valueOf(penalty.fine())) : Money.ZERO;
        boolean fineOnly = penalty.kind() == PunishmentKind.FINE;

        Punishment given = null;
        if (!fineOnly) {
            given = punishments.punish(actor, actorName, subject, subjectName, penalty.kind(),
                    sentenceOf(breach), breach.reason());
        }
        if (fine.isPositive()) {
            FineService.Result fined = fines.fine(actor, actorName, subject, subjectName, fine, breach.reason(),
                    null, FineService.Kind.RULE);
            if (fined.done()) {
                given = given == null ? fined.punishment() : given;
            } else {
                messages.send(by, fined.status() == FineService.Status.NO_ECONOMY
                        ? "moderation.fine.no-economy" : "moderation.fine.cannot-pay", "player", subjectName);
                if (fineOnly) {
                    return Optional.empty();
                }
            }
        }
        if (given == null) {
            return Optional.empty();
        }
        offences.add(subject, broken.id());
        messages.send(by, "moderation.rules.punished", "player", subjectName, "number", broken.number(),
                "title", broken.title(), "penalty", breach.penalty().describe(),
                "offence", RulePenalty.ordinal(breach.offence()));
        return Optional.of(given);
    }

    private static Sentence sentenceOf(RuleBreachRule.Breach breach) {
        return breach.penalty().length() == null ? Sentence.forEver() : Sentence.of(breach.penalty().length());
    }

    @Override
    public String describe() {
        return "punishing for breaking one of the server's rules";
    }
}
