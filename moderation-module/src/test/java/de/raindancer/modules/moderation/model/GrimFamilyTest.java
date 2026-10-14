package de.raindancer.modules.moderation.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class GrimFamilyTest {

    @ParameterizedTest(name = "{0} → {1}")
    @DisplayName("each of Grim's checks lands in the family it belongs to")
    @CsvSource({
            "Simulation, GRIM_MOVEMENT", "GroundSpoof, GRIM_MOVEMENT", "NoFall, GRIM_MOVEMENT", "Phase, GRIM_MOVEMENT",
            "Timer, GRIM_MOVEMENT", "NegativeTimer, GRIM_MOVEMENT", "VehicleA, GRIM_MOVEMENT", "ElytraC, GRIM_MOVEMENT",
            "SprintB, GRIM_MOVEMENT", "NoSlow, GRIM_MOVEMENT", "FlightA, GRIM_MOVEMENT", "Baritone, GRIM_MOVEMENT",
            "Reach, GRIM_COMBAT", "Hitboxes, GRIM_COMBAT", "AimModulo360, GRIM_COMBAT", "AimDuplicateLook, GRIM_COMBAT",
            "MultiInteractA, GRIM_COMBAT", "InvalidInteractTarget, GRIM_COMBAT", "SelfInteract, GRIM_COMBAT",
            "AntiKB, GRIM_COMBAT", "AntiExplosion, GRIM_COMBAT",
            "FastBreak, GRIM_WORLD", "NoSwingBreak, GRIM_WORLD", "PositionBreakA, GRIM_WORLD", "AirLiquidPlace, GRIM_WORLD",
            "FarPlace, GRIM_WORLD", "InvalidPlaceFace, GRIM_WORLD", "RotationPlace, GRIM_WORLD", "GhostBlockMitigation, GRIM_WORLD",
            "InventoryA, GRIM_INVENTORY",
            "BadPacketsX, GRIM_PACKETS", "PacketOrderK, GRIM_PACKETS", "CrashD, GRIM_PACKETS", "ExploitA, GRIM_PACKETS",
            "Post, GRIM_PACKETS", "TransactionOrder, GRIM_PACKETS", "MultiActionsB, GRIM_PACKETS", "ChatC, GRIM_PACKETS",
            "ClientBrand, GRIM_PACKETS"
    })
    void families(String check, CheckType family) {
        assertThat(GrimFamily.of(check)).isEqualTo(family);
    }

    @ParameterizedTest
    @DisplayName("a check Grim adds later, or no name at all, counts as movement — what Grim mostly checks")
    @CsvSource({"SomethingNew", "''"})
    void unknown(String check) {
        assertThat(GrimFamily.of(check)).isEqualTo(CheckType.GRIM_MOVEMENT);
    }
}
