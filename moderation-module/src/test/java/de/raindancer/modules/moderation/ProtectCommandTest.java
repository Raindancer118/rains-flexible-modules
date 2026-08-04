package de.raindancer.modules.moderation;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.moderation.command.IModerationCommand;
import de.raindancer.modules.moderation.command.ProtectCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may reach {@code /protect}.
 *
 * <h2>Why the door is the only thing tested here</h2>
 * Because it is the only part that is a decision. What the command then does — resolve a name, add an
 * id, write the file — needs a running server to mean anything, and the deciding half of it is already
 * {@code ImmuneStaffTest}'s. What cannot be left to a live server is <em>who gets in</em>: a wrong
 * answer there is a shield the staff can hand each other, which is the failure this command was built
 * to remove.
 *
 * <p>The senders are {@link Proxy} instances rather than mocks. This repository has no mocking library
 * and does not want one for three interface methods: the command asks nothing of a sender except what
 * type it is, so a proxy that answers nothing is a faithful stand-in.
 */
class ProtectCommandTest {

    /** A sender of the given interface that answers nothing — the command only asks what it is. */
    private static CommandSender senderOf(Class<? extends CommandSender> kind) {
        return (CommandSender) Proxy.newProxyInstance(
                ProtectCommandTest.class.getClassLoader(),
                new Class<?>[]{kind},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> kind.getSimpleName();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(
                            "the command asked a sender for " + method.getName()
                                    + ", which this test's stand-in cannot answer — and which a "
                                    + "console-only guard should not need");
                });
    }

    private static ProtectCommand protect() {
        return new ProtectCommand(() -> {
            throw new AssertionError("the services were asked for while deciding who may run this");
        }, true);
    }

    @Test
    @DisplayName("the console may use it")
    void theConsole() {
        assertThat(protect().canUse(senderOf(ConsoleCommandSender.class))).isTrue();
    }

    @Test
    @DisplayName("a player may not, whoever they are")
    void aPlayer() {
        // No "unless they are op" here, and deliberately: an opped player is a player, and the account
        // most worth protecting is the one most worth taking over.
        assertThat(protect().canUse(senderOf(Player.class))).isFalse();
    }

    @Test
    @DisplayName("deciding who may run it asks nothing of the module")
    void theGuardIsSelfContained() {
        // The supplier above throws. This passes only because canUse answers from the sender alone,
        // which is what lets the guard hold while the module is starting, stopped or broken — the three
        // states in which somebody is most likely to be at the console typing this.
        assertThat(protect().canUse(senderOf(Player.class))).isFalse();
        assertThat(new ProtectCommand(() -> {
            throw new AssertionError("asked");
        }, false).canUse(senderOf(ConsoleCommandSender.class))).isTrue();
    }

    @Test
    @DisplayName("both halves exist, or a protection cannot be lifted")
    void bothCommandsAreDeclared() {
        List<String> names = ModerationCommands.declared().stream()
                .filter(command -> command.handler() instanceof IModerationCommand mine
                        && mine.consoleOnly())
                .map(ModuleCommand::name)
                .toList();

        assertThat(names)
                .as("a protection nothing can take off outlives the person leaving the staff, and then "
                        + "the only way to act on that account is a text editor and a restart")
                .containsExactlyInAnyOrder("protect", "unprotect");
    }

    @Test
    @DisplayName("each says which half it is")
    void theyDescribeThemselves() {
        assertThat(new ProtectCommand(() -> null, true).describe()).contains("console only");
        assertThat(new ProtectCommand(() -> null, false).describe()).contains("console only");
        assertThat(new ProtectCommand(() -> null, true).describe())
                .isNotEqualTo(new ProtectCommand(() -> null, false).describe());
    }
}
