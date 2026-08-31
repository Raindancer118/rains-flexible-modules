package de.raindancer.modules.manhunt.model;

/**
 * One thing a host can throw at a running Manhunt, from the console or a menu click —
 * {@link de.raindancer.modules.manhunt.service.ChaosService} is what actually does it; this is only
 * the closed vocabulary of what "it" can be, so a command's tab completion, a menu's button grid and
 * the cooldown gate all read from the same list rather than three that can drift apart.
 */
public enum ChaosAction {

    /** Teleports every living participant to a randomly shuffled position among themselves — nobody
     *  leaves the roster, everybody just wakes up somewhere else on it. */
    SWAP_POSITIONS("Swap positions",
            "Shuffles every living Runner and Hunter to a randomly chosen spot another one of them "
                    + "was just standing on."),

    /** A short, one-off "somebody knows roughly where you are" moment for the Hunters. */
    REVEAL_RUNNERS("Reveal the Runners",
            "Makes every living Runner glow, visible to everybody, for a short while."),

    /** A temporary edge for whichever side is falling behind. */
    HASTE_HUNTERS("Haste for the Hunters",
            "Gives every living Hunter a short burst of Speed."),

    SLOW_RUNNERS("Slow the Runners",
            "Gives every living Runner a short bout of Slowness."),

    /** Cosmetic only — see {@code ChaosService.strikeNearRandomRunner} for why it never damages anybody. */
    LIGHTNING_ON_A_RUNNER("Lightning on a Runner",
            "A cosmetic lightning strike at a randomly chosen living Runner's feet — no damage, no fire."),

    FLIP_WEATHER("Flip the weather",
            "Toggles a storm on or off in the Manhunt world.");

    private final String label;
    private final String description;

    ChaosAction(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }
}
