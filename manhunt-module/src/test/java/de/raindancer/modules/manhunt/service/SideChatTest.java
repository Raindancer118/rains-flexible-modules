package de.raindancer.modules.manhunt.service;

import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.service.SideChat.Audience;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Who hears what. Pure, no Bukkit — the listener only filters an event's viewers by these answers. */
class SideChatTest {

    private static SideChat chat() {
        return new SideChat(ManhuntSettings.DEFAULTS);
    }

    @Test
    @DisplayName("outside a hunt, this module does not touch anybody's chat")
    void notRunning() {
        assertThat(chat().audienceFor("hello", false, true)).isEqualTo(Audience.EVERYBODY);
    }

    @Test
    @DisplayName("a bystander is never narrowed to a side they are not on")
    void bystander() {
        assertThat(chat().audienceFor("hello", true, false)).isEqualTo(Audience.EVERYBODY);
    }

    @Test
    @DisplayName("with side chat off, everybody hears everybody as usual")
    void switchedOff() {
        SideChat off = new SideChat(ManhuntSettings.DEFAULTS.withSideChat(false));

        assertThat(off.audienceFor("hello", true, true)).isEqualTo(Audience.EVERYBODY);
    }

    @Test
    @DisplayName("mid-hunt, a participant is heard by their own side")
    void ownSide() {
        assertThat(chat().audienceFor("they went nether", true, true)).isEqualTo(Audience.OWN_SIDE);
    }

    @Test
    @DisplayName("the prefix breaks out to everybody")
    void prefixBreaksOut() {
        assertThat(chat().audienceFor("!be right back", true, true)).isEqualTo(Audience.EVERYBODY);
    }

    @Test
    @DisplayName("the prefix is taken off before the message is shown")
    void prefixIsStripped() {
        assertThat(chat().strip("! be right back")).isEqualTo("be right back");
        assertThat(chat().strip("!brb")).isEqualTo("brb");
    }

    @Test
    @DisplayName("a message without the prefix is left exactly as it was")
    void nonPrefixedIsUntouched() {
        assertThat(chat().strip("they went nether")).isEqualTo("they went nether");
    }

    @Test
    @DisplayName("an empty prefix takes the way out away entirely")
    void noPrefixConfigured() {
        SideChat sealed = new SideChat(ManhuntSettings.DEFAULTS.withSideChatGlobalPrefix(""));

        assertThat(sealed.audienceFor("!still my side", true, true)).isEqualTo(Audience.OWN_SIDE);
        assertThat(sealed.globalPrefix()).isEmpty();
        assertThat(sealed.strip("!still my side")).isEqualTo("!still my side");
    }

    @Test
    @DisplayName("live settings are re-read, not captured once")
    void settingsAreLive() {
        SideChat live = chat();
        live.settings(ManhuntSettings.DEFAULTS.withSideChat(false));

        assertThat(live.audienceFor("hello", true, true)).isEqualTo(Audience.EVERYBODY);
    }
}
