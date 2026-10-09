package de.raindancer.modules.moderation.service;

import de.raindancer.core.moderation.audit.Audit;
import de.raindancer.core.moderation.audit.AuditEntry;
import de.raindancer.core.moderation.punishment.Punishment;
import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.social.economy.DebtKeeper;
import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.moderation.ModerationSettings;
import de.raindancer.modules.moderation.model.FineRecord;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.rules.FineRule;
import de.raindancer.modules.moderation.rules.StaffRule;
import de.raindancer.modules.moderation.store.FineLedger;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Fines: charging them, what cannot be paid becoming debt, forgiving and revoking.
 *
 * <h2>What happens when the balance does not cover it</h2>
 * With {@code fines.unpaid-becomes-debt} on, what the account holds is taken and the rest is recorded as debt, which
 * {@link #paid} lets the economy collect from future income. The victim of a fine is paid first, from what is
 * there; their unpaid part becomes the server's debt like the rest. Off, a fine somebody cannot pay is refused
 * and nothing is taken. No economy is never "free": a fine is refused outright, while a warning or a rule's
 * punishment still goes through without its money.
 */
public final class FineService implements IModerationService, DebtKeeper {

    /** The economy's source keys are not message keys, however alike they look. */
    private static final String SOURCES = "moderation.";
    private static final String DEBT = SOURCES + "debt";

    /** Which kind of fine, for the audit line and for the economy's source key. */
    public enum Kind {
        FINE("fine", SOURCES + "fine"),
        WARN("warn-fine", SOURCES + "warn-fine"),
        RULE("rule-fine", SOURCES + "rule-fine");

        private final String label;
        private final String source;

        Kind(String label, String source) {
            this.label = label;
            this.source = source;
        }
    }

    public enum Status { DONE, NO_ECONOMY, CANNOT_PAY, TOO_MUCH, INVALID }

    /** What a charge came to, in minor units as actually charged. */
    public record Charge(Money charged, Money paid, Money toVictim, Money debt) {
    }

    /** What fining somebody did. {@code detail} is the limit's own words when {@code TOO_MUCH}. */
    public record Result(Status status, Charge charge, Punishment punishment, FineRecord record, String detail) {

        static Result refused(Status status, String detail) {
            return new Result(status, null, null, null, detail);
        }

        public boolean done() {
            return status == Status.DONE;
        }
    }

    /** A warning's fine, already charged and waiting for the punishment it belongs to. */
    public record Warned(FineRecord record, String detail) {
    }

    public enum RevokeStatus { DONE, NOT_FOUND, ALREADY, REFUND_FAILED }

    public enum PayStatus { DONE, NOTHING_OWED, NO_ECONOMY, NOT_ENOUGH }

    public record Payment(PayStatus status, Money paid, Money remaining) {
    }

    /** Hands a punishment to whatever records and announces it. */
    @FunctionalInterface
    public interface Recorder {
        Punishment record(UUID actor, String actorName, UUID target, String targetName, PunishmentKind kind,
                          String reason, String detail);
    }

    /** Tells a player something, now or when they are back. */
    @FunctionalInterface
    public interface Notifier {
        void tell(UUID who, String key, Map<String, String> values);
    }

    private final Recorder recorder;
    private final FineLedger ledger;
    private final StaffRule staff;
    private final Notifier notifier;
    private final Audit audit;
    private final LogChannel log;
    private final FineRule rule = new FineRule();

    private volatile ModerationSettings settings;

    public FineService(Recorder recorder, FineLedger ledger, StaffRule staff, Notifier notifier, Audit audit,
                       LogChannel log, ModerationSettings settings) {
        this.recorder = recorder;
        this.ledger = ledger;
        this.staff = staff;
        this.notifier = notifier;
        this.audit = audit;
        this.log = log;
        settings(settings);
    }

    // ------------------------------------------------------------------------------ fining

    /** Whether an economy is there to charge anything. */
    public boolean hasEconomy() {
        return Economies.current().isPresent();
    }

    /** What this moderator would be held to: empty for no limit. */
    public Optional<Money> limitFor(UUID actor) {
        Money max = Fees.amount(settings.modFineMax());
        return staff.may(actor, ModerationPermission.FINE_UNLIMITED) || !max.isPositive()
                ? Optional.empty() : Optional.of(max);
    }

    /**
     * Fines somebody — charged first, recorded as a FINE punishment only when the charge went through.
     *
     * @param actor  null for the console and for the rules themselves, neither of which has a limit
     * @param victim who gets {@code fines.victim-share-percent} of it; null for nobody
     */
    public Result fine(UUID actor, String actorName, UUID target, String targetName, Money written, String reason,
                       UUID victim, Kind kind) {
        if (written == null || !written.isPositive()) {
            return Result.refused(Status.INVALID, null);
        }
        if (kind == Kind.FINE && actor != null) {
            var verdict = rule.mayFine(staff.may(actor, ModerationPermission.FINE_UNLIMITED), written,
                    Fees.amount(settings.modFineMax()), Fees.format(Fees.amount(settings.modFineMax())));
            if (verdict.isRefused()) {
                return Result.refused(Status.TOO_MUCH, verdict.detail());
            }
        }
        Optional<Charge> charged = collect(target, written, reason, kind, victim);
        if (charged.isEmpty()) {
            return Result.refused(hasEconomy() ? Status.CANNOT_PAY : Status.NO_ECONOMY, null);
        }
        Charge charge = charged.get();
        Punishment given = recorder.record(actor, actorName, target, targetName, PunishmentKind.FINE, reason,
                describe(charge));
        FineRecord record = new FineRecord(null, target, given.id(), kind.label, reason, System.currentTimeMillis(),
                charge.charged().minor(), charge.paid().minor(), charge.toVictim().minor(),
                charge.toVictim().isPositive() ? victim : null, charge.debt().minor(), 0, false, 0);
        save(record);
        audit(actor, actorName, target, targetName, kind.label, reason, describe(charge));
        return new Result(Status.DONE, charge, given, record, null);
    }

    /**
     * What the {@code nth} warning inside the window costs this player, charged now.
     *
     * @return empty when warnings are free, there is no economy, or it could not be taken
     */
    public Optional<Warned> chargeForWarning(UUID target, int nth) {
        ModerationSettings now = settings;
        Money least = Fees.amount(now.warnFineLeast());
        Money most = Fees.amount(now.warnFineMost());
        List<Money> ladder = FineRule.ladder(now.warnFine(), Fees::amount);
        if (ladder.isEmpty() && now.warnFinePercent() <= 0) {
            return Optional.empty();
        }
        Optional<Economy> bank = Economies.current();
        if (bank.isEmpty()) {
            log.warn("Warnings are set to cost money, but there is no economy: no fine was charged.");
            return Optional.empty();
        }
        bank.get().createAccount(target);
        Money fine = rule.warnFine(nth, bank.get().balance(target), ladder, now.warnFinePercent(), least, most);
        if (!fine.isPositive()) {
            return Optional.empty();
        }
        Optional<Charge> charged = collect(target, fine, "Fine: warning", Kind.WARN, null);
        if (charged.isEmpty()) {
            return Optional.empty();
        }
        Charge charge = charged.get();
        FineRecord record = new FineRecord(null, target, "", Kind.WARN.label, "a warning", System.currentTimeMillis(),
                charge.charged().minor(), charge.paid().minor(), 0, null, charge.debt().minor(), 0, false, 0);
        return Optional.of(new Warned(record, describe(charge)));
    }

    /** Ties a warning's fine to the warning it was for, once that is on the record. */
    public void attach(Warned warned, Punishment warning, String targetName) {
        FineRecord record = warned.record().withPunishment(warning.id());
        save(record);
        audit(null, "the warning fine", record.target(), targetName, Kind.WARN.label, warning.reason(), warned.detail());
    }

    private Optional<Charge> collect(UUID target, Money written, String reason, Kind kind, UUID victim) {
        Optional<Economy> found = Economies.current();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Economy bank = found.get();
        String source = kind.source;
        String why = "Fine: " + reason;
        int percent = victim != null && !victim.equals(target) ? settings.victimSharePercent() : 0;
        FineRule.Split split = rule.split(written, percent);
        Money feeQuote = Fees.quote(source, split.server());
        Money total = split.victim().plus(feeQuote);
        if (!total.isPositive()) {
            return Optional.empty();
        }
        bank.createAccount(target);
        Money balance = bank.balance(target);
        boolean affordable = balance.isAtLeast(total);
        if (!affordable && !settings.unpaidFinesBecomeDebt()) {
            return Optional.empty();
        }

        Money victimPaid = Money.ZERO;
        Money feePaid = Money.ZERO;
        if (affordable) {
            // The server's part first: if that cannot be taken, nothing has moved yet.
            if (feeQuote.isPositive()) {
                EconomyResult taken = Fees.charge(target, split.server(), why, source);
                if (!taken.succeeded()) {
                    return Optional.empty();
                }
                feePaid = taken.amount();
            }
            victimPaid = payVictim(bank, target, victim, split.victim(), why);
        } else {
            victimPaid = payVictim(bank, target, victim, split.victim().min(balance), why);
            Money left = balance.minus(victimPaid).max(Money.ZERO);
            Money take = feeQuote.min(left);
            if (take.isPositive()) {
                EconomyResult taken = bank.withdraw(target, take, why, source);
                feePaid = taken.succeeded() ? take : Money.ZERO;
            }
        }
        Money debt = total.minus(victimPaid).minus(feePaid).max(Money.ZERO);
        return Optional.of(new Charge(total, feePaid, victimPaid, debt));
    }

    private Money payVictim(Economy bank, UUID from, UUID to, Money amount, String why) {
        if (to == null || !amount.isPositive()) {
            return Money.ZERO;
        }
        bank.createAccount(to);
        return bank.transfer(from, to, amount, why).succeeded() ? amount : Money.ZERO;
    }

    private static String describe(Charge charge) {
        String text = Fees.format(charge.charged());
        return charge.debt().isPositive()
                ? text + ", " + Fees.format(charge.debt()) + " of it still owed"
                : text;
    }

    // ------------------------------------------------------------------------------ the record

    /** The amount for a history line: what the fines on this punishment came to. */
    public String amountOn(Punishment punishment) {
        List<FineRecord> fines = ledger.forPunishment(punishment.id());
        if (fines.isEmpty()) {
            return punishment.kind() == PunishmentKind.FINE ? "an amount not on record" : punishment.length();
        }
        long total = fines.stream().mapToLong(FineRecord::charged).sum();
        boolean revoked = fines.stream().allMatch(FineRecord::revoked);
        return Fees.format(Money.of(total)) + (revoked ? " (revoked)" : "");
    }

    public List<FineRecord> finesOn(String punishmentId) {
        return ledger.forPunishment(punishmentId);
    }

    public List<FineRecord> finesOf(UUID player) {
        return ledger.of(player);
    }

    // ------------------------------------------------------------------------------ revoking and forgiving

    /** Takes a fine back: what was paid to the server is refunded and what is still owed is dropped. */
    public RevokeStatus revoke(UUID actor, String actorName, String fineId, String targetName) {
        Optional<FineRecord> found = ledger.get(fineId);
        if (found.isEmpty()) {
            return RevokeStatus.NOT_FOUND;
        }
        FineRecord record = found.get();
        if (record.revoked()) {
            return RevokeStatus.ALREADY;
        }
        Money back = Money.of(record.paid());
        if (back.isPositive()) {
            EconomyResult refund = Fees.refund(record.target(), back, "Fine revoked: " + record.reason(), Kind.FINE.source);
            if (!refund.succeeded()) {
                return RevokeStatus.REFUND_FAILED;
            }
        }
        save(record.revokedWith(back.minor()));
        audit(actor, actorName, record.target(), targetName, "lift-fine", record.reason(),
                "refunded " + Fees.format(back));
        notifier.tell(record.target(), "moderation.fine.revoked-for-you",
                Map.of("reason", record.reason(), "amount", Fees.format(back)));
        return RevokeStatus.DONE;
    }

    /** Writes off everything somebody owes; returns how much. */
    public Money forgive(UUID actor, String actorName, UUID target, String targetName) {
        Money wiped = Money.of(ledger.forgive(target));
        if (wiped.isPositive()) {
            audit(actor, actorName, target, targetName, "forgive-debt", "debt written off", Fees.format(wiped));
            notifier.tell(target, "moderation.fine.forgiven-for-you", Map.of("amount", Fees.format(wiped)));
        }
        return wiped;
    }

    // ------------------------------------------------------------------------------ debt

    @Override
    public Money owed(UUID who) {
        return Money.of(ledger.owed(who));
    }

    @Override
    public Money paid(UUID who, Money amount) {
        return amount == null ? Money.ZERO : Money.of(ledger.payDown(who, amount.minor()));
    }

    /**
     * Pays towards what the player owes from their own balance.
     *
     * @param wanted null for as much as the balance covers, up to everything owed
     */
    public Payment pay(UUID player, Money wanted) {
        Money owing = owed(player);
        if (!owing.isPositive()) {
            return new Payment(PayStatus.NOTHING_OWED, Money.ZERO, Money.ZERO);
        }
        Optional<Economy> found = Economies.current();
        if (found.isEmpty()) {
            return new Payment(PayStatus.NO_ECONOMY, Money.ZERO, owing);
        }
        Economy bank = found.get();
        Money balance = bank.balance(player);
        Money amount = (wanted == null ? owing : wanted.min(owing));
        if (wanted == null) {
            amount = amount.min(balance);
        }
        if (!amount.isPositive() || !balance.isAtLeast(amount)) {
            return new Payment(PayStatus.NOT_ENOUGH, Money.ZERO, owing);
        }
        EconomyResult taken = bank.withdraw(player, amount, "Paying off fines", DEBT);
        if (!taken.succeeded()) {
            return new Payment(PayStatus.NOT_ENOUGH, Money.ZERO, owing);
        }
        Money settled = paid(player, amount);
        if (amount.isMoreThan(settled)) {
            // The economy collected some of it from income in the meantime; give the surplus back.
            Fees.refund(player, amount.minus(settled), "Fine debt already settled", DEBT);
        }
        return new Payment(PayStatus.DONE, settled, owed(player));
    }

    // ------------------------------------------------------------------------------ plumbing

    private void save(FineRecord record) {
        if (!ledger.replace(record)) {
            log.error("The fine ledger could not be written; fine {} is only in memory.", record.id());
        }
    }

    private void audit(UUID actor, String actorName, UUID target, String targetName, String action, String reason,
                       String detail) {
        if (audit == null || !settings.auditEverything()) {
            return;
        }
        audit.record(AuditEntry.of("moderation", action).by(actor, actorName).to(target, targetName)
                .saying(reason).with("amount", detail));
    }

    @Override
    public void settings(ModerationSettings settings) {
        this.settings = settings == null ? ModerationSettings.DEFAULTS : settings;
    }

    @Override
    public String describe() {
        return "fines: charging them, unpaid ones becoming debt, forgiving and revoking";
    }
}
