package de.raindancer.modules.twitch.rules;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What an announcement may say when it came from another server over the link: the channel must be a Twitch login and the
 * link is built here from it — a click on the banner can only ever open twitch.tv — and the free text is cleaned and cut.
 */
public final class AnnouncementRule implements ITwitchRule {

    private static final Pattern LOGIN = Pattern.compile("[a-z0-9_]{1,25}");

    public String channelOf(String fromTheLink, String own) {
        String clean = fromTheLink == null ? "" : fromTheLink.strip().toLowerCase(Locale.ROOT);
        return LOGIN.matcher(clean).matches() ? clean : own;
    }

    public String urlOf(String channel) {
        return "https://www.twitch.tv/" + channel;
    }

    public String clean(String text, int longest) {
        if (text == null) {
            return "";
        }
        String plain = text.replaceAll("[\\p{Cntrl}]+", " ").replaceAll("\\s{2,}", " ").strip();
        return plain.length() <= longest ? plain : plain.substring(0, longest).strip();
    }

    @Override
    public String describe() {
        return "what an announcement from another server may say: a Twitch login, twitch.tv's own link, cleaned text";
    }
}
