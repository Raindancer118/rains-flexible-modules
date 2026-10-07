package de.raindancer.modules.anticheat.model;

/** One failure, written down. */
public record Evidence(long atMillis, String check, double level, String detail, String world, int x, int y,
                       int z, int ping, double tps) {

    private static final String SEPARATOR = "|";

    /** One line for the file; the detail goes last because it is the only part that may hold a bar. */
    public String encode() {
        return atMillis + SEPARATOR + check + SEPARATOR + String.format(java.util.Locale.ROOT, "%.2f", level)
                + SEPARATOR + ping + SEPARATOR + String.format(java.util.Locale.ROOT, "%.1f", tps) + SEPARATOR
                + world + SEPARATOR + x + SEPARATOR + y + SEPARATOR + z + SEPARATOR + detail;
    }

    public static java.util.Optional<Evidence> decode(String line) {
        if (line == null) {
            return java.util.Optional.empty();
        }
        String[] parts = line.split("\\|", 10);
        if (parts.length < 10) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(new Evidence(Long.parseLong(parts[0]), parts[1], Double.parseDouble(parts[2]),
                    parts[9], parts[5], Integer.parseInt(parts[6]), Integer.parseInt(parts[7]), Integer.parseInt(parts[8]),
                    Integer.parseInt(parts[3]), Double.parseDouble(parts[4])));
        } catch (RuntimeException malformed) {
            return java.util.Optional.empty();
        }
    }
}
