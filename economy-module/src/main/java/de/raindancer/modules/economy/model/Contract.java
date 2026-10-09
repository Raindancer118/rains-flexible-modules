package de.raindancer.modules.economy.model;

import de.raindancer.core.social.economy.Money;

import java.util.UUID;

/**
 * One player paying another the same amount every so many minutes: a job's wage, or a service's price — rent for
 * an apartment, a farm's upkeep. {@code employer} pays, {@code employee} is paid, whatever the kind.
 *
 * @param nextAt when the next payment is due
 * @param missed payments in a row the payer could not make
 */
public record Contract(UUID id, UUID employer, String employerName, UUID employee, String employeeName, Money wage,
                       int everyMinutes, long nextAt, int missed, String title, long since, Kind kind) {

    /** What the money is for: work done for the payer, or something the payer uses — said differently. */
    public enum Kind { JOB, SERVICE }

    public Contract {
        title = title == null ? "" : title;
        everyMinutes = Math.max(1, everyMinutes);
        kind = kind == null ? Kind.JOB : kind;
    }

    public Contract(UUID id, UUID employer, String employerName, UUID employee, String employeeName, Money wage,
                    int everyMinutes, long nextAt, int missed, String title, long since) {
        this(id, employer, employerName, employee, employeeName, wage, everyMinutes, nextAt, missed, title, since,
                Kind.JOB);
    }

    public boolean isService() {
        return kind == Kind.SERVICE;
    }

    public long everyMillis() {
        return everyMinutes * 60_000L;
    }

    public Contract paid(long now) {
        long next = nextAt + everyMillis();
        // Back from a long downtime: one wage, then on schedule again — never a backlog of wages at once.
        return new Contract(id, employer, employerName, employee, employeeName, wage, everyMinutes,
                next <= now ? now + everyMillis() : next, 0, title, since, kind);
    }

    public Contract missedAt(long now) {
        return new Contract(id, employer, employerName, employee, employeeName, wage, everyMinutes,
                now + everyMillis(), missed + 1, title, since, kind);
    }

    public boolean involves(UUID player) {
        return employer.equals(player) || employee.equals(player);
    }
}
