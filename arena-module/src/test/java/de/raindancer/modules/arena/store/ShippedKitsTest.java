package de.raindancer.modules.arena.store;

import de.raindancer.modules.arena.model.Kit;
import de.raindancer.modules.arena.service.KitService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A built-in kit nobody edited follows the plugin's newer version; one an admin changed is left alone. */
class ShippedKitsTest {

    @TempDir
    Path folder;

    private static Kit lancer(String... items) {
        return new Kit("lancer", "Lancer", "IRON_SPEAR", List.of(items), List.of(), false);
    }

    private KitService service() {
        return new KitService(new KitBook(folder.resolve("kits.yml")), null);
    }

    @Test
    @DisplayName("an unedited built-in kit is replaced by the newer one at the next start")
    void followsTheShippedOne() {
        service().seed(List.of(lancer("spear")), new ShippedKits(folder.resolve("shipped-kits.yml")));
        KitService next = service();
        next.seed(List.of(lancer("spear", "bow", "trident")), new ShippedKits(folder.resolve("shipped-kits.yml")));
        assertThat(next.get("lancer").orElseThrow().items()).containsExactly("spear", "bow", "trident");
        assertThat(service().get("lancer").orElseThrow().items()).as("and saved").containsExactly("spear", "bow", "trident");
    }

    @Test
    @DisplayName("a built-in kit an admin changed stays theirs, whatever the plugin ships later")
    void editedStays() {
        service().seed(List.of(lancer("spear")), new ShippedKits(folder.resolve("shipped-kits.yml")));
        KitBook book = new KitBook(folder.resolve("kits.yml"));
        book.put(lancer("netherite spear"));
        KitService next = service();
        next.seed(List.of(lancer("spear", "bow")), new ShippedKits(folder.resolve("shipped-kits.yml")));
        assertThat(next.get("lancer").orElseThrow().items()).containsExactly("netherite spear");
    }

    @Test
    @DisplayName("a kit already there before fingerprints existed is left alone — nobody can tell whether it was edited")
    void unknownStays() {
        new KitBook(folder.resolve("kits.yml")).put(lancer("old spear"));
        KitService next = service();
        next.seed(List.of(lancer("spear", "bow")), new ShippedKits(folder.resolve("shipped-kits.yml")));
        assertThat(next.get("lancer").orElseThrow().items()).containsExactly("old spear");
    }
}
