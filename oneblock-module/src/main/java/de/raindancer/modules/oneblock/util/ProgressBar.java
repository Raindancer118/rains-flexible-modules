package de.raindancer.modules.oneblock.util;

/** A bar of cells for a share in percent, as MiniMessage. Rounded down: full only when done. */
public final class ProgressBar {

    private static final String CELL = "▮";

    private ProgressBar() {
    }

    public static int filled(int percent, int cells) {
        return Math.clamp(percent, 0, 100) * cells / 100;
    }

    public static String mini(int percent, int cells) {
        int done = filled(percent, cells);
        StringBuilder bar = new StringBuilder();
        if (done > 0) {
            bar.append("<green>").append(CELL.repeat(done)).append("</green>");
        }
        if (done < cells) {
            bar.append("<dark_gray>").append(CELL.repeat(cells - done)).append("</dark_gray>");
        }
        return bar.toString();
    }
}
