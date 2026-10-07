package de.raindancer.modules.voicebridge.screen;

import de.raindancer.core.ui.choose.PlayerChooser;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.voicebridge.VoiceBridgeServices;
import de.raindancer.modules.voicebridge.service.GroupService.GroupView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Simple Voice Chat's group screen, for somebody whose client has no mod: every group, who is in it,
 * and joining, leaving, creating and inviting.
 */
public final class VoiceGroupsMenu extends PaginatedMenu<GroupView> implements IVoiceBridgeScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final VoiceBridgeServices services;

    public VoiceGroupsMenu(VoiceBridgeServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Groups");
    }

    @Override
    public String breadcrumb() {
        return "Voice groups";
    }

    @Override
    protected List<GroupView> entries() {
        return services.groups().groups();
    }

    @Override
    protected ItemStack icon(GroupView group) {
        boolean mine = currentGroup().map(id -> id.equals(group.id())).orElse(false);
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + group.type() + (group.locked() ? ", <yellow>password" : ""));
        for (UUID member : group.members()) {
            Player online = services.server().getPlayer(member);
            if (online != null) {
                lore.add("<white>" + MINI.escapeTags(online.getName()));
            }
        }
        if (group.members().isEmpty()) {
            lore.add("<dark_gray>Nobody in it.");
        }
        lore.add("");
        lore.add(mine ? "<green>You are in this group." : group.locked()
                ? "<dark_gray>Click to be offered the join command; type the password after it."
                : "<dark_gray>Click to join.");
        return Icons.of(mine ? Material.LIME_STAINED_GLASS_PANE : group.locked() ? Material.IRON_DOOR : Material.OAK_DOOR,
                (mine ? "<green>" : "<white>") + MINI.escapeTags(group.name()), lore);
    }

    @Override
    protected void onClick(GroupView group, InventoryClickEvent event) {
        if (group.locked()) {
            viewer.closeInventory();
            services.messages().send(viewer, "voicebridge.groups.type-password", "group", group.name());
            return;
        }
        act(services.groups().join(viewer.getUniqueId(), group.id().toString(), null), "voicebridge.groups.joined");
    }

    @Override
    protected void render() {
        super.render();
        boolean inGroup = currentGroup().isPresent();
        toolbar(2, true, Icons.of(Material.WRITABLE_BOOK, "<white>Make a group",
                        "<gray>/voicebridge group create <name> [password] [type]",
                        "<dark_gray>Types: normal, open, isolated."),
                "", click -> {
                    viewer.closeInventory();
                    services.messages().send(viewer, "voicebridge.groups.create-how");
                });
        toolbar(4, inGroup, Icons.of(Material.PLAYER_HEAD, "<white>Invite somebody",
                        "<gray>They get a button that lets them in,", "<gray>password or not."),
                "You need to be in a group to invite to it.", click -> invite());
        toolbar(6, inGroup, Icons.of(Material.RED_DYE, "<red>Leave your group",
                        "<gray>Back to talking with whoever is near you."),
                "You are not in a group.", click -> act(services.groups().leave(viewer.getUniqueId()), "voicebridge.groups.left"));
    }

    private Optional<UUID> currentGroup() {
        return services.groups().groupOf(viewer.getUniqueId()).map(group -> group.getId());
    }

    private void invite() {
        new PlayerChooser(viewer, services.brand(), this, "Invite to your voice group",
                List.of(viewer.getUniqueId()), entry -> {
                    String refusal = services.groups().invite(viewer.getUniqueId(), entry.id());
                    services.messages().send(viewer, refusal.isEmpty() ? "voicebridge.groups.invite-sent" : refusal,
                            "player", entry.name());
                    reopen();
                }).open();
    }

    private void act(String refusal, String done) {
        services.effects().play(viewer.getUniqueId(), refusal.isEmpty() ? Cues.OK : Cues.NO);
        services.messages().send(viewer, refusal.isEmpty() ? done : refusal);
        refresh();
    }

    @Override
    protected List<String> helpLines() {
        return List.of(
                "Simple Voice Chat's groups, for players talking through Discord.",
                "",
                "Click a group to join it. A locked one needs its password:",
                "type /voicechat join <name> <password>, or ask somebody",
                "inside to invite you — an invite lets you in without it.",
                "",
                "Normal: the group hears itself, and you still hear people near you.",
                "Open: people near you hear the group too.",
                "Isolated: only the group, nobody else.");
    }

    @Override
    public String describe() {
        return "every voice chat group, and joining, leaving, making and inviting";
    }
}
