package de.raindancer.modules.chat.command;

import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.modules.chat.ChatServices;
import de.raindancer.modules.chat.service.AdService;
import de.raindancer.modules.chat.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/** {@code /ad <message>} — a line to everybody online, paid for and filtered like chat. */
public final class AdCommand implements IChatCommand {

    private final Supplier<ChatServices> services;

    public AdCommand(Supplier<ChatServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "pays to broadcast a line to everybody online";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        ChatServices live = services.get();
        if (!(source.getSender() instanceof Player player)) {
            live.messages().send(source.getSender(), "chat.only-a-player");
            return;
        }
        if (!live.config().adsEnabled()) {
            live.messages().send(player, "chat.ad.off");
            return;
        }
        if (args.length == 0) {
            String price = live.ads().priceText();
            live.messages().send(player, price.isEmpty() ? "chat.ad.usage-free" : "chat.ad.usage",
                    "price", price, "most", String.valueOf(live.config().adLength()));
            return;
        }
        place(live, player, String.join(" ", args).strip());
    }

    /** Sends an ad as {@code player}: the command and the chat screen's button do exactly this. */
    public static void place(ChatServices live, Player player, String typed) {
        var muted = live.core().punishmentGuard().speakRefusal(player.getUniqueId());
        if (muted.isPresent()) {
            player.sendMessage(muted.get());
            return;
        }
        String text = typed;
        AdService.Result result = live.ads().place(player.getUniqueId(), text,
                player.hasPermission(PermissionNodes.BYPASS_FILTERS), player.hasPermission(PermissionNodes.BYPASS_FREEZE));
        if (!result.placed()) {
            live.messages().send(player, result.verdict().reason(), "seconds", result.verdict().detail(),
                    "most", result.verdict().detail(), "price", result.verdict().detail());
            return;
        }
        live.server().broadcast(live.format().renderAd(player, text));
        live.history().record(player.getUniqueId(), player.getName(), "[Ad] " + text);
        live.core().effects().playForAll(live.server().getOnlinePlayers().stream().map(Player::getUniqueId).toList(),
                Cues.NOTIFY);
        if (result.charged().isPositive()) {
            live.messages().send(player, "chat.ad.paid", "price", de.raindancer.core.social.economy.Fees.format(result.charged()));
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        return List.of();
    }

    @Override
    public String permission() {
        return PermissionNodes.AD;
    }
}
