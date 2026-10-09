package de.raindancer.modules.essentials.service;

import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.essentials.EssentialsSettings;
import de.raindancer.modules.essentials.model.HouseRule;
import de.raindancer.modules.essentials.store.RuleBook;
import de.raindancer.modules.essentials.util.PermissionNodes;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RulesServiceTest {

    @TempDir
    Path folder;

    private final Messages messages = mock(Messages.class);
    private RuleBook book;
    private RulesService service;

    @BeforeEach
    void setUp() {
        book = new RuleBook(folder.resolve("rules.yml"), folder.resolve("rule-presets.yml"),
                () -> new ByteArrayInputStream("""
                        base:
                          rules:
                            - title: One
                              text: First.
                            - title: Two
                              text: Second.
                        """.getBytes(StandardCharsets.UTF_8)), "base");
        book.load();
        service = new RulesService(book, messages, mock(ChatButtons.class), EssentialsSettings.DEFAULTS);
        book.update(book.rules().getFirst().id(), rule -> rule.withEnabled(false));
    }

    @Test
    @DisplayName("players see only the rules switched on, numbered from one")
    void playersSeeShownOnes() {
        CommandSender player = mock(CommandSender.class);
        service.show(player);
        verify(messages).sendPlain(player, "essentials.rules.line", "number", 1, "title", "Two", "text", "Second.");
        verify(messages, never()).sendPlain(eq(player), eq("essentials.rules.line-off"), any(Object[].class));
    }

    @Test
    @DisplayName("managers see every rule, numbered as /rules remove counts them")
    void managersSeeAll() {
        CommandSender manager = mock(CommandSender.class);
        when(manager.hasPermission(PermissionNodes.RULES_MANAGE)).thenReturn(true);
        service.show(manager);
        verify(messages).sendPlain(manager, "essentials.rules.line-off", "number", 1, "title", "One", "text", "First.");
        verify(messages).sendPlain(manager, "essentials.rules.line", "number", 2, "title", "Two", "text", "Second.");
    }

    @Test
    @DisplayName("a too long title is refused and nothing is added")
    void refusesTooLong() {
        CommandSender manager = mock(CommandSender.class);
        assertThat(service.add(manager, "x".repeat(60), "text")).isEmpty();
        assertThat(book.rules()).hasSize(2);
        verify(messages).send(eq(manager), eq("essentials.rules.too-long"), any(Object[].class));
    }

    @Test
    @DisplayName("a typed preset name is meant as its plain form")
    void presetNamesAsTyped() {
        CommandSender manager = mock(CommandSender.class);
        assertThat(service.savePreset(manager, "Our Rules", "")).isTrue();
        assertThat(book.preset("our-rules")).isPresent();
        assertThat(service.applyPreset(manager, "Our Rules")).isTrue();
        assertThat(service.deletePreset(manager, "Our Rules")).isTrue();
        assertThat(book.preset("our-rules")).isEmpty();
    }

    @Test
    @DisplayName("the rules on a first join follow the setting")
    void firstJoin() {
        Player newcomer = mock(Player.class);
        service.firstJoin(newcomer);
        verify(messages).send(newcomer, "essentials.rules.header");

        Player other = mock(Player.class);
        service.settings(EssentialsSettings.DEFAULTS.withRulesOnFirstJoin(false));
        service.firstJoin(other);
        verify(messages, never()).send(eq(other), anyString(), any(Object[].class));
        verify(messages, never()).send(other, "essentials.rules.header");
    }

    @Test
    @DisplayName("taking one rule from a preset adds just that one, and never the same rule twice")
    void takeFromPreset() {
        CommandSender manager = mock(CommandSender.class);
        book.applyPreset("base");
        book.remove(book.rules().getLast().id());

        assertThat(service.takeFromPreset(manager, "base", 2)).isTrue();
        assertThat(book.rules()).extracting(HouseRule::title).containsExactly("One", "Two");
        verify(messages).send(manager, "essentials.rules.preset.took", "title", "Two", "number", 2);

        assertThat(service.takeFromPreset(manager, "base", 2)).as("already there").isFalse();
        verify(messages).send(manager, "essentials.rules.preset.already-have", "title", "Two");
        assertThat(service.takeFromPreset(manager, "base", 9)).isFalse();
        assertThat(service.takeFromPreset(manager, "nope", 1)).isFalse();
        assertThat(book.rules()).hasSize(2);
    }
}
