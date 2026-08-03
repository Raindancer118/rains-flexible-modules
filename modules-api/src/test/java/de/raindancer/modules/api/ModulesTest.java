package de.raindancer.modules.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one registry a host and its bootstrapper share.
 *
 * <p>It has to be reachable statically, and that is forced by Paper rather than chosen: commands are
 * registered from a {@code PluginBootstrap}, which runs before the plugin object exists and cannot be
 * handed anything by it. The bootstrapper and {@code onEnable} must nevertheless be talking about the
 * same modules — a command holding a module the host never enabled is a command wired to a dead object.
 *
 * <p>Statically per <em>classloader</em>, which is what makes it safe: Paper gives every plugin its own,
 * so two plugins built on this each get their own registry and cannot see each other's modules.
 */
class ModulesTest {

    private final List<String> journal = new ArrayList<>();

    @AfterEach
    void tearDown() {
        Modules.reset();
    }

    @Test
    void isTheSameRegistryEveryTimeItIsAsked() {
        assertThat(Modules.registry()).isSameAs(Modules.registry());
    }

    @Test
    void discoveryFillsIt() {
        Modules.discover(getClass().getClassLoader());
        assertThat(Modules.registry().declared()).extracting(m -> m.info().id()).contains("good");
    }

    @Test
    void discoveringTwiceDoesNotDoubleTheModules() {
        Modules.discover(getClass().getClassLoader());
        int after = Modules.registry().declared().size();
        Modules.discover(getClass().getClassLoader());
        assertThat(Modules.registry().declared()).hasSize(after);
    }

    @Test
    void discoveryProblemsAreKeptRatherThanSwallowed() {
        Modules.discover(getClass().getClassLoader());
        assertThat(Modules.registry().problems()).anySatisfy(problem ->
                assertThat(problem).contains("ExplodingModule"));
    }

    @Test
    void aResetGivesABlankRegistry() {
        Modules.registry().add(FakeModule.named("one", journal));
        Modules.reset();
        assertThat(Modules.registry().declared()).isEmpty();
    }

    @Test
    void aResetAfterEnablingLetsANewRunStartCleanly() {
        Modules.registry().add(FakeModule.named("one", journal));
        Modules.registry().enableAll(module -> new FakeSession(module.info().id(), journal));
        Modules.reset();

        Modules.registry().add(FakeModule.named("one", journal));
        Modules.registry().enableAll(module -> new FakeSession(module.info().id(), journal));
        assertThat(Modules.registry().isEnabled("one")).isTrue();
    }

    @Test
    void shutdownDisablesWhatWasRunningAndForgetsIt() {
        Modules.registry().add(FakeModule.named("one", journal));
        Modules.registry().enableAll(module -> new FakeSession(module.info().id(), journal));
        journal.clear();

        Modules.shutdown();

        assertThat(journal).containsExactly("disable:one", "unwind:one");
        assertThat(Modules.registry().declared()).isEmpty();
    }

    @Test
    void shutdownWithNothingRunningIsFine() {
        Modules.shutdown();
        assertThat(Modules.registry().declared()).isEmpty();
    }
}
