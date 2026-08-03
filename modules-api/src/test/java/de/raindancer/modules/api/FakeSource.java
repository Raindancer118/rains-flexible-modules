package de.raindancer.modules.api;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * A command source that records what was said to it.
 *
 * <p>Built with a {@link Proxy} rather than by implementing {@link CommandSender}, which has upwards of
 * thirty methods this test needs none of. The alternative — not testing the refusal path because faking
 * a sender is tedious — is how the unguarded-commands defect survived 144 green tests in the first place.
 */
final class FakeSource {

    private final List<String> heard = new ArrayList<>();
    private final CommandSourceStack stack;

    FakeSource() {
        CommandSender sender = (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[]{CommandSender.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("sendMessage") && args != null && args.length == 1
                            && args[0] instanceof Component said) {
                        heard.add(PlainTextComponentSerializer.plainText().serialize(said));
                        return null;
                    }
                    return switch (method.getName()) {
                        case "toString" -> "a fake sender";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultFor(method.getReturnType());
                    };
                });

        this.stack = (CommandSourceStack) Proxy.newProxyInstance(
                CommandSourceStack.class.getClassLoader(),
                new Class<?>[]{CommandSourceStack.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getSender", "getExecutor" -> sender;
                    case "toString" -> "a fake command source";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultFor(method.getReturnType());
                });
    }

    private static Object defaultFor(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == void.class) {
            return null;
        }
        return 0;
    }

    CommandSourceStack stack() {
        return stack;
    }

    List<String> heard() {
        return heard;
    }
}
