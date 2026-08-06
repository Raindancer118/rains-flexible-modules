package de.raindancer.modules.hungergames.rules;

import de.raindancer.modules.hungergames.model.GamePhase;

import java.util.Set;

/**
 * The phase- and region-dependent protection matrix — pure decision, no Bukkit. The platform listener
 * translates events into {@link Query}s and asks {@link #shouldDeny}.
 *
 * <h2>The requirement this exists to get right</h2>
 * The cornucopia — the region around the arena's centre where tributes fight over the best loot at the
 * start — must be completely open to ordinary players once the round is {@code RUNNING}: breaking,
 * placing, opening chests, all of it. That is deliberate; it is where the round's opening fight happens,
 * and a plugin that protects it during the fight is a plugin that has misread what the region is for.
 * Before the round it is protected instead, so the loot is not stripped before anybody has arrived, and
 * both of those defaults are configurable rather than assumed.
 */
public final class ProtectionRules implements IHungerGamesRule {

    /** A named protected region. */
    public enum Region {
        /** The circle around the arena's centre (radius configurable elsewhere). */
        CORNUCOPIA,
        /** The rest of the arena. */
        ARENA
    }

    /** The kinds of action checked. */
    public enum ActionType {
        BREAK,
        PLACE,
        INTERACT,
        CONTAINER
    }

    /**
     * One protection question.
     *
     * @param region    the region the action happens in
     * @param action    what kind of action it is
     * @param phase     the round's current phase
     * @param hasBypass whether the player holds the bypass permission
     * @param material  the material name of the block or item, for the allow-list. Empty where there is no
     *                  material at all — see the note below; never null once constructed
     */
    public record Query(Region region, ActionType action, GamePhase phase, boolean hasBypass, String material) {

        /*
         * Why a null material becomes an empty string here.
         *
         * "No material" is a real state, not a mistake: an INTERACT on an entity or on air has no block and no
         * item behind it, and the listener that builds these has nothing honest to put in the field.
         *
         * Left as null it reaches `allowedMaterials().contains(material)`, and that set comes from
         * `Set.copyOf`, which throws NullPointerException on a null lookup rather than answering false. On a
         * hot path — every interaction of every player, several times a second during a round — and inside a
         * rule an event listener calls to decide whether to cancel. A rule that throws does not answer
         * "protected", it answers nothing: the listener unwinds, the event is never cancelled, and the
         * protection has silently stopped working. Somebody mines the cornucopia while the console fills up.
         *
         * Normalised in the constructor rather than guarded at the one use site, so a second use site cannot
         * reintroduce it.
         */
        public Query {
            material = material == null ? "" : material;
        }
    }

    /**
     * Configuration of the protection matrix.
     *
     * @param protectCornucopiaPreGame  protect the cornucopia before {@code RUNNING}
     * @param protectCornucopiaRunning  protect the cornucopia during {@code RUNNING} (default: no — see the class note)
     * @param protectCornucopiaFinished protect the cornucopia after the round ends
     * @param allowedMaterials          materials usable despite protection (e.g. {@code CHEST})
     */
    public record Config(
            boolean protectCornucopiaPreGame,
            boolean protectCornucopiaRunning,
            boolean protectCornucopiaFinished,
            Set<String> allowedMaterials) {

        public Config {
            allowedMaterials = Set.copyOf(allowedMaterials);
        }

        /** Protected before the round, open during it and after it. */
        public static Config defaults() {
            return new Config(true, false, false,
                    Set.of("CHEST", "TRAPPED_CHEST", "ENDER_CHEST", "BARREL", "SHULKER_BOX"));
        }
    }

    private final Config config;

    public ProtectionRules(Config config) {
        this.config = config;
    }

    /** Whether the action must be blocked. */
    public boolean shouldDeny(Query query) {
        if (query.hasBypass()) {
            return false;
        }
        if (query.region() != Region.CORNUCOPIA) {
            return false; // the arena is never plugin-protected
        }

        boolean protectedNow = switch (query.phase()) {
            case RUNNING -> config.protectCornucopiaRunning();
            case FINISHED -> config.protectCornucopiaFinished();
            // NOT_INITIALIZED/PREFLIGHT/LOBBY/STARTUP/READY = before the round
            default -> config.protectCornucopiaPreGame();
        };
        if (!protectedNow) {
            return false;
        }

        // Container interactions with an allowed material always stay possible.
        if ((query.action() == ActionType.CONTAINER || query.action() == ActionType.INTERACT)
                && config.allowedMaterials().contains(query.material())) {
            return false;
        }
        return true;
    }

    @Override
    public String describe() {
        return "what block, container and interaction protection applies right now";
    }
}
