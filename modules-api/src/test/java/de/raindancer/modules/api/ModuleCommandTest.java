package de.raindancer.modules.api;

import io.papermc.paper.command.brigadier.BasicCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A command a module brings with it.
 *
 * <p>Checked here rather than at registration because Paper registers commands during bootstrap: a
 * name it does not like there fails a plugin load with a stack trace pointing at Brigadier, and the
 * module that wrote the bad name is not mentioned anywhere in it.
 */
class ModuleCommandTest {

    private static final BasicCommand NOTHING = (source, args) -> {
    };

    @ParameterizedTest
    @ValueSource(strings = {"mod", "farmworld", "mod-log", "warn2"})
    void acceptsAPlainLowercaseName(String name) {
        assertThat(ModuleCommand.of(name, "a description", NOTHING).name()).isEqualTo(name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Mod", "mod eration", "mod:eration", "-mod", "mod-", "2mod", ""})
    void refusesAnythingElse(String name) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ModuleCommand.of(name, "a description", NOTHING));
    }

    @Test
    void saysSoWhenTheNameCameWithItsSlash() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ModuleCommand.of("/mod", "a description", NOTHING))
                .withMessageContaining("slash");
    }

    @Test
    void wantsADescriptionBecausePaperShowsItInHelp() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ModuleCommand.of("mod", "  ", NOTHING));
    }

    @Test
    void wantsSomethingToRun() {
        assertThat(catchThrowable(() -> ModuleCommand.of("mod", "a description", null)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void hasNoAliasesUntilGivenSome() {
        assertThat(ModuleCommand.of("mod", "d", NOTHING).aliases()).isEmpty();
    }

    @Test
    void keepsAliasesInTheOrderGiven() {
        assertThat(ModuleCommand.of("moderation", "d", NOTHING).aliased("mod", "m").aliases())
                .containsExactly("mod", "m");
    }

    @Test
    void holdsAliasesToTheSameRulesAsNames() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ModuleCommand.of("mod", "d", NOTHING).aliased("Mod Log"));
    }

    @Test
    void refusesAnAliasThatIsTheNameAgain() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ModuleCommand.of("mod", "d", NOTHING).aliased("mod"))
                .withMessageContaining("mod");
    }

    @Test
    void refusesTheSameAliasTwice() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ModuleCommand.of("mod", "d", NOTHING).aliased("m", "m"));
    }

    @Test
    void namesIsTheNameAndThenTheAliases() {
        assertThat(ModuleCommand.of("moderation", "d", NOTHING).aliased("mod", "m").names())
                .containsExactly("moderation", "mod", "m");
    }

    @Test
    void aliasesAreUnmodifiable() {
        ModuleCommand command = ModuleCommand.of("mod", "d", NOTHING).aliased("m");
        assertThat(catchThrowable(() -> command.aliases().add("sneaky")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aliasingTwiceAccumulates() {
        assertThat(ModuleCommand.of("mod", "d", NOTHING).aliased("a").aliased("b").aliases())
                .containsExactly("a", "b");
    }
}
