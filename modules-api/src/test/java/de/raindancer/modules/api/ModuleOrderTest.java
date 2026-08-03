package de.raindancer.modules.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The order modules are started in, and who gets left out.
 *
 * <p>Two properties matter more than the individual cases. The order must be <b>deterministic</b> —
 * a set of modules that starts in one order today and another tomorrow makes a bug that only shows up
 * on some boots, which is the worst kind to be handed. And a module that cannot run must take its
 * dependents with it: enabling something whose foundation was skipped is how you get a
 * {@code NullPointerException} in a place nobody can connect to the actual cause.
 */
class ModuleOrderTest {

    private final List<String> journal = new ArrayList<>();

    private static List<String> idsOf(ModulePlan plan) {
        return plan.order().stream().map(m -> m.info().id()).toList();
    }

    @Nested
    @DisplayName("with nothing to sort out")
    class Simple {

        @Test
        void nothingInNothingOut() {
            ModulePlan plan = ModuleOrder.plan(List.of());
            assertThat(plan.order()).isEmpty();
            assertThat(plan.skipped()).isEmpty();
        }

        @Test
        void independentModulesComeOutAlphabetically() {
            ModulePlan plan = ModuleOrder.plan(List.of(
                    FakeModule.named("zebra", journal),
                    FakeModule.named("apple", journal),
                    FakeModule.named("mango", journal)));
            assertThat(idsOf(plan)).containsExactly("apple", "mango", "zebra");
        }

        @Test
        void shufflingTheInputChangesNothing() {
            List<FlexModule> modules = new ArrayList<>(List.of(
                    FakeModule.requiring("d", journal, "c"),
                    FakeModule.requiring("c", journal, "b"),
                    FakeModule.requiring("b", journal, "a"),
                    FakeModule.named("a", journal),
                    FakeModule.named("z", journal)));
            List<String> first = idsOf(ModuleOrder.plan(modules));
            for (int round = 0; round < 20; round++) {
                Collections.shuffle(modules, new java.util.Random(round));
                assertThat(idsOf(ModuleOrder.plan(modules))).isEqualTo(first);
            }
        }
    }

    @Nested
    @DisplayName("required dependencies")
    class Required {

        @Test
        void comeFirst() {
            ModulePlan plan = ModuleOrder.plan(List.of(
                    FakeModule.requiring("apple", journal, "zebra"),
                    FakeModule.named("zebra", journal)));
            assertThat(idsOf(plan)).containsExactly("zebra", "apple");
        }

        @Test
        void followAWholeChain() {
            ModulePlan plan = ModuleOrder.plan(List.of(
                    FakeModule.requiring("a", journal, "b"),
                    FakeModule.requiring("b", journal, "c"),
                    FakeModule.named("c", journal)));
            assertThat(idsOf(plan)).containsExactly("c", "b", "a");
        }

        @Test
        void aMissingOneSkipsTheModuleAndSaysWhichIsMissing() {
            ModulePlan plan = ModuleOrder.plan(List.of(
                    FakeModule.requiring("apple", journal, "nowhere")));
            assertThat(idsOf(plan)).isEmpty();
            assertThat(plan.isSkipped("apple")).isTrue();
            assertThat(plan.reasonFor("apple")).get().asString().contains("nowhere");
        }

        @Test
        void aMissingOneTakesTheWholeChainWithIt() {
            ModulePlan plan = ModuleOrder.plan(List.of(
                    FakeModule.requiring("a", journal, "b"),
                    FakeModule.requiring("b", journal, "gone"),
                    FakeModule.named("unrelated", journal)));
            assertThat(idsOf(plan)).containsExactly("unrelated");
            assertThat(plan.skipped()).containsOnlyKeys("a", "b");
            assertThat(plan.reasonFor("a")).get().asString().contains("b");
        }

        @Test
        void aCycleSkipsEverybodyInIt() {
            ModulePlan plan = ModuleOrder.plan(List.of(
                    FakeModule.requiring("a", journal, "b"),
                    FakeModule.requiring("b", journal, "a"),
                    FakeModule.named("fine", journal)));
            assertThat(idsOf(plan)).containsExactly("fine");
            assertThat(plan.skipped()).containsOnlyKeys("a", "b");
            assertThat(plan.reasonFor("a")).get().asString().containsIgnoringCase("cycle");
        }

        @Test
        void aCycleAlsoTakesWhoeverDependedOnIt() {
            ModulePlan plan = ModuleOrder.plan(List.of(
                    FakeModule.requiring("a", journal, "b"),
                    FakeModule.requiring("b", journal, "a"),
                    FakeModule.requiring("onlooker", journal, "a")));
            assertThat(idsOf(plan)).isEmpty();
            assertThat(plan.skipped()).containsOnlyKeys("a", "b", "onlooker");
        }

        @Test
        void aModuleRequiringItselfNeverGetsThatFar() {
            // ModuleInfo refuses it outright, so the planner never has to reason about it.
            assertThat(catchThrowable(() -> FakeModule.requiring("a", journal, "a")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        private static Throwable catchThrowable(Runnable what) {
            try {
                what.run();
                return null;
            } catch (Throwable caught) {
                return caught;
            }
        }
    }

    @Nested
    @DisplayName("wanted dependencies")
    class Wanted {

        @Test
        void orderWhenPresent() {
            ModulePlan plan = ModuleOrder.plan(List.of(
                    FakeModule.wanting("apple", journal, "zebra"),
                    FakeModule.named("zebra", journal)));
            assertThat(idsOf(plan)).containsExactly("zebra", "apple");
        }

        @Test
        void changeNothingWhenAbsent() {
            ModulePlan plan = ModuleOrder.plan(List.of(
                    FakeModule.wanting("apple", journal, "nowhere"),
                    FakeModule.named("mango", journal)));
            assertThat(idsOf(plan)).containsExactly("apple", "mango");
            assertThat(plan.skipped()).isEmpty();
        }

        @Test
        void areDroppedRatherThanCausingACycle() {
            // b genuinely needs a. a would merely like b to go first. The soft edge is the one that
            // gives, because dropping it costs an ordering preference and keeping it costs both
            // modules.
            ModulePlan plan = ModuleOrder.plan(List.of(
                    FakeModule.wanting("a", journal, "b"),
                    FakeModule.requiring("b", journal, "a")));
            assertThat(idsOf(plan)).containsExactly("a", "b");
            assertThat(plan.skipped()).isEmpty();
        }

        @Test
        void areDroppedRatherThanCausingALongerCycle() {
            ModulePlan plan = ModuleOrder.plan(List.of(
                    FakeModule.wanting("a", journal, "c"),
                    FakeModule.requiring("b", journal, "a"),
                    FakeModule.requiring("c", journal, "b")));
            assertThat(idsOf(plan)).containsExactly("a", "b", "c");
            assertThat(plan.skipped()).isEmpty();
        }

        @Test
        void aCycleOfNothingButWantsIsStillBrokenRatherThanSkipped() {
            ModulePlan plan = ModuleOrder.plan(List.of(
                    FakeModule.wanting("a", journal, "b"),
                    FakeModule.wanting("b", journal, "a")));
            assertThat(idsOf(plan)).hasSize(2);
            assertThat(plan.skipped()).isEmpty();
        }

        @Test
        void aSkippedWantedModuleDoesNotSkipTheOneWantingIt() {
            ModulePlan plan = ModuleOrder.plan(List.of(
                    FakeModule.wanting("a", journal, "b"),
                    FakeModule.requiring("b", journal, "gone")));
            assertThat(idsOf(plan)).containsExactly("a");
            assertThat(plan.skipped()).containsOnlyKeys("b");
        }
    }

    @Nested
    @DisplayName("two modules with the same id")
    class Duplicates {

        @Test
        void theSecondIsSkippedAndTheFirstSurvives() {
            FakeModule first = FakeModule.named("twin", journal);
            FakeModule second = FakeModule.named("twin", journal);
            ModulePlan plan = ModuleOrder.plan(List.of(first, second));
            assertThat(plan.order()).containsExactly(first);
            // Not "skipped": the id runs, and the reason a caller would look up by that id is the
            // surviving module's, not the shadowed one's. So this is a problem with the installation.
            assertThat(plan.skipped()).isEmpty();
            assertThat(plan.problems()).anySatisfy(problem ->
                    assertThat(problem).containsIgnoringCase("already").contains("twin"));
        }

        @Test
        void aDuplicateDoesNotMakeTheIdCountAsMissing() {
            ModulePlan plan = ModuleOrder.plan(List.of(
                    FakeModule.named("twin", journal),
                    FakeModule.named("twin", journal),
                    FakeModule.requiring("user", journal, "twin")));
            assertThat(idsOf(plan)).containsExactly("twin", "user");
        }
    }

    @Test
    void theSameModuleInstanceTwiceIsStillJustOne() {
        FakeModule once = FakeModule.named("only", journal);
        ModulePlan plan = ModuleOrder.plan(List.of(once, once));
        assertThat(plan.order()).containsExactly(once);
    }

    @Test
    void aPlanIsUnmodifiable() {
        ModulePlan plan = ModuleOrder.plan(List.of(FakeModule.named("a", journal)));
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> plan.order().clear()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> plan.skipped().clear()))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
