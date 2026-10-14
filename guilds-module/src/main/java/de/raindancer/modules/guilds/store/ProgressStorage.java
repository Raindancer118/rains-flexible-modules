package de.raindancer.modules.guilds.store;

/** What the progress tables hold beyond a guild's own rows. */
public final class ProgressStorage {

    private ProgressStorage() {
    }

    /** Who wears the crown, for which week, and the last week whose winner was settled. */
    public record CrownState(String guild, long week, long settledWeek) {

        public static final CrownState NONE = new CrownState("", -1, -1);
    }
}
