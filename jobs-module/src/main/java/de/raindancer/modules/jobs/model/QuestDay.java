package de.raindancer.modules.jobs.model;

import java.util.List;

/**
 * A player's quests for one day.
 *
 * @param day    ISO date, in the server's time zone
 * @param tier   how hard and well paid they are, from the player's balance when they were given
 * @param before the quests of the day before, so the next day does not hand the same ones out again
 */
public record QuestDay(String day, int tier, List<Quest> quests, List<String> before) {

    public QuestDay {
        quests = List.copyOf(quests);
        before = List.copyOf(before);
    }

    public QuestDay with(int index, Quest changed) {
        java.util.List<Quest> next = new java.util.ArrayList<>(quests);
        next.set(index, changed);
        return new QuestDay(day, tier, next, before);
    }
}
