package de.raindancer.modules.performance.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClassIndexTest {

    @Test
    @DisplayName("a class shipped by one plugin is that plugin's; one shaded into several is nobody's")
    void owners() {
        ClassIndex index = new ClassIndex();
        index.add("RainsEconomy", List.of("de/raindancer/modules/economy/EconomyModule.class",
                "de/raindancer/modules/wrapper/ModulePlugin.class", "META-INF/MANIFEST.MF"));
        index.add("RainsHomes", List.of("de/raindancer/modules/homes/HomesModule.class",
                "de/raindancer/modules/wrapper/ModulePlugin.class"));

        assertThat(index.pluginOf("de.raindancer.modules.economy.EconomyModule")).contains("RainsEconomy");
        assertThat(index.pluginOf("de.raindancer.modules.homes.HomesModule")).contains("RainsHomes");
        assertThat(index.pluginOf("de.raindancer.modules.wrapper.ModulePlugin")).isEmpty();
        assertThat(index.pluginOf("net.minecraft.world.entity.Mob")).isEmpty();
    }

    @Test
    @DisplayName("inner and lambda classes belong to their outer class's plugin")
    void innerClasses() {
        ClassIndex index = new ClassIndex();
        index.add("RainsAntiCheat", List.of("de/raindancer/modules/anticheat/Engine.class",
                "de/raindancer/modules/anticheat/Engine$Track.class"));

        assertThat(index.pluginOf("de.raindancer.modules.anticheat.Engine$Track")).contains("RainsAntiCheat");
        assertThat(index.pluginOf("de.raindancer.modules.anticheat.Engine$$Lambda/0x0000123")).contains("RainsAntiCheat");
    }
}
