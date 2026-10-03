package de.raindancer.modules.speedrun;

import de.raindancer.core.data.settings.SettingsNavigation;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.prompt.ChatPrompts;
import org.bukkit.plugin.Plugin;

/**
 * Everything the lobby's screens, chat buttons and records need beyond the lobby itself — handed to
 * {@link SpeedrunLobby#equip} by the module. A lobby built without one (every test that does not
 * ask for it) simply has no history, no HUD and no buttons; nothing else changes.
 *
 * @param navigation  Core's settings screens, for the "All settings" door
 * @param prompts     Core's chat prompts, for typing a time or a seed
 * @param buttons     Core's clickable chat buttons, for every suggested action
 */
public record SpeedrunToolkit(Plugin plugin, Brand brand, Chat chat, Messages messages,
                              SettingsNavigation navigation, ChatPrompts prompts, ChatButtons buttons,
                              Effects effects, SpeedrunHistory history, SpeedrunPlayerPrefs prefs,
                              SpeedrunHud hud) {
}
