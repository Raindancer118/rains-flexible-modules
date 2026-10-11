package de.raindancer.modules.moderation.model;

import java.util.function.Predicate;

/**
 * What a viewer of the player screen may read there — each part guarded by the right that already guards the same
 * data elsewhere, so the overview never shows more than the doors behind it would.
 *
 * @param record    entries, warnings, rule offences, balance and debt — {@link ModerationPermission#HISTORY}
 * @param reports   report counts and the x-ray ledger — {@link ModerationPermission#REPORTS}, like the evidence screens
 * @param notes     how many staff notes — {@link ModerationPermission#NOTES}
 * @param attempts  attempts while punished — {@link ModerationPermission#ATTEMPTS}
 * @param chat      old names, which come out of the chat log — {@link ModerationPermission#CHATLOG}
 * @param location  where they stand, ping and client — whoever may teleport to them anyway
 * @param addresses a blocked join's address hash — operators only
 */
public record OverviewAccess(boolean record, boolean reports, boolean notes, boolean attempts, boolean chat,
                             boolean location, boolean addresses) {

    /** The console, and tests. */
    public static final OverviewAccess EVERYTHING = new OverviewAccess(true, true, true, true, true, true, true);

    public static OverviewAccess of(Predicate<ModerationPermission> may, boolean mayTeleport, boolean operator) {
        return new OverviewAccess(may.test(ModerationPermission.HISTORY), may.test(ModerationPermission.REPORTS),
                may.test(ModerationPermission.NOTES), may.test(ModerationPermission.ATTEMPTS),
                may.test(ModerationPermission.CHATLOG), mayTeleport, operator);
    }
}
