package de.raindancer.modules.cosmetics.service;

import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.ui.identity.Identities;
import de.raindancer.core.ui.identity.Nametags;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.text.Gradients;
import de.raindancer.core.ui.text.NameStyle;
import de.raindancer.modules.cosmetics.CosmeticsSettings;
import de.raindancer.modules.cosmetics.model.Catalogue;
import de.raindancer.modules.cosmetics.model.Grants;
import de.raindancer.modules.cosmetics.model.Preset;
import de.raindancer.modules.cosmetics.rules.NameStyleRule;
import de.raindancer.modules.cosmetics.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permissible;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Wearing a name style. The style lives in Core's {@link Identities} — not in a file of this module's —
 * because that is what chat, the tablist and nicknames already draw a name from; storing it here would
 * need every one of them to ask this module.
 */
public final class NameStyleService implements ICosmeticsService {

    private final Identities identities;
    private final Nametags nametags;
    private final Messages messages;
    private final Supplier<Catalogue> catalogue;
    private final NameStyleRule rule = new NameStyleRule();

    private volatile CosmeticsSettings settings;

    public NameStyleService(Identities identities, Nametags nametags, Messages messages,
                            Supplier<Catalogue> catalogue, CosmeticsSettings settings) {
        this.identities = identities;
        this.nametags = nametags;
        this.messages = messages;
        this.catalogue = catalogue;
        settings(settings);
    }

    @Override
    public void settings(CosmeticsSettings fresh) {
        this.settings = fresh;
        nametags.enabled(fresh.nameAboveHead());
    }

    /** Puts the styled nametag on or off as the settings say. Called once at enable. */
    public void applyNametagSetting() {
        nametags.enabled(settings.nameAboveHead());
    }

    /**
     * Floats their nametag — style, prefix and nickname as everybody sees it — in front of them for a
     * few seconds, seen by them alone.
     */
    public void previewNametag(Player who) {
        nametags.preview(who, identities.nametag(who.getUniqueId(), who.getName()), 6);
        messages.send(who, settings.nameAboveHead() ? "cosmetics.preview.nametag" : "cosmetics.preview.nametag-off");
    }

    public NameStyle current(UUID who) {
        return identities.nameStyle(who);
    }

    public int maxStops() {
        return settings.stops();
    }

    /** What this player may wear, read off their permissions now. */
    public Grants grantsOf(Permissible who) {
        Set<TextDecoration> decorations = EnumSet.noneOf(TextDecoration.class);
        for (TextDecoration decoration : TextDecoration.values()) {
            if (who.hasPermission(PermissionNodes.decoration(decoration))) {
                decorations.add(decoration);
            }
        }
        Set<String> presets = new HashSet<>();
        boolean every = who.hasPermission(PermissionNodes.PRESET_ALL);
        for (Preset preset : catalogue.get().presets()) {
            if (preset.restricted() && (every || who.hasPermission(preset.permission()))) {
                presets.add(preset.id());
            }
        }
        return new Grants(who.hasPermission(PermissionNodes.NAME_COLOUR),
                who.hasPermission(PermissionNodes.NAME_GRADIENT),
                who.hasPermission(PermissionNodes.NAME_ANY_COLOUR), decorations, presets);
    }

    public Verdict judge(Permissible who, NameStyle style) {
        return rule.judge(style, grantsOf(who), catalogue.get(), settings.stops());
    }

    public boolean mayUse(Permissible who, Preset preset) {
        return rule.mayUse(preset, grantsOf(who));
    }

    /** Their name in this style, for a preview or a chat line. */
    public static Component painted(String name, NameStyle style) {
        return Gradients.styled(name, style);
    }

    /** Puts a style on, or says why not. {@link NameStyle#NONE} takes it off. */
    public boolean wear(Player who, NameStyle style) {
        return wear(who, style, true);
    }

    /**
     * @param announce false from a menu, where the preview already shows the result and a chat line
     *                 per click would bury chat; a refusal is always said
     */
    public boolean wear(Player who, NameStyle style, boolean announce) {
        Verdict verdict = judge(who, style);
        if (verdict.isRefused()) {
            messages.send(who, verdict.reason(), "detail", verdict.detail() == null ? "" : verdict.detail());
            return false;
        }
        if (!identities.setNameStyle(who.getUniqueId(), style)) {
            messages.send(who, "cosmetics.name.not-saved");
            return false;
        }
        if (!announce) {
            return true;
        }
        if (style.isEmpty()) {
            messages.send(who, "cosmetics.name.cleared");
        } else {
            messages.send(who, "cosmetics.name.set", "name", painted(who.getName(), style));
        }
        return true;
    }

    /** "pink → light blue, bold" — the style in words, for lore and for anyone who cannot tell two blues apart. */
    public String describe(NameStyle style) {
        if (style.isEmpty()) {
            return "plain";
        }
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (!style.colours().isEmpty()) {
            parts.add(String.join(" → ", style.colours().stream().map(catalogue.get()::nameOf).toList()));
        }
        style.decorations().forEach(decoration ->
                parts.add(decoration.name().toLowerCase(java.util.Locale.ROOT)));
        return String.join(", ", parts);
    }

    public boolean wear(Player who, Preset preset) {
        if (!mayUse(who, preset)) {
            messages.send(who, "cosmetics.refused.preset", "preset", preset.title());
            return false;
        }
        return wear(who, preset.style());
    }

    /** Staff taking somebody's style off — the one way to do it to somebody else. */
    public void resetOther(CommandSender staff, OfflinePlayer target) {
        String name = target.getName() == null ? target.getUniqueId().toString() : target.getName();
        identities.setNameStyle(target.getUniqueId(), NameStyle.NONE);
        messages.send(staff, "cosmetics.name.reset-other", "player", name);
        Player online = target.getPlayer();
        if (online != null && online != staff) {
            messages.send(online, "cosmetics.name.reset-by-staff");
        }
    }

    /**
     * On join: a style they may no longer wear is taken off and they are told — the rank that paid for
     * it is gone. Only when the owner left that switched on.
     */
    public void revalidate(Player who) {
        if (!settings.dropWithoutPermission()) {
            return;
        }
        NameStyle style = identities.nameStyle(who.getUniqueId());
        if (style.isEmpty()) {
            return;
        }
        Verdict verdict = judge(who, style);
        if (verdict.isRefused()) {
            identities.setNameStyle(who.getUniqueId(), NameStyle.NONE);
            messages.send(who, "cosmetics.name.dropped");
        }
    }

    @Override
    public String describe() {
        return "what somebody's name is painted in";
    }
}
