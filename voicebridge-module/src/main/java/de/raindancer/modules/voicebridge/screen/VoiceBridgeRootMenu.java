package de.raindancer.modules.voicebridge.screen;

import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.voicebridge.VoiceBridgeServices;
import de.raindancer.modules.voicebridge.model.BridgeStatus;
import de.raindancer.modules.voicebridge.service.GroupService;
import de.raindancer.modules.voicebridge.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What {@code /voicebridge} opens: whether Discord is connected, who is on either side, and the one
 * thing a player does here — step into the bridged group or out of it.
 */
public final class VoiceBridgeRootMenu extends Menu implements IVoiceBridgeScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** Names past this are counted rather than listed; lore taller than the screen helps nobody. */
    private static final int NAMES_SHOWN = 12;

    private final VoiceBridgeServices services;

    public VoiceBridgeRootMenu(VoiceBridgeServices services, Player viewer) {
        super(viewer, services.brand(), null);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Discord");
    }

    @Override
    public String breadcrumb() {
        return "Discord voice";
    }

    @Override
    protected void render() {
        BridgeStatus status = services.bridge().status();
        String group = services.gateway().groupName();

        set(MenuLayout.HEADER_LEFT, statusIcon(status));

        List<String> inGroup = new ArrayList<>();
        for (UUID member : services.gateway().members()) {
            Player online = services.server().getPlayer(member);
            if (online != null) {
                inGroup.add(online.getName());
            }
        }
        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.NOTE_BLOCK,
                "<white>Voice chat group <aqua>" + escape(group),
                names(inGroup, "<gray>Nobody in game is in it.")));

        set(MenuLayout.HEADER_RIGHT, Icons.of(Material.JUKEBOX,
                status.isConnected() ? "<white>On Discord in <aqua>" + escape(status.channelName()) : "<white>On Discord",
                status.isConnected() ? names(status.discordMembers(), "<gray>Nobody is in the channel.")
                        : List.of("<gray>Not connected.")));

        boolean member = services.gateway().isMember(viewer.getUniqueId());
        boolean hasVoicechat = services.gateway().hasVoicechat(viewer.getUniqueId());
        boolean bridged = services.groups().isBridged(viewer.getUniqueId());
        if (member) {
            band(MenuLayout.RULES, 3, true, Icons.of(Material.RED_DYE,
                            "<red>Leave the Discord group",
                            "<gray>Back to talking with whoever is near you.",
                            "<dark_gray>Discord stops hearing you straight away."),
                    "", click -> act(services.bridge().leave(viewer.getUniqueId()), "voicebridge.leave.done"));
        } else {
            band(MenuLayout.RULES, 3, hasVoicechat || bridged, Icons.of(Material.LIME_DYE,
                            "<green>Join the Discord group",
                            "<gray>Hear the Discord channel, and be heard there.",
                            "<yellow>Everything you say is sent to Discord",
                            "<yellow>while you are in the group."),
                    "You need the Simple Voice Chat mod, or a linked Discord account.",
                    click -> act(PermissionNodes.svc(viewer, GroupService.SVC_GROUPS_PERMISSION)
                            ? services.bridge().join(viewer.getUniqueId(), bridged)
                            : "voicebridge.groups.no-permission", "voicebridge.join.done"));
        }

        boolean linked = services.links().discordOf(viewer.getUniqueId()).isPresent();
        band(MenuLayout.RULES, 1, true, linked
                        ? Icons.of(Material.NAME_TAG, "<green>Discord account linked",
                        "<gray>Talk through Discord in proximity chat:",
                        "<gray>join the lobby voice channel there.",
                        "<dark_gray>Click to unlink.")
                        : Icons.of(Material.NAME_TAG, "<white>Link your Discord account",
                        "<gray>Get a code, then type /link <code>",
                        "<gray>in the Discord server.",
                        "<dark_gray>Then talk in proximity chat through Discord."),
                "", click -> link(linked));

        band(MenuLayout.RULES, 5, bridged, Icons.of(Material.BELL, "<white>Voice chat groups",
                        "<gray>Join, leave, make and invite to groups,",
                        "<gray>like the voice chat's own group screen."),
                hasVoicechat ? "You have the mod: use the voice chat's own group screen (G)."
                        : "Link your Discord account first.",
                click -> new VoiceGroupsMenu(services, viewer, this).open());

        boolean admin = viewer.hasPermission(PermissionNodes.ADMIN);
        band(MenuLayout.RULES, 7, admin, Icons.of(Material.ENDER_PEARL,
                        "<white>Reconnect the bot",
                        "<gray>Reads the token file again and rejoins",
                        "<gray>the channel set in /settings."),
                "Only staff can reconnect the Discord bot.",
                click -> reconnect());
    }

    private ItemStack statusIcon(BridgeStatus status) {
        return switch (status.phase()) {
            case CONNECTED -> Icons.of(Material.LIME_CONCRETE, "<green>Connected to Discord",
                    "<gray>Channel <white>" + escape(status.channelName()),
                    "<gray>" + status.discordMembers().size() + " on Discord, "
                            + services.gateway().members().size() + " in game");
            case CONNECTING -> Icons.of(Material.YELLOW_CONCRETE, "<yellow>Connecting to Discord…");
            case FAILED -> Icons.of(Material.RED_CONCRETE, "<red>Not connected",
                    services.messages().raw(status.detail()));
            case OFF -> Icons.of(Material.GRAY_CONCRETE, "<gray>Not connected",
                    services.messages().raw(status.detail()));
        };
    }

    private static List<String> names(List<String> names, String nobody) {
        if (names.isEmpty()) {
            return List.of(nobody);
        }
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < Math.min(NAMES_SHOWN, names.size()); i++) {
            lines.add("<white>" + escape(names.get(i)));
        }
        if (names.size() > NAMES_SHOWN) {
            lines.add("<gray>and " + (names.size() - NAMES_SHOWN) + " more");
        }
        return lines;
    }

    private static String escape(String text) {
        return MINI.escapeTags(text);
    }

    private void act(String refusal, String done) {
        if (refusal.isEmpty()) {
            services.effects().play(viewer.getUniqueId(), Cues.OK);
            services.messages().send(viewer, done);
        } else {
            services.effects().play(viewer.getUniqueId(), Cues.NO);
            services.messages().send(viewer, refusal);
        }
        refresh();
    }

    private void link(boolean linked) {
        viewer.closeInventory();
        if (linked) {
            services.lobby().unlinked(viewer.getUniqueId());
            services.links().unlink(viewer.getUniqueId());
            services.messages().send(viewer, "voicebridge.link.unlinked");
        } else {
            services.messages().send(viewer, "voicebridge.link.code", "code",
                    services.links().codeFor(viewer.getUniqueId()));
        }
    }

    private void reconnect() {
        if (!viewer.hasPermission(PermissionNodes.ADMIN)) {
            services.messages().send(viewer, "voicebridge.reconnect.not-allowed");
            services.effects().play(viewer.getUniqueId(), Cues.NO);
            return;
        }
        services.bridge().reconnect();
        services.messages().send(viewer, "voicebridge.reconnect.started");
        services.effects().play(viewer.getUniqueId(), Cues.OK);
        refresh();
    }

    @Override
    protected List<String> helpLines() {
        return List.of(
                "A Simple Voice Chat group, joined to a Discord voice channel.",
                "",
                "Everybody in the group hears the Discord channel,",
                "and everybody in the Discord channel hears the group.",
                "Nobody outside the group is ever sent to Discord.",
                "",
                "Join from here, with /voicebridge join, or from the",
                "voice chat's own group menu. Turn Discord down for",
                "yourself in the voice chat volume settings.");
    }

    @Override
    public String describe() {
        return "the Discord bridge's status, who is on either side, and joining the group";
    }
}
