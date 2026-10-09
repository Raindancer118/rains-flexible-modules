package de.raindancer.modules.moderation.model;

import java.util.UUID;

/**
 * One fine: what it was for, what has been paid so far, what is still owed.
 *
 * <p>Core's punishment record has no field for money, so this is kept beside it, linked by the id of the
 * punishment it belongs to. All amounts are minor units of the server's currency, as charged — after the
 * price index, which is what the player actually lost.
 *
 * @param punishmentId  the Core punishment this belongs to: the FINE entry, or the warning that cost money
 * @param source        which kind of fine it is, for the audit line: {@code fine}, {@code warn-fine}, {@code rule-fine}
 * @param paid          what reached the server, debt payments later included — what a revocation refunds
 * @param toVictim      what went to the victim instead of the server; never clawed back
 * @param debt          what is still owed
 * @param forgiven      what staff wrote off
 * @param refunded      what a revocation gave back
 */
public record FineRecord(String id, UUID target, String punishmentId, String source, String reason,
                         long givenAt, long charged, long paid, long toVictim, UUID victim,
                         long debt, long forgiven, boolean revoked, long refunded) {

    public FineRecord {
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        punishmentId = punishmentId == null ? "" : punishmentId;
        source = source == null || source.isBlank() ? "fine" : source;
        reason = reason == null || reason.isBlank() ? "no reason given" : reason;
        paid = Math.max(0, paid);
        toVictim = Math.max(0, toVictim);
        debt = Math.max(0, debt);
        forgiven = Math.max(0, forgiven);
        refunded = Math.max(0, refunded);
    }

    /** What the player has lost to this fine in total, the victim's share included. */
    public long lost() {
        return paid + toVictim;
    }

    public boolean isOpen() {
        return !revoked && debt > 0;
    }

    public FineRecord withPunishment(String punishment) {
        return new FineRecord(id, target, punishment, source, reason, givenAt, charged, paid, toVictim, victim,
                debt, forgiven, revoked, refunded);
    }

    /** {@code amount} of the debt has been paid, which can never be more than was owed. */
    public FineRecord paidDown(long amount) {
        long settled = Math.min(Math.max(0, amount), debt);
        return new FineRecord(id, target, punishmentId, source, reason, givenAt, charged, paid + settled,
                toVictim, victim, debt - settled, forgiven, revoked, refunded);
    }

    public FineRecord forgivenDebt() {
        return new FineRecord(id, target, punishmentId, source, reason, givenAt, charged, paid, toVictim, victim,
                0, forgiven + debt, revoked, refunded);
    }

    /** Revoked: the debt is dropped and {@code back} is what was given back. */
    public FineRecord revokedWith(long back) {
        return new FineRecord(id, target, punishmentId, source, reason, givenAt, charged, paid, toVictim, victim,
                0, forgiven, true, Math.max(0, back));
    }
}
