package de.raindancer.modules.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finding the modules a jar brought with it.
 *
 * <p>Run against a real {@code ServiceLoader} and a real service file — {@code
 * src/test/resources/META-INF/services/de.raindancer.modules.api.FlexModule} — with the two failures
 * that actually happen in it: a module whose constructor throws, and a class name left behind after a
 * rename. A stand-in for {@code ServiceLoader} would prove nothing about either, because the whole
 * question is what the JDK does when a provider cannot be built.
 *
 * <p>Neither may take the host down. A standalone jar with one broken module is a plugin that fails to
 * load, which is fine; a host plugin with six modules and one broken one must load the other five.
 */
class ModuleDiscoveryTest {

    @Test
    void findsTheModulesThatCanBeBuilt() {
        ModuleDiscovery.Discovered found = ModuleDiscovery.onClasspath(getClass().getClassLoader());

        assertThat(found.modules()).extracting(m -> m.info().id()).containsExactly("good");
    }

    @Test
    void reportsTheOneWhoseConstructorThrew() {
        ModuleDiscovery.Discovered found = ModuleDiscovery.onClasspath(getClass().getClassLoader());

        assertThat(found.problems()).anySatisfy(problem ->
                assertThat(problem).contains("ExplodingModule"));
    }

    @Test
    void reportsTheOneThatIsNotThereAtAll() {
        ModuleDiscovery.Discovered found = ModuleDiscovery.onClasspath(getClass().getClassLoader());

        assertThat(found.problems()).anySatisfy(problem ->
                assertThat(problem).contains("ModuleThatWasRenamedAndNobodyUpdatedThisFile"));
    }

    @Test
    void findsNothingAndComplainsAboutNothingWhenThereIsNoServiceFile() {
        // A classloader that can see the API but has no service file of its own.
        ClassLoader empty = new ClassLoader(null) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                return ModuleDiscoveryTest.class.getClassLoader().loadClass(name);
            }
        };
        ModuleDiscovery.Discovered found = ModuleDiscovery.onClasspath(empty);

        assertThat(found.modules()).isEmpty();
        assertThat(found.problems()).isEmpty();
    }

    @Test
    void survivesBeingAskedTwice() {
        assertThat(ModuleDiscovery.onClasspath(getClass().getClassLoader()).modules())
                .hasSameSizeAs(ModuleDiscovery.onClasspath(getClass().getClassLoader()).modules());
    }

    @Test
    void givesFreshInstancesEachTime() {
        FlexModule first = ModuleDiscovery.onClasspath(getClass().getClassLoader()).modules().getFirst();
        FlexModule second = ModuleDiscovery.onClasspath(getClass().getClassLoader()).modules().getFirst();
        assertThat(first).isNotSameAs(second);
    }

    @Test
    void whatItFoundIsUnmodifiable() {
        ModuleDiscovery.Discovered found = ModuleDiscovery.onClasspath(getClass().getClassLoader());
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> found.modules().clear()))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
