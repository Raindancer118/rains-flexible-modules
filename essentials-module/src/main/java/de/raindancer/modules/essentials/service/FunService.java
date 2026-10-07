package de.raindancer.modules.essentials.service;

import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.platform.util.Cooldowns;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.essentials.EssentialsSettings;
import de.raindancer.modules.essentials.rules.FunRule;
import de.raindancer.modules.essentials.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * {@code /roast} and {@code /joke}: a line from messages.yml, said in chat as whoever asked.
 *
 * <p>As them rather than as a server broadcast, so a muted player cannot roast anybody, chat filters
 * and formats apply, and the line reads as the joke somebody told rather than an announcement. Lines
 * are dealt with {@link Messages#fresh}, so all of them come round before one repeats.
 */
public final class FunService implements IEssentialsService {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final Messages messages;
    private final Plugin plugin;
    private final FunRule rule = new FunRule();
    /** Shared by both commands: a roast straight after a joke is still two lines in a row. */
    private final Cooldowns<UUID> waits = new Cooldowns<>();

    private volatile EssentialsSettings settings;

    public FunService(Messages messages, Plugin plugin, EssentialsSettings settings) {
        this.messages = messages;
        this.plugin = plugin;
        settings(settings);
    }

    @Override
    public void settings(EssentialsSettings fresh) {
        this.settings = fresh;
        waits.every(fresh.funCooldown());
    }

    /** Roasts {@code target} — who may be {@code by} themselves — in {@code by}'s name. */
    public void roast(Player by, Player target) {
        String name = PLAIN.serialize(target.displayName());
        say(by, "roast", settings.roastEnabled(), () -> messages.fresh("essentials.fun.roasts", "target", name));
    }

    /** Tells a joke in {@code by}'s name. */
    public void joke(Player by) {
        say(by, "joke", settings.jokeEnabled(), () -> messages.fresh("essentials.fun.jokes"));
    }

    private void say(Player by, String command, boolean enabled, java.util.function.Supplier<Component> line) {
        Verdict verdict = rule.judge(new FunRule.Ask(command, enabled, waits.remaining(by.getUniqueId()),
                by.hasPermission(PermissionNodes.FUN_NO_COOLDOWN)));
        if (verdict.isRefused()) {
            messages.send(by, verdict.reason(), "command", command, "seconds", verdict.detail());
            return;
        }
        waits.start(by.getUniqueId());
        // Chat text, not markup: whatever an owner put in the line is said exactly as a player would type it.
        String text = PLAIN.serialize(line.get());
        Scheduling.entity(plugin, by, () -> by.chat(text));
    }

    @Override
    public String describe() {
        return "/roast and /joke";
    }
}
