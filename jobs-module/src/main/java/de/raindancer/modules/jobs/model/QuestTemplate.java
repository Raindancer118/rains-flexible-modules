package de.raindancer.modules.jobs.model;

import de.raindancer.core.ui.choose.ItemSelection;

/**
 * A quest players can be given, from quests.yml, at the size it has for the poorest players; richer players
 * get it bigger and better paid.
 *
 * @param role   the role it is for ("miner"), or empty for anybody
 * @param amount how many, at the lowest tier
 * @param pay    what it pays at the lowest tier, as written ("150")
 */
public record QuestTemplate(String id, String title, String icon, QuestTask task, ItemSelection things, String role,
                            int amount, String pay) {

    public QuestTemplate {
        role = role == null ? "" : role;
    }

    public boolean forRole() {
        return !role.isEmpty();
    }
}
