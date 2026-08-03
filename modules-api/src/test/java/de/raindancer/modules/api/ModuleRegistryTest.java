package de.raindancer.modules.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Starting and stopping modules, and surviving one that goes wrong.
 *
 * <p>The interesting behaviour is all in the failure paths, because the happy path is a for-loop. A
 * module that throws on the way up has usually already registered a listener, so leaving it half-in is
 * worse than not having it: the events keep arriving at code that thinks it was initialised. Hence the
 * rule these tests hold — <b>whatever happens, the session is unwound</b>.
 */
class ModuleRegistryTest {

    private final List<String> journal = new ArrayList<>();
    private final Map<String, FakeSession> sessions = new HashMap<>();

    /** A session per module, remembered so a test can ask whether it was unwound. */
    private ModuleSession sessionFor(FlexModule module) {
        return sessions.computeIfAbsent(module.info().id(), id -> new FakeSession(id, journal));
    }

    private ModuleRegistry registryOf(FlexModule... modules) {
        ModuleRegistry registry = new ModuleRegistry();
        for (FlexModule module : modules) {
            registry.add(module);
        }
        return registry;
    }

    @Nested
    @DisplayName("going up")
    class Enabling {

        @Test
        void enablesInPlanOrder() {
            ModuleRegistry registry = registryOf(
                    FakeModule.requiring("second", journal, "first"),
                    FakeModule.named("first", journal));
            registry.enableAll(ModuleRegistryTest.this::sessionFor);

            assertThat(journal).containsExactly("enable:first", "enable:second");
            assertThat(registry.enabled()).extracting(m -> m.info().id())
                    .containsExactly("first", "second");
        }

        @Test
        void handsEachModuleItsOwnContext() {
            FakeModule one = FakeModule.named("one", journal);
            FakeModule two = FakeModule.named("two", journal);
            registryOf(one, two).enableAll(ModuleRegistryTest.this::sessionFor);

            assertThat(one.seen).isNotNull().isNotSameAs(two.seen);
        }

        @Test
        void marksWhatHappened() {
            ModuleRegistry registry = registryOf(FakeModule.named("one", journal));
            assertThat(registry.stateOf("one")).isEqualTo(ModuleState.NEW);
            registry.enableAll(ModuleRegistryTest.this::sessionFor);
            assertThat(registry.stateOf("one")).isEqualTo(ModuleState.ENABLED);
            assertThat(registry.isEnabled("one")).isTrue();
        }

        @Test
        void knowsNothingAboutAModuleItWasNeverGiven() {
            ModuleRegistry registry = registryOf();
            assertThat(registry.stateOf("ghost")).isEqualTo(ModuleState.ABSENT);
            assertThat(registry.isEnabled("ghost")).isFalse();
            assertThat(registry.get("ghost")).isEmpty();
        }

        @Test
        void neverEnablesWhatThePlanSkipped() {
            ModuleRegistry registry = registryOf(
                    FakeModule.requiring("orphan", journal, "nowhere"));
            registry.enableAll(ModuleRegistryTest.this::sessionFor);

            assertThat(journal).isEmpty();
            assertThat(registry.stateOf("orphan")).isEqualTo(ModuleState.SKIPPED);
            assertThat(registry.reasonFor("orphan")).get().asString().contains("nowhere");
        }

        @Test
        void doesNotEvenBuildASessionForASkippedModule() {
            registryOf(FakeModule.requiring("orphan", journal, "nowhere"))
                    .enableAll(ModuleRegistryTest.this::sessionFor);
            assertThat(sessions).isEmpty();
        }

        @Test
        void refusesToBeStartedTwice() {
            ModuleRegistry registry = registryOf(FakeModule.named("one", journal));
            registry.enableAll(ModuleRegistryTest.this::sessionFor);
            assertThatIllegalStateException()
                    .isThrownBy(() -> registry.enableAll(ModuleRegistryTest.this::sessionFor));
        }

        @Test
        void refusesModulesAddedAfterItStarted() {
            ModuleRegistry registry = registryOf(FakeModule.named("one", journal));
            registry.enableAll(ModuleRegistryTest.this::sessionFor);
            assertThatIllegalStateException()
                    .isThrownBy(() -> registry.add(FakeModule.named("late", journal)));
        }

        @Test
        void refusesTwoNulls() {
            ModuleRegistry registry = new ModuleRegistry();
            assertThat(catchThrowable(() -> registry.add(null)))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("when a module throws on the way up")
    class Failing {

        @Test
        void isMarkedFailedAndItsSessionIsUnwound() {
            ModuleRegistry registry = registryOf(
                    FakeModule.named("bad", journal).failingToEnable(new IllegalStateException("no disk")));
            registry.enableAll(ModuleRegistryTest.this::sessionFor);

            assertThat(registry.stateOf("bad")).isEqualTo(ModuleState.FAILED);
            assertThat(registry.reasonFor("bad")).get().asString().contains("no disk");
            assertThat(journal).containsExactly("enable:bad", "unwind:bad");
            assertThat(registry.enabled()).isEmpty();
        }

        @Test
        void doesNotStopTheModulesAroundIt() {
            ModuleRegistry registry = registryOf(
                    FakeModule.named("a", journal),
                    FakeModule.named("b", journal).failingToEnable(new RuntimeException("boom")),
                    FakeModule.named("c", journal));
            registry.enableAll(ModuleRegistryTest.this::sessionFor);

            assertThat(registry.enabled()).extracting(m -> m.info().id()).containsExactly("a", "c");
        }

        @Test
        void takesItsDependentsWithIt() {
            ModuleRegistry registry = registryOf(
                    FakeModule.named("base", journal).failingToEnable(new RuntimeException("boom")),
                    FakeModule.requiring("on-top", journal, "base"));
            registry.enableAll(ModuleRegistryTest.this::sessionFor);

            assertThat(registry.stateOf("on-top")).isEqualTo(ModuleState.SKIPPED);
            assertThat(registry.reasonFor("on-top")).get().asString().contains("base");
            assertThat(journal).containsExactly("enable:base", "unwind:base");
        }

        @Test
        void takesTheWholeChainAboveIt() {
            ModuleRegistry registry = registryOf(
                    FakeModule.named("base", journal).failingToEnable(new RuntimeException("boom")),
                    FakeModule.requiring("middle", journal, "base"),
                    FakeModule.requiring("top", journal, "middle"));
            registry.enableAll(ModuleRegistryTest.this::sessionFor);

            assertThat(registry.stateOf("top")).isEqualTo(ModuleState.SKIPPED);
            assertThat(registry.enabled()).isEmpty();
        }

        @Test
        void doesNotTakeAModuleThatMerelyWantedIt() {
            ModuleRegistry registry = registryOf(
                    FakeModule.named("base", journal).failingToEnable(new RuntimeException("boom")),
                    FakeModule.wanting("fan", journal, "base"));
            registry.enableAll(ModuleRegistryTest.this::sessionFor);

            assertThat(registry.stateOf("fan")).isEqualTo(ModuleState.ENABLED);
        }

        @Test
        void anErrorRatherThanAnExceptionIsStillContained() {
            ModuleRegistry registry = registryOf(
                    FakeModule.named("bad", journal)
                            .failingToEnable(new AssertionErrorLike("assertions on")),
                    FakeModule.named("good", journal));
            registry.enableAll(ModuleRegistryTest.this::sessionFor);

            assertThat(registry.stateOf("bad")).isEqualTo(ModuleState.FAILED);
            assertThat(registry.stateOf("good")).isEqualTo(ModuleState.ENABLED);
        }

        @Test
        void anExceptionWithNoMessageStillGetsAReadableReason() {
            ModuleRegistry registry = registryOf(
                    FakeModule.named("bad", journal).failingToEnable(new NullPointerException()));
            registry.enableAll(ModuleRegistryTest.this::sessionFor);

            assertThat(registry.reasonFor("bad")).get().asString()
                    .isNotBlank()
                    .contains("NullPointerException");
        }

        /** A RuntimeException standing in for the kind of thing an assertion failure is. */
        private static final class AssertionErrorLike extends RuntimeException {
            AssertionErrorLike(String message) {
                super(message);
            }
        }
    }

    @Nested
    @DisplayName("when building a session throws")
    class BadSession {

        @Test
        void theModuleFailsRatherThanTheWholeHost() {
            ModuleRegistry registry = registryOf(
                    FakeModule.named("a", journal),
                    FakeModule.named("nofolder", journal));
            registry.enableAll(module -> {
                if (module.info().id().equals("nofolder")) {
                    throw new RuntimeException("cannot make its data folder");
                }
                return sessionFor(module);
            });

            assertThat(registry.stateOf("nofolder")).isEqualTo(ModuleState.FAILED);
            assertThat(registry.reasonFor("nofolder")).get().asString().contains("data folder");
            assertThat(registry.stateOf("a")).isEqualTo(ModuleState.ENABLED);
        }

        @Test
        void aNullSessionIsAFailureAndNotANullPointerLater() {
            ModuleRegistry registry = registryOf(FakeModule.named("a", journal));
            registry.enableAll(module -> null);
            assertThat(registry.stateOf("a")).isEqualTo(ModuleState.FAILED);
            assertThat(journal).isEmpty();
        }
    }

    @Nested
    @DisplayName("going down")
    class Disabling {

        @Test
        void disablesInReverseOrderAndUnwindsEachOne() {
            ModuleRegistry registry = registryOf(
                    FakeModule.requiring("second", journal, "first"),
                    FakeModule.named("first", journal));
            registry.enableAll(ModuleRegistryTest.this::sessionFor);
            journal.clear();
            registry.disableAll();

            assertThat(journal).containsExactly(
                    "disable:second", "unwind:second",
                    "disable:first", "unwind:first");
            assertThat(registry.stateOf("first")).isEqualTo(ModuleState.DISABLED);
            assertThat(registry.enabled()).isEmpty();
        }

        @Test
        void leavesWhatNeverStartedAlone() {
            ModuleRegistry registry = registryOf(
                    FakeModule.named("bad", journal).failingToEnable(new RuntimeException("boom")),
                    FakeModule.requiring("skipped", journal, "bad"));
            registry.enableAll(ModuleRegistryTest.this::sessionFor);
            journal.clear();
            registry.disableAll();

            assertThat(journal).isEmpty();
            assertThat(registry.stateOf("bad")).isEqualTo(ModuleState.FAILED);
            assertThat(registry.stateOf("skipped")).isEqualTo(ModuleState.SKIPPED);
        }

        @Test
        void unwindsEvenWhenDisableThrows() {
            FakeModule bad = FakeModule.named("bad", journal)
                    .failingToDisable(new RuntimeException("half shut"));
            ModuleRegistry registry = registryOf(bad, FakeModule.named("also", journal));
            registry.enableAll(ModuleRegistryTest.this::sessionFor);
            journal.clear();
            registry.disableAll();

            assertThat(journal).contains("unwind:bad");
            assertThat(registry.problems()).anySatisfy(problem ->
                    assertThat(problem).contains("bad").contains("half shut"));
            // and the one next to it went down all the same
            assertThat(journal).contains("disable:also", "unwind:also");
        }

        @Test
        void isSafeToCallTwice() {
            ModuleRegistry registry = registryOf(FakeModule.named("one", journal));
            registry.enableAll(ModuleRegistryTest.this::sessionFor);
            registry.disableAll();
            journal.clear();
            registry.disableAll();
            assertThat(journal).isEmpty();
        }

        @Test
        void isSafeBeforeAnythingStarted() {
            ModuleRegistry registry = registryOf(FakeModule.named("one", journal));
            registry.disableAll();
            assertThat(journal).isEmpty();
            assertThat(registry.stateOf("one")).isEqualTo(ModuleState.NEW);
        }
    }

    @Nested
    @DisplayName("the commands modules bring")
    class Commands {

        private static ModuleCommand command(String name) {
            return ModuleCommand.of(name, "does " + name, (source, args) -> {
            });
        }

        @Test
        void areCollectedInPlanOrder() {
            ModuleRegistry registry = registryOf(
                    FakeModule.requiring("second", journal, "first").offering(command("b")),
                    FakeModule.named("first", journal).offering(command("a")));

            assertThat(registry.commands()).extracting(ModuleCommand::name).containsExactly("a", "b");
        }

        @Test
        void areAvailableBeforeAnythingIsEnabled() {
            // Paper wants commands during bootstrap, which is before onEnable. A registry that only
            // knew its commands after enabling would register none at all — silently.
            ModuleRegistry registry = registryOf(FakeModule.named("one", journal).offering(command("a")));
            assertThat(registry.commands()).hasSize(1);
            assertThat(registry.stateOf("one")).isEqualTo(ModuleState.NEW);
        }

        @Test
        void aSkippedModuleBringsNone() {
            ModuleRegistry registry = registryOf(
                    FakeModule.requiring("orphan", journal, "nowhere").offering(command("a")));
            assertThat(registry.commands()).isEmpty();
        }

        @Test
        void aCollidingNameIsDroppedAndSaidOutLoud() {
            ModuleRegistry registry = registryOf(
                    FakeModule.named("first", journal).offering(command("mod")),
                    FakeModule.named("second", journal).offering(command("mod")));

            assertThat(registry.commands()).hasSize(1);
            assertThat(registry.problems()).anySatisfy(problem ->
                    assertThat(problem).contains("mod").contains("second"));
        }

        @Test
        void anAliasCollidingWithAnotherNameIsDroppedToo() {
            ModuleRegistry registry = registryOf(
                    FakeModule.named("first", journal).offering(command("mod")),
                    FakeModule.named("second", journal)
                            .offering(ModuleCommand.of("moderate", "x", (source, args) -> {
                            }).aliased("mod")));

            assertThat(registry.commands()).extracting(ModuleCommand::name).containsExactly("mod");
            assertThat(registry.problems()).isNotEmpty();
        }

        @Test
        void twoCommandsFromOneModuleAreBothKept() {
            ModuleRegistry registry = registryOf(
                    FakeModule.named("one", journal).offering(command("a")).offering(command("b")));
            assertThat(registry.commands()).hasSize(2);
        }

        @Test
        void aModuleThatThrowsWhenAskedForItsCommandsDoesNotStopTheRest() {
            FlexModule rude = new FlexModule() {
                @Override
                public ModuleInfo info() {
                    return ModuleInfo.of("rude", "Rude", "1.0.0");
                }

                @Override
                public List<ModuleCommand> commands() {
                    throw new RuntimeException("built its commands wrongly");
                }

                @Override
                public void enable(ModuleContext context) {
                }

                @Override
                public void disable() {
                }
            };
            ModuleRegistry registry = registryOf(rude, FakeModule.named("z", journal).offering(command("z")));

            assertThat(registry.commands()).extracting(ModuleCommand::name).containsExactly("z");
            assertThat(registry.problems()).anySatisfy(problem ->
                    assertThat(problem).contains("rude"));
        }
    }
}
