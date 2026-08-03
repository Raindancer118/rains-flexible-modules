package de.raindancer.modules.wrapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The {@code paper-plugin.yml} a standalone module needs, checked before a server ever sees it.
 *
 * <h2>Why this is worth a class of its own</h2>
 * Because every way of getting it wrong fails at load time with a message naming something the author did not
 * write. The two that have actually happened here:
 *
 * <ul>
 *   <li>{@code depend: [RainsCore]} — the legacy {@code plugin.yml} spelling, <b>silently ignored</b> in a
 *       {@code paper-plugin.yml}. The plugin loads before RainsCore, with no access to its classes, and dies
 *       with {@code NoClassDefFoundError} naming a class the author has never heard of.</li>
 *   <li>a missing {@code join-classpath} — the dependency is declared, load order is right, and the classes
 *       still are not there, because Paper plugins get isolated classloaders.</li>
 * </ul>
 *
 * <p>Both are one line in a YAML file and neither is visible by reading it. So the wrapper generates the file
 * and this checks what it generates.
 */
class DescriptorTest {

    private final StandaloneDescriptor descriptor = StandaloneDescriptor.forPlugin("RainsClaims", "1.0.0");

    @Nested
    @DisplayName("what it must contain")
    class Required {

        @Test
        void namesTheWrapperAsTheMainClass() {
            // The point of the wrapper: a module ships as a plugin without anybody writing a plugin class.
            assertThat(descriptor.render())
                    .contains("main: " + ModulePlugin.class.getName());
        }

        @Test
        void namesTheBootstrapper() {
            // Not optional. Commands are registered during bootstrap, and a plugin with no bootstrapper never
            // gets the chance — the command simply does not exist, with nothing logged.
            assertThat(descriptor.render())
                    .contains("bootstrapper: " + ModuleBootstrap.class.getName());
        }

        @Test
        void declaresRainsCoreTheWayPaperActuallyReads() {
            String yaml = descriptor.render();

            assertThat(yaml).contains("dependencies:");
            assertThat(yaml).contains("RainsCore:");
            assertThat(yaml).contains("load: BEFORE");
            assertThat(yaml).contains("required: true");
            assertThat(yaml)
                    .as("without join-classpath the plugin loads and then dies the moment it touches a "
                            + "RainsCore class — declared, ordered, and still not on the classpath")
                    .contains("join-classpath: true");
        }

        @Test
        void neverUsesTheLegacySpelling() {
            // depend: is plugin.yml syntax. In a paper-plugin.yml it declares nothing at all, and the failure
            // names a class the author never wrote.
            assertThat(descriptor.render())
                    .as("depend: is silently ignored here, which is the worst possible outcome")
                    .doesNotContain("depend:");
        }

        @Test
        void carriesTheNameAndVersionItWasGiven() {
            assertThat(descriptor.render())
                    .contains("name: RainsClaims")
                    .contains("version: '1.0.0'");
        }

        @Test
        void declaresAnApiVersion() {
            assertThat(descriptor.render()).contains("api-version:");
        }
    }

    @Nested
    @DisplayName("what it refuses to make")
    class Refusals {

        @Test
        void aPluginWithNoName() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> StandaloneDescriptor.forPlugin("  ", "1.0.0"));
        }

        @Test
        void aPluginWithNoVersion() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> StandaloneDescriptor.forPlugin("RainsClaims", ""));
        }

        @Test
        void aNameWithASpaceInIt() {
            // Paper refuses these at load with a message about the file rather than about the name.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> StandaloneDescriptor.forPlugin("Rains Claims", "1.0.0"));
        }
    }

    @Nested
    @DisplayName("the optional parts")
    class Optional {

        @Test
        void areLeftOutWhenNothingWasSaid() {
            String yaml = descriptor.render();
            assertThat(yaml).doesNotContain("author:");
            assertThat(yaml).doesNotContain("description:");
        }

        @Test
        void appearWhenTheyAreGiven() {
            String yaml = descriptor
                    .describedAs("Land claims")
                    .by("Raindancer118")
                    .render();

            assertThat(yaml).contains("description: 'Land claims'");
            assertThat(yaml).contains("author: Raindancer118");
        }

        @Test
        void anotherPluginCanBeDeclaredAsWell() {
            // A module that wants a second dependency — a permissions plugin, an economy — should not have to
            // hand-write the file to get one.
            String yaml = descriptor.dependingOn("Vault", false).render();

            assertThat(yaml).contains("Vault:");
            assertThat(yaml).contains("required: false");
        }

        @Test
        void aDependencyIsNotDeclaredTwice() {
            String yaml = descriptor.dependingOn("RainsCore", true).render();

            assertThat(yaml.split("RainsCore:", -1))
                    .as("RainsCore is always declared; asking for it again must not duplicate it")
                    .hasSize(2);
        }
    }

    @Test
    @DisplayName("it reads as valid YAML, indentation and all")
    void theShapeIsRight() {
        List<String> lines = List.of(descriptor.render().split("\n"));

        // The nesting Paper wants is dependencies > server > <name> > keys, and getting the depth wrong is a
        // file that parses into something else entirely rather than failing.
        int dependencies = lines.indexOf("dependencies:");
        assertThat(dependencies).isNotNegative();
        assertThat(lines.get(dependencies + 1)).isEqualTo("  server:");
        assertThat(lines.get(dependencies + 2)).isEqualTo("    RainsCore:");
        assertThat(lines.get(dependencies + 3)).startsWith("      ");
    }

    @Test
    @DisplayName("no line has trailing whitespace, which some YAML readers do mind")
    void nothingTrails() {
        for (String line : descriptor.render().split("\n")) {
            assertThat(line).isEqualTo(line.stripTrailing());
        }
    }
}
