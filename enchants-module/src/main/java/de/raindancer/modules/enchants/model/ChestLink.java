package de.raindancer.modules.enchants.model;

import java.util.Optional;
import java.util.UUID;

/** Where a Chest Link tool sends what it mines, as kept on the tool: {@code world-uuid;x;y;z}. */
public record ChestLink(UUID world, int x, int y, int z) {

    public String write() {
        return world + ";" + x + ";" + y + ";" + z;
    }

    public static Optional<ChestLink> read(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String[] parts = text.split(";");
        if (parts.length != 4) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ChestLink(UUID.fromString(parts[0]), Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]), Integer.parseInt(parts[3])));
        } catch (IllegalArgumentException broken) {
            return Optional.empty();
        }
    }
}
