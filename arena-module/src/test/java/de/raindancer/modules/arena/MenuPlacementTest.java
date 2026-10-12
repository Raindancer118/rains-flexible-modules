package de.raindancer.modules.arena;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Core's menu has three bands (1–3) of seven columns (1–7) and quietly moves anything outside onto the edge — where
 * a later button takes its place. Written after the lanista's menu lost Duel, Sumo, Teams of three and Horseback
 * that way: every button with a fixed place must have a real one, and a place only once per screen.
 */
class MenuPlacementTest {

    private static final Pattern BAND = Pattern.compile("band\\((\\d+),\\s*(\\d+),");

    @Test
    @DisplayName("every fixed button is in band 1–3, column 1–7, and no two share a place on one screen")
    void placements() throws IOException {
        List<String> wrong = new ArrayList<>();
        int seen = 0;
        try (Stream<Path> files = Files.list(Path.of("src/main/java/de/raindancer/modules/arena/screen"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Set<String> places = new HashSet<>();
                Matcher found = BAND.matcher(Files.readString(file));
                while (found.find()) {
                    seen++;
                    int band = Integer.parseInt(found.group(1));
                    int column = Integer.parseInt(found.group(2));
                    String where = file.getFileName() + " band(" + band + ", " + column + ")";
                    if (band < 1 || band > 3 || column < 1 || column > 7) {
                        wrong.add(where + " is outside the menu");
                    }
                    if (!places.add(band + ":" + column)) {
                        wrong.add(where + " is used twice");
                    }
                }
            }
        }
        assertThat(seen).as("the scan found the menus").isGreaterThan(20);
        assertThat(wrong).isEmpty();
    }
}
