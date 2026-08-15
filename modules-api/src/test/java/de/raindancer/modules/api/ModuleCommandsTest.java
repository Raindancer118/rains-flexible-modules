package de.raindancer.modules.api;

import io.papermc.paper.command.brigadier.BasicCommand;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a command says when the module behind it is not running.
 *
 * <p>This case is not hypothetical, and it is not rare. Commands are registered during bootstrap, and
 * whether a module enables is decided minutes later in {@code onEnable}. So a module that fails to
 * start leaves its commands registered and answering — and the honest answer is a sentence saying the
 * module is not running and why, not a {@link NullPointerException} in the console with the player left
 * looking at nothing.
 */
class ModuleCommandsTest {

    private static final BasicCommand NOTHING = (source, args) -> {
    };

    private final List<String> journal = new ArrayList<>();

    @Test
    void anEnabledModuleRefusesNothing() {
        ModuleRegistry registry = new ModuleRegistry();
        registry.add(FakeModule.named("moderation", journal));
        registry.enableAll(module -> new FakeSession(module.info().id(), journal));

        assertThat(ModuleCommands.refusalFor(registry, "moderation")).isEmpty();
    }

    @Test
    void aModuleThatHasNotBeenStartedYetSaysSo() {
        ModuleRegistry registry = new ModuleRegistry();
        registry.add(FakeModule.named("moderation", journal));

        assertThat(ModuleCommands.refusalFor(registry, "moderation")).get().asString()
                .containsIgnoringCase("not")
                .contains("moderation");
    }

    @Test
    void aFailedModuleSaysWhatWentWrong() {
        ModuleRegistry registry = new ModuleRegistry();
        registry.add(FakeModule.named("moderation", journal)
                .failingToEnable(new IllegalStateException("could not open its database")));
        registry.enableAll(module -> new FakeSession(module.info().id(), journal));

        assertThat(ModuleCommands.refusalFor(registry, "moderation")).get().asString()
                .contains("could not open its database");
    }

    @Test
    void aSkippedModuleSaysWhatItWasWaitingFor() {
        ModuleRegistry registry = new ModuleRegistry();
        registry.add(FakeModule.requiring("moderation", journal, "missing-thing"));
        registry.enableAll(module -> new FakeSession(module.info().id(), journal));

        assertThat(ModuleCommands.refusalFor(registry, "moderation")).get().asString()
                .contains("missing-thing");
    }

    @Test
    void aModuleTheHostHasNeverHeardOfStillGetsASentence() {
        assertThat(ModuleCommands.refusalFor(new ModuleRegistry(), "ghost")).get().asString()
                .contains("ghost");
    }

    @Test
    void aDisabledModuleSaysSoRatherThanPretendingToWork() {
        ModuleRegistry registry = new ModuleRegistry();
        registry.add(FakeModule.named("moderation", journal));
        registry.enableAll(module -> new FakeSession(module.info().id(), journal));
        registry.disableAll();

        assertThat(ModuleCommands.refusalFor(registry, "moderation")).isPresent();
    }

    @Test
    void guardingKeepsTheNameTheAliasesAndTheDescription() {
        ModuleRegistry registry = new ModuleRegistry();
        ModuleCommand original = ModuleCommand.of("mod", "Moderation tools", NOTHING).aliased("m");
        ModuleCommand guarded = ModuleCommands.guarded(registry, "moderation", original);

        assertThat(guarded.name()).isEqualTo("mod");
        assertThat(guarded.aliases()).containsExactly("m");
        assertThat(guarded.description()).isEqualTo("Moderation tools");
        assertThat(guarded.handler()).isNotSameAs(original.handler());
    }

    @Test
    void aCommandIsNotAuditedUnlessItAsksToBe() {
        assertThat(ModuleCommand.of("mod", "Moderation tools", NOTHING).audited()).isFalse();
    }

    @Test
    void guardingKeepsWhetherItIsAudited() {
        ModuleRegistry registry = new ModuleRegistry();
        ModuleCommand original =
                ModuleCommand.of("mod", "Moderation tools", NOTHING).auditUsage();
        ModuleCommand guarded = ModuleCommands.guarded(registry, "moderation", original);

        assertThat(guarded.audited()).isTrue();
    }

    /**
     * Running an audited command never fails just because RainsCore is not up — the fake
     * {@link ModuleRegistry} in these tests has none, which is exactly the shape a unit test for a
     * module has, and the command's own work must not be held hostage by that.
     */
    @Test
    void anAuditedCommandStillRunsWithoutRainsCore() {
        ModuleRegistry registry = new ModuleRegistry();
        registry.add(FakeModule.named("moderation", journal));
        registry.enableAll(module -> new FakeSession(module.info().id(), journal));

        List<String> ran = new ArrayList<>();
        ModuleCommand original = ModuleCommand
                .of("mod", "Moderation tools", (source, args) -> ran.add("ran"))
                .auditUsage();
        ModuleCommand guarded = ModuleCommands.guarded(registry, "moderation", original);

        guarded.handler().execute(new FakeSource().stack(), new String[0]);

        assertThat(ran).containsExactly("ran");
    }
}
