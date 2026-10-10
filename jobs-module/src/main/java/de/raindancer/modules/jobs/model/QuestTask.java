package de.raindancer.modules.jobs.model;

/** What a personal quest asks for, and what its {@code things} name. */
public enum QuestTask {
    /** Break blocks — not ones a player put there. Things are blocks: "iron_ore", "*_log". */
    MINE("Mine"),
    /** Break ripe crops. Things are the crop blocks: "wheat", "carrots", "potatoes". */
    HARVEST("Harvest"),
    /** Kill mobs — not ones from spawners or spawn eggs. Things are mobs: "zombie", "skeleton". */
    KILL("Defeat"),
    /** Reel fish in with a rod. Things are the fish: "cod", "salmon". */
    FISH("Catch"),
    /** Breed animals. Things are the animals: "cow", "sheep". */
    BREED("Breed"),
    /** Travel, in blocks, on foot or riding. Things are not used. */
    TRAVEL("Travel");

    private final String verb;

    QuestTask(String verb) {
        this.verb = verb;
    }

    public String verb() {
        return verb;
    }
}
