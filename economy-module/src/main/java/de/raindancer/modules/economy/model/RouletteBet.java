package de.raindancer.modules.economy.model;

/**
 * What a roulette chip is on.
 *
 * @param number the number for {@link Kind#NUMBER}; ignored otherwise
 */
public record RouletteBet(Kind kind, int number) {

    public enum Kind {
        RED("Red"), BLACK("Black"), GREEN("Green (0)"), EVEN("Even"), ODD("Odd"), LOW("1 to 18"), HIGH("19 to 36"),
        FIRST_DOZEN("1st dozen"), SECOND_DOZEN("2nd dozen"), THIRD_DOZEN("3rd dozen"), NUMBER("Number");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public static RouletteBet on(Kind kind) {
        return new RouletteBet(kind, 0);
    }

    public static RouletteBet number(int number) {
        return new RouletteBet(Kind.NUMBER, Math.max(0, Math.min(36, number)));
    }

    public String label() {
        return kind == Kind.NUMBER ? "Number " + number : kind.label();
    }
}
