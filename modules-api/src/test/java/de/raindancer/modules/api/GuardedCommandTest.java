package de.raindancer.modules.api;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.junit.jupiter.api.Test;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * What actually happens when somebody runs a command whose module is not there.
 *
 * <p>Written after a review pointed out that {@link ModuleRegistry#commands()} handed out the modules' own
 * handlers unwrapped, so the guard existed and nothing used it. Every one of the 144 tests passed with that
 * defect in place, because they all asked what the registry <em>collected</em> and none asked what happened
 * when one of the collected commands was run. These do.
 *
 * <p>The module below is hostile on purpose: every one of its methods throws. A module that failed halfway
 * through {@code enable} is exactly that — its fields are null and any of its methods will throw — and
 * {@code canUse} is called by Brigadier while resolving the command, before {@code execute} is reached.
 */
class GuardedCommandTest {

    private final List<String> journal = new ArrayList<>();
    private final FakeSource source = new FakeSource();

    /** A handler that must never be reached, and complains loudly if it is. */
    private static final class Hostile implements BasicCommand {
        boolean ran;

        @Override
        public void execute(CommandSourceStack ignored, String[] args) {
            ran = true;
            throw new IllegalStateException("the module never finished starting");
        }

        @Override
        public Collection<String> suggest(CommandSourceStack ignored, String[] args) {
            throw new IllegalStateException("the module never finished starting");
        }

        @Override
        public boolean canUse(CommandSender sender) {
            throw new IllegalStateException("the module never finished starting");
        }

        @Override
        public String permission() {
            throw new IllegalStateException("the module never finished starting");
        }
    }

    private static ModuleRegistry withA(FlexModule module) {
        ModuleRegistry registry = new ModuleRegistry();
        registry.add(module);
        return registry;
    }

    @Test
    void theRegistryHandsOutGuardedCommandsRatherThanTheModulesOwn() {
        Hostile raw = new Hostile();
        ModuleRegistry registry = withA(FakeModule.named("moderation", journal)
                .offering(ModuleCommand.of("mod", "Moderation", raw)));

        assertThat(registry.commands().getFirst().handler()).isNotSameAs(raw);
    }

    @Test
    void runningACommandOfAFailedModuleExplainsItselfInsteadOfThrowing() {
        Hostile raw = new Hostile();
        ModuleRegistry registry = withA(FakeModule.named("moderation", journal)
                .failingToEnable(new IllegalStateException("could not open its database"))
                .offering(ModuleCommand.of("mod", "Moderation", raw)));

        // Registered during bootstrap, as Paper insists — before anything is enabled.
        BasicCommand registered = registry.commands().getFirst().handler();
        registry.enableAll(module -> new FakeSession(module.info().id(), journal));

        registered.execute(source.stack(), new String[0]);

        assertThat(raw.ran).isFalse();
        assertThat(source.heard()).singleElement().asString()
                .contains("moderation")
                .contains("could not open its database");
    }

    @Test
    void runningACommandBeforeAnythingWasEnabledExplainsItself() {
        Hostile raw = new Hostile();
        ModuleRegistry registry = withA(FakeModule.named("moderation", journal)
                .offering(ModuleCommand.of("mod", "Moderation", raw)));

        registry.commands().getFirst().handler().execute(source.stack(), new String[0]);

        assertThat(raw.ran).isFalse();
        assertThat(source.heard()).isNotEmpty();
    }

    @Test
    void runningACommandOfAnEnabledModuleReachesTheModule() {
        List<String> ran = new ArrayList<>();
        ModuleRegistry registry = withA(FakeModule.named("moderation", journal)
                .offering(ModuleCommand.of("mod", "Moderation",
                        (stack, args) -> ran.add("ran with " + args.length))));
        registry.enableAll(module -> new FakeSession(module.info().id(), journal));

        registry.commands().getFirst().handler().execute(source.stack(), new String[]{"a", "b"});

        assertThat(ran).containsExactly("ran with 2");
        assertThat(source.heard()).isEmpty();
    }

    @Test
    void suggestingForAModuleThatIsNotThereAsksNothingOfIt() {
        Hostile raw = new Hostile();
        ModuleRegistry registry = withA(FakeModule.named("moderation", journal)
                .offering(ModuleCommand.of("mod", "Moderation", raw)));

        BasicCommand registered = registry.commands().getFirst().handler();
        assertThat(registered.suggest(source.stack(), new String[0])).isEmpty();
    }

    @Test
    void brigadiersUsabilityCheckNeverReachesAModuleThatIsNotRunning() {
        // The one Brigadier calls first, and the one that would throw during command resolution rather
        // than during execution — so the player would see nothing at all and the console a stack trace
        // from inside Paper's parser.
        Hostile raw = new Hostile();
        ModuleRegistry registry = withA(FakeModule.named("moderation", journal)
                .offering(ModuleCommand.of("mod", "Moderation", raw)));

        BasicCommand registered = registry.commands().getFirst().handler();

        assertThat(catchThrowable(() -> registered.canUse(null))).isNull();
        // True, so the command resolves and the player is told why it will not work. Answering false
        // would give them "Unknown command", which is a lie about what is wrong.
        assertThat(registered.canUse(null)).isTrue();
        assertThat(registered.permission()).isNull();
    }

    @Test
    void aRunningModuleWhosePermissionCheckThrowsIsTreatedAsUnusable() {
        // Fail closed. Swallowing the exception and answering true would open a moderation command to
        // everybody on the server, which is far worse than the command not working.
        ModuleRegistry registry = withA(FakeModule.named("moderation", journal)
                .offering(ModuleCommand.of("mod", "Moderation", new Hostile())));
        registry.enableAll(module -> new FakeSession(module.info().id(), journal));

        BasicCommand registered = registry.commands().getFirst().handler();

        assertThat(catchThrowable(() -> registered.canUse(null))).isNull();
        assertThat(registered.canUse(null)).isFalse();
    }

    @Test
    void aRunningModulesPermissionAndUsabilityAreItsOwn() {
        BasicCommand real = new BasicCommand() {
            @Override
            public void execute(CommandSourceStack stack, String[] args) {
            }

            @Override
            public boolean canUse(CommandSender sender) {
                return true;
            }

            @Override
            public String permission() {
                return "rains.moderation.use";
            }
        };
        ModuleRegistry registry = withA(FakeModule.named("moderation", journal)
                .offering(ModuleCommand.of("mod", "Moderation", real)));
        registry.enableAll(module -> new FakeSession(module.info().id(), journal));

        BasicCommand registered = registry.commands().getFirst().handler();
        assertThat(registered.permission()).isEqualTo("rains.moderation.use");
        assertThat(registered.canUse(null)).isTrue();
    }

    @Test
    void aModuleThatWasStoppedNoLongerAnswersItsCommand() {
        Hostile raw = new Hostile();
        ModuleRegistry registry = withA(FakeModule.named("moderation", journal)
                .offering(ModuleCommand.of("mod", "Moderation", raw)));
        registry.enableAll(module -> new FakeSession(module.info().id(), journal));
        BasicCommand registered = registry.commands().getFirst().handler();
        registry.disableAll();

        registered.execute(source.stack(), new String[0]);

        assertThat(raw.ran).isFalse();
        assertThat(source.heard()).isNotEmpty();
    }

    @Test
    void guardingIsNotAppliedTwice() {
        // commands() is called by the bootstrapper and may be called again by the host. The second call
        // must not wrap the guard in another guard, or the same registry check runs twice per keystroke.
        ModuleRegistry registry = withA(FakeModule.named("moderation", journal)
                .offering(ModuleCommand.of("mod", "Moderation", new Hostile())));

        assertThat(registry.commands().getFirst().handler())
                .isSameAs(registry.commands().getFirst().handler());
    }
}
