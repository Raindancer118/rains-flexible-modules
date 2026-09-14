package de.raindancer.modules.manhunt.command;

import de.raindancer.core.RainsCore;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.manhunt.IManhuntScreensOpener;
import de.raindancer.modules.manhunt.ManhuntServices;
import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.service.ChaosService;
import de.raindancer.modules.manhunt.service.HuntHistory;
import de.raindancer.modules.manhunt.service.ManhuntAchievements;
import de.raindancer.modules.manhunt.service.ManhuntDeathListener;
import de.raindancer.modules.manhunt.service.ManhuntLobbyListener;
import de.raindancer.modules.manhunt.service.ManhuntService;
import de.raindancer.modules.manhunt.service.ManhuntSpectators;
import de.raindancer.modules.manhunt.service.ManhuntWhitelistService;
import de.raindancer.modules.manhunt.service.TrackerCompassService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandException;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@code open}/{@code close} are this module's own words; everything else passes straight through to
 * vanilla's {@code minecraft:whitelist} via {@link Bukkit#dispatchCommand}. A word that is neither —
 * {@code enable}, say, which is nobody's word: not Manhunt's (only open/close) and not vanilla's
 * (on/off) — used to reach the server console as an unhandled {@link CommandException} with a full
 * stack trace, because {@link Bukkit#dispatchCommand} does not catch what a directly-typed command's
 * own top-level dispatcher normally would. This is that path, sealed.
 */
class WhitelistCommandTest {

    private ManhuntServices services;
    private Messages messages;
    private WhitelistCommand command;
    private CommandSourceStack source;
    private CommandSender sender;

    @BeforeEach
    void setUp() {
        messages = mock(Messages.class);
        services = new ManhuntServices(
                mock(Plugin.class), mock(Server.class), mock(RainsCore.class), mock(LogChannel.class),
                messages, mock(Chat.class), mock(Brand.class),
                () -> ManhuntSettings.DEFAULTS, mock(SettingsStore.class),
                mock(ManhuntService.class), mock(ChaosService.class), mock(ManhuntWhitelistService.class),
                mock(ManhuntAchievements.class), mock(ManhuntLobbyListener.class),
                mock(TrackerCompassService.class), mock(ManhuntDeathListener.class),
                mock(ManhuntSpectators.class), mock(HuntHistory.class), mock(IManhuntScreensOpener.class));
        command = new WhitelistCommand(() -> services);
        sender = mock(CommandSender.class);
        source = mock(CommandSourceStack.class);
        org.mockito.Mockito.when(source.getSender()).thenReturn(sender);
    }

    private MockedStatic<Bukkit> bukkit;

    @AfterEach
    void closeStatic() {
        if (bukkit != null) {
            bukkit.close();
        }
    }

    @Test
    @DisplayName("a word that is neither Manhunt's nor vanilla's is caught, not thrown at the console")
    void unknownWordIsCaughtCleanly() {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.dispatchCommand(sender, "minecraft:whitelist enable"))
                .thenThrow(new CommandException("Incorrect argument for command"));

        command.execute(source, new String[]{"enable"});

        verify(messages).send(org.mockito.ArgumentMatchers.eq(sender),
                anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("a real vanilla word still passes straight through, unchanged")
    void knownVanillaWordStillPassesThrough() {
        bukkit = mockStatic(Bukkit.class);

        command.execute(source, new String[]{"on"});

        bukkit.verify(() -> Bukkit.dispatchCommand(sender, "minecraft:whitelist on"), times(1));
        verify(messages, never()).send(any(CommandSender.class), anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("open/close are still this module's own words, never handed to vanilla")
    void openIsHandledLocally() {
        bukkit = mockStatic(Bukkit.class);

        command.execute(source, new String[]{"open"});

        bukkit.verify(() -> Bukkit.dispatchCommand(any(CommandSender.class), anyString()), never());
    }
}
