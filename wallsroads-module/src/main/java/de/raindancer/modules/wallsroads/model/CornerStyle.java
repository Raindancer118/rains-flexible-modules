package de.raindancer.modules.wallsroads.model;

/**
 * How a wall's corners are built: sharp (the polygon's own vertices) or rounded to a radius.
 * {@code radius == 0} means sharp — kept as one type rather than a sealed hierarchy because that is
 * the whole difference, and every caller already has to clamp a radius anyway.
 */
public record CornerStyle(int radius) {

    public static final CornerStyle SHARP = new CornerStyle(0);

    public static CornerStyle rounded(int radius) {
        return new CornerStyle(Math.max(1, radius));
    }

    public boolean isRounded() {
        return radius > 0;
    }
}
