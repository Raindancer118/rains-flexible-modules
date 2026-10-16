package de.raindancer.modules.ctf.model;

/** What the end of a round hands out. */
public enum Award {
    MVP("MVP", "most points"),
    BEST_DEFENDER("Best Defender", "most flags returned and defenders' kills"),
    SPEED_DEMON("Speed Demon", "fastest capture"),
    BUTTERFINGERS("Butterfingers", "most flags dropped"),
    BODYGUARD("Bodyguard", "most kills while escorting a carrier"),
    FLAG_RUNNER("Flag Runner", "longest time carrying a flag");

    private final String title;
    private final String forWhat;

    Award(String title, String forWhat) {
        this.title = title;
        this.forWhat = forWhat;
    }

    public String title() {
        return title;
    }

    public String forWhat() {
        return forWhat;
    }
}
