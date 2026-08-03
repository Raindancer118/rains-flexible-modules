package de.raindancer.modules.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * What a module is allowed to call itself.
 *
 * <p>The id is not decoration: it names a folder, a permission node and a settings section, and it is
 * what one module writes down to depend on another. So it is checked here, once, rather than at each
 * of those places — and the checks are strict on purpose. A module id containing a slash would be a
 * path outside the data folder the moment it reached {@link ModuleLayout}.
 */
class ModuleInfoTest {

    @Nested
    @DisplayName("the id")
    class Ids {

        @ParameterizedTest
        @ValueSource(strings = {"moderation", "farm-world", "a", "x2", "one-two-three", "a1-b2"})
        void acceptsLowercaseKebab(String id) {
            assertThatCode(() -> ModuleInfo.of(id, "A name", "1.0.0")).doesNotThrowAnyException();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "Moderation",        // upper case
                "moderation_module", // underscore
                "moderation module", // space
                "-moderation",       // leading dash
                "moderation-",       // trailing dash
                "farm--world",       // doubled dash
                "2fast",             // leading digit
                "mod/eration",       // a path separator, which ModuleLayout would obey
                "../escape",
                "mod.eration",
        })
        void refusesAnythingElse(String id) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ModuleInfo.of(id, "A name", "1.0.0"))
                    .withMessageContaining(id);
        }

        @Test
        void refusesBlank() {
            assertThatIllegalArgumentException().isThrownBy(() -> ModuleInfo.of("  ", "A name", "1.0.0"));
        }

        @Test
        void refusesNull() {
            assertThatIllegalArgumentException().isThrownBy(() -> ModuleInfo.of(null, "A name", "1.0.0"));
        }

        @Test
        void refusesSomethingLongerThanAFolderNameShouldBe() {
            String tooLong = "a".repeat(65);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ModuleInfo.of(tooLong, "A name", "1.0.0"));
        }
    }

    @Nested
    @DisplayName("name, version, description, author")
    class Text {

        @Test
        void nameMayNotBeBlank() {
            assertThatIllegalArgumentException().isThrownBy(() -> ModuleInfo.of("mod", " ", "1.0.0"));
        }

        @Test
        void versionMayNotBeBlank() {
            assertThatIllegalArgumentException().isThrownBy(() -> ModuleInfo.of("mod", "Mod", ""));
        }

        @Test
        void descriptionAndAuthorDefaultToEmptyRatherThanNull() {
            ModuleInfo info = ModuleInfo.of("mod", "Mod", "1.0.0");
            assertThat(info.description()).isEmpty();
            assertThat(info.author()).isEmpty();
        }

        @Test
        void carriesWhatItWasTold() {
            ModuleInfo info = ModuleInfo.of("mod", "Moderation", "2.1.0")
                    .describedAs("Punishments and the screens for them")
                    .by("Raindancer118");
            assertThat(info.name()).isEqualTo("Moderation");
            assertThat(info.version()).isEqualTo("2.1.0");
            assertThat(info.description()).isEqualTo("Punishments and the screens for them");
            assertThat(info.author()).isEqualTo("Raindancer118");
        }
    }

    @Nested
    @DisplayName("dependencies")
    class Dependencies {

        @Test
        void startEmpty() {
            ModuleInfo info = ModuleInfo.of("mod", "Mod", "1.0.0");
            assertThat(info.requires()).isEmpty();
            assertThat(info.wants()).isEmpty();
            assertThat(info.dependencies()).isEmpty();
        }

        @Test
        void areHeldSortedSoTwoEqualDeclarationsAreEqual() {
            ModuleInfo one = ModuleInfo.of("mod", "Mod", "1.0.0").requiring("zoo", "apple");
            ModuleInfo other = ModuleInfo.of("mod", "Mod", "1.0.0").requiring("apple", "zoo");
            assertThat(one.requires()).containsExactly("apple", "zoo");
            assertThat(one).isEqualTo(other);
        }

        @Test
        void areUnmodifiable() {
            ModuleInfo info = ModuleInfo.of("mod", "Mod", "1.0.0").requiring("other");
            assertThatCode(() -> info.requires().add("sneaky"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void dropDuplicatesRatherThanCountingThemTwice() {
            ModuleInfo info = ModuleInfo.of("mod", "Mod", "1.0.0").requiring("other", "other");
            assertThat(info.requires()).containsExactly("other");
        }

        @Test
        void mustThemselvesBeValidIds() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ModuleInfo.of("mod", "Mod", "1.0.0").requiring("Not An Id"));
        }

        @Test
        void mayNotNameTheModuleItself() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ModuleInfo.of("mod", "Mod", "1.0.0").requiring("mod"))
                    .withMessageContaining("itself");
        }

        @Test
        void mayNotBeRequiredAndMerelyWantedAtOnce() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ModuleInfo.of("mod", "Mod", "1.0.0")
                            .requiring("other")
                            .wanting("other"))
                    .withMessageContaining("other");
        }

        @Test
        void dependenciesIsBothKinds() {
            ModuleInfo info = ModuleInfo.of("mod", "Mod", "1.0.0")
                    .requiring("hard")
                    .wanting("soft");
            assertThat(info.dependencies()).containsExactly("hard", "soft");
            assertThat(info.needs("hard")).isTrue();
            assertThat(info.needs("soft")).isFalse();
        }

        @Test
        void requiringNothingIsAllowedAndChangesNothing() {
            ModuleInfo info = ModuleInfo.of("mod", "Mod", "1.0.0").requiring();
            assertThat(info.requires()).isEmpty();
        }

        @Test
        void addingDependenciesTwiceAccumulates() {
            ModuleInfo info = ModuleInfo.of("mod", "Mod", "1.0.0")
                    .requiring("one")
                    .requiring("two");
            assertThat(info.requires()).containsExactlyInAnyOrderElementsOf(Set.of("one", "two"));
        }
    }

    @Test
    void readsAsItsNameAndVersion() {
        assertThat(ModuleInfo.of("mod", "Moderation", "2.1.0").toString())
                .contains("Moderation")
                .contains("2.1.0");
    }
}
