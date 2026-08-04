package de.raindancer.modules.names;

import de.raindancer.modules.names.model.Reagent;
import de.raindancer.modules.names.store.Palette;
import de.raindancer.modules.names.store.PaletteFile;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The palette being read while it is replaced.
 *
 * <h2>Why this is worth a test</h2>
 * The palette is read from whichever region thread owns a crafting grid — on Folia that is one thread
 * per region, all of them at once — and replaced by whoever typed {@code /namestyle reload}. That is a
 * genuine race, and its consequences are not cosmetic: a craft resolved against one palette and charged
 * against another is a player paying a dye for a colour they did not get.
 *
 * <p>Two things have to hold, and only one of them is the {@code volatile}:
 *
 * <ul>
 *   <li><b>A reader never sees a half-built palette.</b> The map is filled before the field is assigned
 *       and never touched afterwards, so every read is of one complete palette — the old one or the new
 *       one, never a mixture.</li>
 *   <li><b>A reader that took a palette keeps working from it.</b> Everything that resolves a craft
 *       takes {@code current()} once and uses that instance, so a reload cannot change the answer half
 *       way through a single craft.</li>
 * </ul>
 */
class ConcurrencyTest {

    @TempDir
    Path folder;

    /** The two colours the file alternates between, neither of which is the shipped red. */
    private static final String FIRST = "#010203";
    private static final String SECOND = "#0a0b0c";

    @Test
    @DisplayName("a reader always sees one whole palette, never a mixture of two")
    void readingWhileItIsReplaced() throws Exception {
        PaletteFile file = new PaletteFile(folder.resolve("config.yml"));
        write(FIRST);
        file.load(warning -> {
        });

        int readers = 4;
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean();
        ConcurrentLinkedQueue<String> seen = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> broke = new ConcurrentLinkedQueue<>();

        List<Thread> threads = new java.util.ArrayList<>();
        for (int reader = 0; reader < readers; reader++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    while (!stop.get()) {
                        // Taken once and used, which is what every craft does. A palette taken and then
                        // asked twice must answer the same thing both times whatever the writer is up to.
                        Palette held = file.current();
                        Reagent first = held.reagentFor(Material.RED_DYE);
                        Reagent second = held.reagentFor(Material.RED_DYE);
                        if (first == null || !first.equals(second)) {
                            broke.add(new AssertionError("one palette answered two different things"));
                            return;
                        }
                        seen.add(((Reagent.Colour) first).colour().asHexString().toLowerCase());
                    }
                } catch (Throwable trouble) {
                    broke.add(trouble);
                }
            }, "palette-reader-" + reader);
            thread.setDaemon(true);
            threads.add(thread);
            thread.start();
        }

        start.countDown();
        for (int round = 0; round < 40; round++) {
            write(round % 2 == 0 ? SECOND : FIRST);
            file.load(warning -> {
            });
        }
        stop.set(true);
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(10));
        }

        assertThat(broke).isEmpty();
        assertThat(seen)
                .as("the readers never actually ran, so this proves nothing")
                .isNotEmpty();
        assertThat(Set.copyOf(seen))
                .as("a colour that is neither of the two the file ever held is a half-built palette")
                .isSubsetOf(FIRST, SECOND);
    }

    @Test
    @DisplayName("a palette handed out earlier is not changed by a later reload")
    void aHeldPaletteIsFrozen() {
        PaletteFile file = new PaletteFile(folder.resolve("config.yml"));
        write(FIRST);
        Palette held = file.load(warning -> {
        });

        write(SECOND);
        file.load(warning -> {
        });

        assertThat(((Reagent.Colour) held.reagentFor(Material.RED_DYE)).colour().asHexString())
                .as("the instance a craft was resolved against changed under it")
                .isEqualToIgnoringCase(FIRST);
        assertThat(((Reagent.Colour) file.current().reagentFor(Material.RED_DYE)).colour().asHexString())
                .isEqualToIgnoringCase(SECOND);
    }

    private void write(String red) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("colours.RED_DYE", red);
        yaml.set("decorations.IRON_INGOT", "bold");
        try {
            yaml.save(folder.resolve("config.yml").toFile());
        } catch (IOException cannot) {
            throw new AssertionError("could not write the fixture", cannot);
        }
    }
}
