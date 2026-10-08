package de.raindancer.modules.economy.model;

import de.raindancer.core.social.economy.Money;

import java.util.UUID;

/**
 * One player employing another: a wage paid from the employer's account every so many minutes.
 *
 * @param nextAt when the next wage is due
 * @param missed wages in a row the employer could not pay
 */
public record Contract(UUID id, UUID employer, String employerName, UUID employee, String employeeName, Money wage,
                       int everyMinutes, long nextAt, int missed, String title, long since) {

    public Contract {
        title = title == null ? "" : title;
        everyMinutes = Math.max(1, everyMinutes);
    }

    public long everyMillis() {
        return everyMinutes * 60_000L;
    }

    public Contract paid(long now) {
        long next = nextAt + everyMillis();
        // Back from a long downtime: one wage, then on schedule again — never a backlog of wages at once.
        return new Contract(id, employer, employerName, employee, employeeName, wage, everyMinutes,
                next <= now ? now + everyMillis() : next, 0, title, since);
    }

    public Contract missedAt(long now) {
        return new Contract(id, employer, employerName, employee, employeeName, wage, everyMinutes,
                now + everyMillis(), missed + 1, title, since);
    }

    public boolean involves(UUID player) {
        return employer.equals(player) || employee.equals(player);
    }
}
