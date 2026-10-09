package de.raindancer.modules.roles.service;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.social.economy.PriceModifiers;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.roles.RolesSettings;
import de.raindancer.modules.roles.model.Role;
import de.raindancer.modules.roles.store.ChoiceBook;
import de.raindancer.modules.roles.store.RoleCatalogue;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoleServiceTest {

    private static final long HOUR = Duration.ofHours(1).toMillis();

    @TempDir
    Path folder;

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final Messages messages = mock(Messages.class);
    private final Server server = mock(Server.class);
    private final Player tom = mock(Player.class);
    private final Player ana = mock(Player.class);
    private final UUID tomId = UUID.randomUUID();
    private RoleService service;
    private Role cook;
    private Role builder;

    @BeforeEach
    void start() {
        when(tom.getUniqueId()).thenReturn(tomId);
        when(tom.getName()).thenReturn("Tom");
        when(ana.getUniqueId()).thenReturn(UUID.randomUUID());
        when(server.getOnlinePlayers()).thenAnswer(call -> List.of(tom, ana));
        RoleCatalogue catalogue = new RoleCatalogue(new YamlStore(folder.resolve("roles.yml")), () ->
                new ByteArrayInputStream("""
                        roles:
                          cook:
                            perks: [ { buy: 25, items: [ bread ] } ]
                          builder:
                            perks: [ { buy: 20, items: [ stone ] } ]
                        """.getBytes(StandardCharsets.UTF_8)));
        catalogue.reload();
        ChoiceBook choices = new ChoiceBook(new YamlStore(folder.resolve("choices.yml")));
        choices.load();
        service = new RoleService(server, catalogue, choices, messages, now::get, RolesSettings.DEFAULTS);
        cook = catalogue.find("cook").orElseThrow();
        builder = catalogue.find("builder").orElseThrow();
    }

    @AfterEach
    void stop() {
        PriceModifiers.clear();
    }

    @Test
    @DisplayName("the first role is taken at once and announced to everybody else")
    void first() {
        assertThat(service.choose(tom, cook)).isTrue();
        assertThat(service.roleOf(tomId)).contains(cook);
        verify(messages).send(eq(tom), eq("roles.chosen"), any(Object[].class));
        verify(messages).send(eq(ana), eq("roles.announce"), any(Object[].class));
    }

    @Test
    @DisplayName("changing waits three days, then works")
    void threeDays() {
        service.choose(tom, cook);
        now.addAndGet(71 * HOUR);
        assertThat(service.choose(tom, builder)).isFalse();
        assertThat(service.roleOf(tomId)).contains(cook);
        assertThat(service.left(tomId)).isEqualTo(Duration.ofHours(1));
        verify(messages).send(eq(tom), eq("roles.wait"), any(Object[].class));
        now.addAndGet(HOUR);
        assertThat(service.choose(tom, builder)).isTrue();
        assertThat(service.roleOf(tomId)).contains(builder);
    }

    @Test
    @DisplayName("staff with the bypass change at once, quietly, and the bypass is forgotten when they leave")
    void bypass() {
        service.choose(tom, cook);
        org.mockito.Mockito.clearInvocations(messages);
        assertThat(service.toggleBypass(tomId)).isTrue();
        assertThat(service.choose(tom, builder)).isTrue();
        assertThat(service.choose(tom, cook)).isTrue();
        verify(messages, never()).send(eq(ana), eq("roles.announce"), any(Object[].class));
        service.forget(tomId);
        assertThat(service.bypassing(tomId)).isFalse();
        assertThat(service.choose(tom, builder)).isFalse();
    }

    @Test
    @DisplayName("staff can set, clear and end somebody's wait; the role survives a restart")
    void staff() {
        assertThat(service.set(tomId, Optional.of(builder))).isTrue();
        assertThat(service.left(tomId)).isEqualTo(Duration.ofHours(72));
        assertThat(service.endWait(tomId)).isTrue();
        assertThat(service.left(tomId)).isZero();
        assertThat(service.roleOf(tomId)).contains(builder);

        ChoiceBook reread = new ChoiceBook(new YamlStore(folder.resolve("choices.yml")));
        reread.load();
        assertThat(reread.of(tomId)).isPresent();

        assertThat(service.set(tomId, Optional.empty())).isTrue();
        assertThat(service.roleOf(tomId)).isEmpty();
    }

    @Test
    @DisplayName("the role's perks reach the shop's prices through Core, and stop when perks are switched off")
    void prices() {
        RolePrices prices = new RolePrices(service, () -> RolesSettings.DEFAULTS);
        PriceModifiers.provide(mock(org.bukkit.plugin.Plugin.class), prices);
        service.choose(tom, cook);
        assertThat(PriceModifiers.buy(tomId, "BREAD", Money.of(1000)).price()).isEqualTo(Money.of(750));
        assertThat(PriceModifiers.buy(tomId, "STONE", Money.of(1000)).price()).isEqualTo(Money.of(1000));
        assertThat(PriceModifiers.buy(UUID.randomUUID(), "BREAD", Money.of(1000)).price()).isEqualTo(Money.of(1000));

        PriceModifiers.clear();
        RolesSettings off = new RolesSettings(false, 72, true, true);
        PriceModifiers.provide(mock(org.bukkit.plugin.Plugin.class), new RolePrices(service, () -> off));
        assertThat(PriceModifiers.buy(tomId, "BREAD", Money.of(1000)).price()).isEqualTo(Money.of(1000));
    }

    @Test
    @DisplayName("when choices.yml cannot be read nobody changes role, and the file is left alone")
    void unreadable() throws Exception {
        Path file = folder.resolve("broken.yml");
        Files.writeString(file, "choices: [ : : nope");
        ChoiceBook broken = new ChoiceBook(new YamlStore(file));
        broken.load();
        RoleService stuck = new RoleService(server, new RoleCatalogue(new YamlStore(folder.resolve("roles.yml")),
                () -> null), broken, messages, now::get, RolesSettings.DEFAULTS);
        assertThat(stuck.choose(tom, cook)).isFalse();
        verify(messages).send(eq(tom), eq("roles.not-saved"), any(Object[].class));
        assertThat(Files.readString(file)).contains("nope");
    }
}
