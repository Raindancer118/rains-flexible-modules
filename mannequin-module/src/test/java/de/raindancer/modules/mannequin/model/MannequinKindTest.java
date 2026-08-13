package de.raindancer.modules.mannequin.model;

import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MannequinKindTest {

    @Test
    @DisplayName("exactly the five curated kinds exist, no more and no fewer")
    void exactlyFiveKinds() {
        assertThat(MannequinKind.values()).containsExactly(
                MannequinKind.PLAYER, MannequinKind.ZOMBIE, MannequinKind.SKELETON,
                MannequinKind.WITHER, MannequinKind.IRON_GOLEM);
    }

    @Test
    @DisplayName("each kind maps to the matching real Bukkit entity type")
    void bukkitTypesMatch() {
        assertThat(MannequinKind.PLAYER.bukkitType()).isEqualTo(EntityType.PLAYER);
        assertThat(MannequinKind.ZOMBIE.bukkitType()).isEqualTo(EntityType.ZOMBIE);
        assertThat(MannequinKind.SKELETON.bukkitType()).isEqualTo(EntityType.SKELETON);
        assertThat(MannequinKind.WITHER.bukkitType()).isEqualTo(EntityType.WITHER);
        assertThat(MannequinKind.IRON_GOLEM.bukkitType()).isEqualTo(EntityType.IRON_GOLEM);
    }

    @Test
    @DisplayName("only player, zombie and skeleton get a loadout screen")
    void loadoutSupport() {
        assertThat(MannequinKind.PLAYER.supportsLoadout()).isTrue();
        assertThat(MannequinKind.ZOMBIE.supportsLoadout()).isTrue();
        assertThat(MannequinKind.SKELETON.supportsLoadout()).isTrue();
        assertThat(MannequinKind.WITHER.supportsLoadout()).isFalse();
        assertThat(MannequinKind.IRON_GOLEM.supportsLoadout()).isFalse();
    }

    @Test
    @DisplayName("only player ever wears a skin")
    void onlyPlayerHasASkin() {
        for (MannequinKind kind : MannequinKind.values()) {
            assertThat(kind.supportsSkin()).isEqualTo(kind == MannequinKind.PLAYER);
        }
    }

    @Test
    @DisplayName("only zombie and skeleton would otherwise burn in daylight")
    void burnsInDaylight() {
        assertThat(MannequinKind.ZOMBIE.burnsInDaylight()).isTrue();
        assertThat(MannequinKind.SKELETON.burnsInDaylight()).isTrue();
        assertThat(MannequinKind.PLAYER.burnsInDaylight()).isFalse();
        assertThat(MannequinKind.WITHER.burnsInDaylight()).isFalse();
        assertThat(MannequinKind.IRON_GOLEM.burnsInDaylight()).isFalse();
    }

    @Test
    @DisplayName("byName is case-insensitive and empty for anything unrecognised")
    void byNameLookup() {
        assertThat(MannequinKind.byName("zombie")).contains(MannequinKind.ZOMBIE);
        assertThat(MannequinKind.byName("ZOMBIE")).contains(MannequinKind.ZOMBIE);
        assertThat(MannequinKind.byName("  Iron_Golem  ")).contains(MannequinKind.IRON_GOLEM);
        assertThat(MannequinKind.byName("dragon")).isEmpty();
        assertThat(MannequinKind.byName(null)).isEmpty();
        assertThat(MannequinKind.byName("")).isEmpty();
    }

    @Test
    @DisplayName("every kind has a human-readable display name")
    void displayNames() {
        assertThat(MannequinKind.PLAYER.displayName()).isEqualTo("Player");
        assertThat(MannequinKind.ZOMBIE.displayName()).isEqualTo("Zombie");
        assertThat(MannequinKind.SKELETON.displayName()).isEqualTo("Skeleton");
        assertThat(MannequinKind.WITHER.displayName()).isEqualTo("Wither");
        assertThat(MannequinKind.IRON_GOLEM.displayName()).isEqualTo("Iron Golem");
    }
}
