package de.raindancer.modules.essentials.store;

import de.raindancer.modules.essentials.model.HouseRule;
import de.raindancer.modules.essentials.model.RulePreset;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBookTest {

    private static final String BUNDLED = """
            friendly-smp:
              description: Be nice, build things
              rules:
                - title: Be kind
                  text: No insults, no harassment.
                  icon: poppy
                - title: No griefing
                  text: Leave what others built alone.
                  icon: tnt
                  punishments: warn, ban 3d, ban
            anarchy:
              description: Anything goes
              rules:
                - title: No rules
                  text: Except this one.
            """;

    private static InputStream bundled() {
        return new ByteArrayInputStream(BUNDLED.getBytes(StandardCharsets.UTF_8));
    }

    private static RuleBook book(Path folder) {
        RuleBook book = new RuleBook(folder.resolve("rules.yml"), folder.resolve("rule-presets.yml"),
                RuleBookTest::bundled, "friendly-smp");
        book.load();
        return book;
    }

    @Test
    @DisplayName("a new server starts with the bundled presets and the default one's rules")
    void startsFromTheDefaultPreset(@TempDir Path folder) {
        RuleBook book = book(folder);

        assertThat(book.presets()).extracting(RulePreset::name).containsExactly("friendly-smp", "anarchy");
        assertThat(book.rules()).extracting(HouseRule::title).containsExactly("Be kind", "No griefing");
        assertThat(book.rules().getFirst().icon()).isEqualTo(Material.POPPY);
        assertThat(Files.exists(folder.resolve("rules.yml"))).isTrue();
    }

    @Test
    @DisplayName("added, edited, moved and removed rules survive a restart, in their order")
    void editsSurvive(@TempDir Path folder) {
        RuleBook book = book(folder);
        HouseRule added = book.add("No lag machines", "Redstone clocks get removed.");
        book.update(added.id(), rule -> rule.withIcon(Material.REDSTONE).withEnabled(false));
        assertThat(book.move(added.id(), -1)).isTrue();
        HouseRule first = book.rules().getFirst();
        assertThat(book.remove(first.id())).contains(first);

        RuleBook again = book(folder);
        assertThat(again.rules()).extracting(HouseRule::title).containsExactly("No lag machines", "No griefing");
        assertThat(again.rules().getFirst().icon()).isEqualTo(Material.REDSTONE);
        assertThat(again.rules().getFirst().enabled()).isFalse();
        assertThat(again.shown()).extracting(HouseRule::title).containsExactly("No griefing");
    }

    @Test
    @DisplayName("moving past either end changes nothing")
    void moveAtTheEdges(@TempDir Path folder) {
        RuleBook book = book(folder);
        HouseRule first = book.rules().getFirst();
        assertThat(book.move(first.id(), -1)).isFalse();
        assertThat(book.move(book.rules().getLast().id(), 1)).isFalse();
        assertThat(book.move("nope", 1)).isFalse();
    }

    @Test
    @DisplayName("an empty rule list stays empty after a restart — it is not refilled from the preset")
    void emptyStaysEmpty(@TempDir Path folder) {
        RuleBook book = book(folder);
        book.rules().forEach(rule -> book.remove(rule.id()));
        assertThat(book(folder).rules()).isEmpty();
    }

    @Test
    @DisplayName("applying a preset replaces every rule; saving one keeps the current rules under a name")
    void presets(@TempDir Path folder) {
        RuleBook book = book(folder);
        book.add("Extra", "Only on this server.");
        assertThat(book.savePreset("mine", "Our own")).isTrue();

        assertThat(book.applyPreset("anarchy")).isTrue();
        assertThat(book.rules()).extracting(HouseRule::title).containsExactly("No rules");

        assertThat(book.applyPreset("mine")).isTrue();
        assertThat(book.rules()).extracting(HouseRule::title).containsExactly("Be kind", "No griefing", "Extra");
        assertThat(book.applyPreset("nope")).isFalse();

        RuleBook again = book(folder);
        assertThat(again.preset("mine")).get().extracting(RulePreset::description).isEqualTo("Our own");
        assertThat(again.deletePreset("mine")).isTrue();
        assertThat(book(folder).preset("mine")).isEmpty();
        assertThat(again.deletePreset("mine")).isFalse();
    }

    @Test
    @DisplayName("rules from a preset get ids of their own, so applying it twice never shares one")
    void freshIds(@TempDir Path folder) {
        RuleBook book = book(folder);
        String before = book.rules().getFirst().id();
        book.applyPreset("friendly-smp");
        assertThat(book.rules().getFirst().id()).isNotEqualTo(before);
        assertThat(book.rules()).extracting(HouseRule::id).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("a deleted preset file is written again from the jar; an edited one is left as it is")
    void presetsFile(@TempDir Path folder) throws Exception {
        book(folder);
        Files.writeString(folder.resolve("rule-presets.yml"), """
                own:
                  rules:
                    - title: Only mine
                      text: Hand-written.
                      icon: not_a_material
                """);
        RuleBook book = book(folder);
        assertThat(book.presets()).extracting(RulePreset::name).containsExactly("own");
        assertThat(book.preset("own").orElseThrow().rules().getFirst().icon()).isEqualTo(Material.PAPER);
    }

    @Test
    @DisplayName("a rule is found by its number as players see it, counting from one")
    void byNumber(@TempDir Path folder) {
        RuleBook book = book(folder);
        assertThat(book.byNumber(2)).get().extracting(HouseRule::title).isEqualTo("No griefing");
        assertThat(book.byNumber(0)).isEmpty();
        assertThat(book.byNumber(3)).isEmpty();
    }

    @Test
    @DisplayName("a single rule taken from a preset is added at the end, the other rules untouched")
    void takeOne(@TempDir Path folder) {
        RuleBook book = book(folder);
        HouseRule taken = book.preset("anarchy").orElseThrow().rules().getFirst();

        HouseRule added = book.addCopy(taken);

        assertThat(book.rules()).extracting(HouseRule::title).containsExactly("Be kind", "No griefing", "No rules");
        assertThat(added.id()).isNotNull().isNotEqualTo(taken.id());
        assertThat(book(folder).rules()).hasSize(3);
        assertThat(book.has(taken)).isTrue();
        assertThat(book.has(book.preset("friendly-smp").orElseThrow().rules().getLast())).isTrue();
    }

    @Test
    @DisplayName("a rule's punishments come from its preset, can be changed, and survive a restart")
    void punishments(@TempDir Path folder) {
        RuleBook book = book(folder);
        HouseRule griefing = book.rules().get(1);
        assertThat(de.raindancer.core.moderation.rules.RulePenalty.write(griefing.penalties()))
                .isEqualTo("warn, ban 3d, ban");
        assertThat(book.rules().getFirst().penalties()).isEmpty();

        book.update(griefing.id(), rule -> rule.withPenalties(
                de.raindancer.core.moderation.rules.RulePenalty.ladder("mute 1h, ban").orElseThrow()));
        HouseRule again = book(folder).rules().get(1);
        assertThat(de.raindancer.core.moderation.rules.RulePenalty.write(again.penalties())).isEqualTo("mute 1h, ban");

        assertThat(book.savePreset("mine", "")).isTrue();
        assertThat(de.raindancer.core.moderation.rules.RulePenalty.write(
                book(folder).preset("mine").orElseThrow().rules().get(1).penalties())).isEqualTo("mute 1h, ban");
    }
}
