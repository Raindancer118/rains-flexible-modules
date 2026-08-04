package de.raindancer.modules.pack;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

/**
 * Which pack this server wears, and how hard it insists.
 *
 * <p>The record <em>is</em> the schema: the file, its comments, its validation and the
 * {@code /settings} screens all come from it. {@link #DEFAULTS} points at the pack this server
 * actually uses, because a module whose defaults do nothing is one every server has to configure
 * before it works — and this one has exactly one job.
 */
@Settings(id = "serverpack", topics = {
        @Topic(path = "serverpack", title = "Server pack", icon = Material.PAINTING),
})
public record PackSettings(

        @In("serverpack") @Title("What it is called")
        @Describe("The name the pack is registered under. Only ever seen in the log and in "
                + "/settings — a server with two hosted packs tells them apart by this.")
        @Key("name")
        String name,

        @In("serverpack") @Title("Where it is downloaded from")
        @Describe("The full link to the .zip. Left empty, nothing is sent at all — which is the "
                + "right thing for a server that does not want a pack, and is why this is a "
                + "setting rather than something built in.")
        @Key("url")
        String url,

        @In("serverpack") @Title("What it must hash to")
        @Describe("The pack's sha1. A client caches by this, so a pack sent without one is "
                + "downloaded again on every single join. Left empty it is looked up once at "
                + "startup from sha1.txt beside the pack — which is the point: the pack can then "
                + "be updated without touching this file.")
        @Key("sha1")
        String sha1,

        @In("serverpack") @Title("Look the hash up automatically")
        @Describe("Whether to read sha1.txt from beside the pack at startup. On, updating the pack "
                + "on its host is all there is to do. Off, the hash above is used exactly as "
                + "written and a changed pack needs this file edited.")
        @Key("look-up-hash")
        boolean lookUpHash,

        @In("serverpack") @Title("Refusing it means being disconnected")
        @Describe("Whether somebody who declines the download is kicked. Worth thinking about: a "
                + "required pack turns every download failure — a proxy, a slow connection, a "
                + "client bug — into a player who cannot join and does not know why.")
        @Key("required")
        boolean required,

        @In("serverpack") @Title("What the download prompt says")
        @Describe("The line the client shows when it asks. Empty leaves the client's own wording.")
        @Key("prompt")
        String prompt) {

    /**
     * What this server actually uses.
     *
     * <p>The hash is deliberately empty: it is looked up from the host at startup, so updating the
     * pack does not mean editing every server that wears it. See {@code PackSha1Lookup}.
     */
    public static final PackSettings DEFAULTS = new PackSettings(
            "yeukpack",
            "https://mc-packs.raindancer118.de/yeukpack/yeukpack.zip",
            "",
            true,
            true,
            "This server has its own resource pack. It is needed for the custom sounds and models.");

    /** Whether there is anything to send at all. */
    public boolean isConfigured() {
        return url != null && !url.isBlank();
    }

    /**
     * Where the hash is published, worked out from the pack's own link.
     *
     * <p>{@code sha1.txt} beside the zip, which is what the pack host writes. Derived rather than
     * configured: two links in a file are two things to keep in step, and the one nobody updates is
     * the one that silently keeps sending yesterday's hash.
     */
    public String hashUrl() {
        if (!isConfigured()) {
            return "";
        }
        int lastSlash = url.lastIndexOf('/');
        return lastSlash < 0 ? "" : url.substring(0, lastSlash + 1) + "sha1.txt";
    }

    /** The file name the hash file lists it under — {@code yeukpack.zip}. */
    public String fileName() {
        if (!isConfigured()) {
            return "";
        }
        int lastSlash = url.lastIndexOf('/');
        return lastSlash < 0 ? url : url.substring(lastSlash + 1);
    }

    // ------------------------------------------------------------------ one component at a time

    public PackSettings withName(String value) {
        return new PackSettings(value, url, sha1, lookUpHash, required, prompt);
    }

    public PackSettings withUrl(String value) {
        return new PackSettings(name, value, sha1, lookUpHash, required, prompt);
    }

    public PackSettings withSha1(String value) {
        return new PackSettings(name, url, value, lookUpHash, required, prompt);
    }

    public PackSettings withLookUpHash(boolean value) {
        return new PackSettings(name, url, sha1, value, required, prompt);
    }

    public PackSettings withRequired(boolean value) {
        return new PackSettings(name, url, sha1, lookUpHash, value, prompt);
    }

    public PackSettings withPrompt(String value) {
        return new PackSettings(name, url, sha1, lookUpHash, required, value);
    }
}
