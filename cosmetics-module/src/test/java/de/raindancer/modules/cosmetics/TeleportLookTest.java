package de.raindancer.modules.cosmetics;

import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.world.teleport.TravelLook;
import de.raindancer.core.ui.effect.ParticleShape;
import de.raindancer.modules.cosmetics.model.ParticleChoice;
import de.raindancer.modules.cosmetics.model.ParticleDensity;
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

    @Test
    @DisplayName("a choice no longer allowed is dropped back to the server's, the rest is kept, and nothing is never dropped")
    void revalidate() {
        TeleportLookChoice choice = new TeleportLookChoice("entity.ender_dragon.death", "block.bell.use", "none", "block.note_block.pling");
        TeleportLookChoice kept = choice.keeping((part, value) -> !value.equals("entity.ender_dragon.death"));
        assertThat(kept.depart()).isNull();
        assertThat(kept.arrive()).isEqualTo("block.bell.use");
        assertThat(kept.waitParticle()).isEqualTo("none");
        assertThat(kept.tick()).isEqualTo("block.note_block.pling");

        assertThat(choice.keeping((part, value) -> false).waitParticle())
                .as("choosing nothing needs no permission").isEqualTo("none");
    }

    @Test
    @DisplayName("the waiting particle keeps its shape, colour and density, as a worn particle does, and Core gets all three")
    void waitStyle() {
        TeleportLookChoice choice = TeleportLookChoice.SERVERS.with(TeleportPart.WAIT, "DUST")
                .withWait(new ParticleChoice("DUST", ParticleShape.HALO, 0xFF8800, ParticleDensity.DENSE));

        TravelLook look = choice.toLook();
        assertThat(look.waitParticle()).isEqualTo("DUST");
        assertThat(look.waitShape()).isEqualTo(ParticleShape.HALO);
        assertThat(look.waitColour()).isEqualTo(0xFF8800);
        assertThat(look.waitDensity()).isEqualTo(ParticleDensity.DENSE.count() * 2);
        assertThat(choice.waitStyle().particle()).isEqualTo("DUST");
        assertThat(choice.withWait(choice.waitStyle().withColourTo(0x0000FF)).toLook().waitColourTo())
                .isEqualTo(0x0000FF);

        // Picking another particle keeps how it is drawn.
        TeleportLookChoice flame = choice.with(TeleportPart.WAIT, "FLAME");
        assertThat(flame.toLook().waitShape()).isEqualTo(ParticleShape.HALO);
        assertThat(flame.toLook().waitColour()).isEqualTo(0xFF8800);
    }

    @Test
    @DisplayName("not chosen, the shape and density are the server's")
    void waitStyleUnset() {
        TravelLook look = TeleportLookChoice.SERVERS.with(TeleportPart.WAIT, "HEART").toLook();

        assertThat(look.waitShape()).isNull();
        assertThat(look.waitColour()).isNull();
        assertThat(look.waitDensity()).isNull();
    }
}
