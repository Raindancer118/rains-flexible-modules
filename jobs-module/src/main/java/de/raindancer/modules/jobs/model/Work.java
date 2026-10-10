package de.raindancer.modules.jobs.model;

import de.raindancer.core.ui.choose.ItemSelection;

/**
 * A kind of work an order can ask for, from orders.yml.
 *
 * @param name     what the units are called, plural: "Wardens", "blocks of stone"
 * @param value    what one is worth in the easiest order, as written ("8")
 * @param rate     how many a skilled player manages in an hour — the clock is worked out from it
 * @param hardness 0 for the easiest work, 1 for the hardest; an order picks work as hard as its amount
 */
public record Work(String id, String name, String icon, QuestTask task, ItemSelection things, String value,
                   double rate, double hardness) {

    public Work {
        rate = Math.max(0.01, rate);
        hardness = Math.clamp(hardness, 0.0, 1.0);
    }

    /** "Kill 100 Wardens", "Mine 2,000 blocks of stone", "Travel 5,000 blocks". */
    public String says(int units) {
        String count = String.format(java.util.Locale.ROOT, "%,d", units);
        return (task == QuestTask.KILL ? "Kill" : task.verb()) + " " + count + " " + name;
    }
}
