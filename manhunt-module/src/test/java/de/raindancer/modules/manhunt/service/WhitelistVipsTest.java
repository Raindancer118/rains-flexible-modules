package de.raindancer.modules.manhunt.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The few people a cleared or closed whitelist never touches. See {@link WhitelistVips} for why this
 * is a file of its own rather than a settings field.
 */
class WhitelistVipsTest {

    private static final UUID ANNA = UUID.nameUUIDFromBytes("anna".getBytes());
    private static final UUID BEN = UUID.nameUUIDFromBytes("ben".getBytes());

    @TempDir
    Path directory;

    private WhitelistVips vips() {
        return new WhitelistVips(directory.resolve("whitelist-vips.yml"));
    }

    @Test
    @DisplayName("somebody added is a VIP, and somebody else is not")
    void addsAndRecognises() {
        WhitelistVips vips = vips();

        assertThat(vips.add(ANNA, "Anna")).isTrue();

        assertThat(vips.isVip(ANNA)).isTrue();
        assertThat(vips.isVip(BEN)).isFalse();
        assertThat(vips.ids()).containsExactly(ANNA);
    }

    @Test
    @DisplayName("adding the same person twice says so rather than listing them twice")
    void addingTwiceIsRefused() {
        WhitelistVips vips = vips();
        vips.add(ANNA, "Anna");

        assertThat(vips.add(ANNA, "Anna")).isFalse();
        assertThat(vips.ids()).containsExactly(ANNA);
    }

    @Test
    @DisplayName("a rename is kept: the same id under whatever name they wear now")
    void keepsTheLatestName() {
        WhitelistVips vips = vips();
        vips.add(ANNA, "Anna");

        vips.add(ANNA, "Annabel");

        assertThat(vips.names()).containsExactly("Annabel");
    }

    @Test
    @DisplayName("removing takes them off, and removing somebody who was never on says so")
    void removes() {
        WhitelistVips vips = vips();
        vips.add(ANNA, "Anna");

        assertThat(vips.remove(ANNA)).isTrue();
        assertThat(vips.remove(ANNA)).isFalse();
        assertThat(vips.isVip(ANNA)).isFalse();
    }

    @Test
    @DisplayName("a VIP can be found by the name they were added under, whatever the case")
    void findsByName() {
        WhitelistVips vips = vips();
        vips.add(ANNA, "Anna");

        assertThat(vips.byName("anna")).contains(ANNA);
        assertThat(vips.byName("ANNA")).contains(ANNA);
        assertThat(vips.byName("ben")).isEmpty();
    }

    @Test
    @DisplayName("the list survives a restart")
    void survivesAReload() {
        WhitelistVips first = vips();
        first.add(ANNA, "Anna");
        first.add(BEN, "Ben");
        first.remove(BEN);

        WhitelistVips reloaded = vips();

        assertThat(reloaded.ids()).containsExactly(ANNA);
        assertThat(reloaded.names()).containsExactly("Anna");
    }

    @Test
    @DisplayName("no file yet is an empty list, not a failure")
    void missingFileIsEmpty() {
        assertThat(vips().ids()).isEmpty();
    }
}
