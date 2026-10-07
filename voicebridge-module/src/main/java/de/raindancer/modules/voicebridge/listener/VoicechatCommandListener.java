package de.raindancer.modules.voicebridge.listener;

import de.raindancer.modules.voicebridge.VoiceBridgeServices;
import de.raindancer.modules.voicebridge.model.SvcCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Optional;
import java.util.UUID;

/**
 * Simple Voice Chat's own {@code /voicechat join|leave|invite} refuse anybody whose client has no mod —
 * which is exactly a Discord-linked player. For them this answers those commands instead, so a click
 * on an SVC invite and the commands people already know just work. Everybody else is left to SVC,
 * except an invite aimed at a linked player, which SVC would refuse for the same reason.
 */
public final class VoicechatCommandListener implements IVoiceBridgeListener {

    private final VoiceBridgeServices services;

    public VoicechatCommandListener(VoiceBridgeServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Optional<SvcCommand> parsed = SvcCommand.parse(event.getMessage());
        if (parsed.isEmpty()) {
            return;
        }
        SvcCommand command = parsed.get();
        Player sender = event.getPlayer();
        boolean bridged = services.groups().isBridged(sender.getUniqueId());

        if (command.sub().equals("invite") && !command.args().isEmpty()) {
            Player target = services.server().getPlayerExact(command.args().getFirst());
            if (target != null && (bridged || services.groups().isBridged(target.getUniqueId()))) {
                event.setCancelled(true);
                answer(sender, services.groups().invite(sender.getUniqueId(), target.getUniqueId()),
                        "voicebridge.groups.invite-sent", "player", target.getName());
            }
            return;
        }
        if (!bridged) {
            return;
        }
        UUID player = sender.getUniqueId();
        switch (command.sub()) {
            case "join" -> {
                if (command.args().isEmpty()) {
                    return;
                }
                event.setCancelled(true);
                String password = command.args().size() > 1 ? command.args().get(1) : null;
                answer(sender, services.groups().join(player, command.args().getFirst(), password),
                        "voicebridge.groups.joined");
            }
            case "leave" -> {
                event.setCancelled(true);
                answer(sender, services.groups().leave(player), "voicebridge.groups.left");
            }
            default -> {
                // help and the admin commands are SVC's to answer, or refuse.
            }
        }
    }

    private void answer(Player sender, String refusal, String done, Object... values) {
        services.messages().send(sender, refusal.isEmpty() ? done : refusal, values);
    }

    @Override
    public void forget(UUID player) {
        // Remembers nobody; every command is judged as it arrives.
    }
}
