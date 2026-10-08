package de.raindancer.modules.moderation.model;

/**
 * An eight-block-thick slice of one kind of world. Ore density changes with height, so every rate
 * is compared within its own band: diamonds at -58 against diamonds at -58, never against the average.
 */
public record RockBand(String environment, int bottom) {

    public static final int HEIGHT = 8;

    public static RockBand of(String environment, int y) {
        return new RockBand(environment, Math.floorDiv(y, HEIGHT) * HEIGHT);
    }

    public String key() {
        return environment + ":" + bottom;
    }

    public static RockBand parse(String key) {
        int colon = key.lastIndexOf(':');
        return new RockBand(key.substring(0, colon), Integer.parseInt(key.substring(colon + 1)));
    }
}
