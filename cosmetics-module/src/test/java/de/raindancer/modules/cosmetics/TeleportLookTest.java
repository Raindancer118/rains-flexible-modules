package de.raindancer.modules.cosmetics;

import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.world.teleport.TravelLook;
import de.raindancer.modules.cosmetics.model.TeleportLookChoice;
import de.raindancer.modules.cosmetics.model.TeleportPart;
import de.raindancer.modules.cosmetics.rules.TeleportLookRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TeleportLookTest {

    private final TeleportLookRule rule = new TeleportLookRule();
    private static final List<String> OFFERED = List.of("entity.enderman.teleport", "block.bell.use");

    @Test
    @DisplayName("nothing chosen is the server's look; 'none' is silence or no particles; a choice is that")
    void toLook() {
        assertThat(TeleportLookChoice.SERVERS.toLook()).isEqualTo(TravelLook.SERVERS);

        TravelLook none = new TeleportLookChoice("none", "none", "none", "none").toLook();
        assertThat(none.depart()).isEqualTo(TravelLook.NOTHING_HEARD);
        assertThat(none.arrive()).isEqualTo(TravelLook.NOTHING_HEARD);
        assertThat(none.waitParticle()).isEmpty();
        assertThat(none.tick()).isEqualTo(TravelLook.NOTHING_HEARD);

        TravelLook chosen = new TeleportLookChoice("block.bell.use", null, "HEART", "block.note_block.pling").toLook();
        assertThat(chosen.depart().key()).isEqualTo("block.bell.use");
        assertThat(chosen.arrive()).isNull();
        assertThat(chosen.waitParticle()).isEqualTo("HEART");
        assertThat(chosen.tick().key()).isEqualTo("block.note_block.pling");
    }

    @Test
    @DisplayName("changing one part leaves the other two alone")
    void with() {
        TeleportLookChoice choice = TeleportLookChoice.SERVERS
                .with(TeleportPart.ARRIVE, "block.bell.use")
                .with(TeleportPart.WAIT, "none");
        assertThat(choice.depart()).isNull();
        assertThat(choice.arrive()).isEqualTo("block.bell.use");
        assertThat(choice.waitParticle()).isEqualTo("none");
        assertThat(choice.with(TeleportPart.ARRIVE, null).arrive()).isNull();
        assertThat(choice.with(TeleportPart.ARRIVE, null).with(TeleportPart.WAIT, null).isServers()).isTrue();
    }

    @Test
    @DisplayName("a sound from the server's list is fine; anything else needs the any-sound permission")
    void sounds() {
        assertThat(rule.judgeSound(true, true, "block.bell.use", OFFERED, false, true).isAllowed()).isTrue();
        Verdict notOffered = rule.judgeSound(true, true, "entity.ender_dragon.death", OFFERED, false, true);
        assertThat(notOffered.reason()).isEqualTo("cosmetics.teleport.sound-not-offered");
        assertThat(rule.judgeSound(true, true, "entity.ender_dragon.death", OFFERED, true, true).isAllowed()).isTrue();
        assertThat(rule.judgeSound(true, true, "no.such.sound", OFFERED, true, false).reason())
                .isEqualTo("cosmetics.teleport.sound-unknown");
    }

    @Test
    @DisplayName("switched off, or without the permission, nothing can be chosen — but going back to the default always can")
    void refusals() {
        assertThat(rule.judgeSound(false, true, "block.bell.use", OFFERED, false, true).reason())
                .isEqualTo("cosmetics.teleport.switched-off");
        assertThat(rule.judgeSound(true, false, "block.bell.use", OFFERED, false, true).reason())
                .isEqualTo("cosmetics.teleport.no-permission");
        assertThat(rule.judgeSound(false, false, null, OFFERED, false, false).isAllowed()).isTrue();
        assertThat(rule.judgeSound(true, true, "none", OFFERED, false, false).isAllowed()).isTrue();
    }
}
