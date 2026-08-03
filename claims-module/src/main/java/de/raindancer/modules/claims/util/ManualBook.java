package de.raindancer.modules.claims.util;

import de.raindancer.modules.claims.ClaimServices;
import de.raindancer.modules.claims.model.ClaimFeature;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * The in-game manual, as a written book — one edition for players, one for staff.
 * <p>
 * Ported from Rain's Extended Claims, minus everything about towns: this module does not have them, and
 * a page describing a feature that does not exist is worse than no page at all. What is kept is written
 * against what this module actually offers rather than against everything the old plugin could do — the
 * same discipline the original had, just aimed at a narrower target.
 * <p>
 * Commands in the text are clickable: clicking runs the harmless ones outright and types the rest into
 * the chat bar for the reader to finish. Nothing destructive is ever click-to-run — a manual that can
 * delete your claim by being read carelessly is a trap, so those are printed plainly. Where the rebuilt
 * module moved something from a command into a menu button, the text says so instead of naming a command
 * that no longer exists — a manual that lists a command nobody can run is worse than no manual.
 */
public final class ManualBook {

    /** Which edition. They share the machinery and nothing else. */
    public enum Edition {
        /** Everything a player can do with their own land. */
        PLAYER("Land", "Player's manual"),
        /** Everything staff govern, and where each lever lives. */
        ADMIN("Claims: Staff Manual", "Server administration");

        private final String title;
        private final String subtitle;

        Edition(String title, String subtitle) {
            this.title = title;
            this.subtitle = subtitle;
        }

        public String title() {
            return title;
        }

        public String subtitle() {
            return subtitle;
        }
    }

    /**
     * Everything the manual needs to know about the server, and nothing else.
     * <p>
     * A seam rather than the whole module: the one thing worth testing about a book is whether its pages
     * fit, and that needs no server, only a set of answers. {@link #everything()} is the longest the book
     * can get, which is the version the layout has to survive.
     */
    public interface Availability {

        boolean feature(ClaimFeature feature);

        /** Whether the server leaves any protection flag at all for an owner to change. */
        boolean anyClaimFlag();

        static Availability of(ClaimServices services) {
            return new Availability() {
                @Override
                public boolean feature(ClaimFeature feature) {
                    return services.features().isOffered(feature);
                }

                @Override
                public boolean anyClaimFlag() {
                    return !services.flags().editableFlags().isEmpty();
                }
            };
        }

        /** Every feature on: the longest the book ever gets, and so the one that must fit. */
        static Availability everything() {
            return new Availability() {
                @Override
                public boolean feature(ClaimFeature feature) {
                    return true;
                }

                @Override
                public boolean anyClaimFlag() {
                    return true;
                }
            };
        }
    }

    private final Availability server;
    private final Edition edition;

    public ManualBook(ClaimServices services, Edition edition) {
        this(Availability.of(services), edition);
    }

    public ManualBook(Availability server, Edition edition) {
        this.server = server;
        this.edition = edition;
    }

    private boolean has(ClaimFeature feature) {
        return server.feature(feature);
    }

    // ------------------------------------------------------------ MiniMessage helpers

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private static Component mm(String miniMessage) {
        return MINI.deserialize(miniMessage);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    // ------------------------------------------------------------ the two forms

    /** The book as an item, to keep in a chest or hand to somebody. */
    public ItemStack asItem() {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.title(mm("<dark_aqua>" + edition.title()));
        meta.author(mm("<gray>Rain's Flexible Modules"));
        meta.addPages(pages().toArray(new Component[0]));
        book.setItemMeta(meta);
        return book;
    }

    /** The same content for {@code Player#openBook}, which shows it without handing anything over. */
    public Book asBook() {
        return Book.book(mm("<dark_aqua>" + edition.title()),
                mm("<gray>Rain's Flexible Modules"), pages());
    }

    // ------------------------------------------------------------ clickable pieces

    /**
     * A command that is safe to fire on a stray click — it opens something or prints something.
     * <p>
     * The hover says what will happen, because a click in a book is irreversible from the reader's point
     * of view: there is no confirmation step between the page and the command.
     */
    private static Component run(String command) {
        return mm("<blue><underlined>" + command)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(mm("<gray>Click to run <white>" + command)));
    }

    /**
     * A command that needs finishing, or that changes something. Clicking types it into the chat bar and
     * leaves the reader to press enter, so nothing happens by accident.
     */
    private static Component type(String command) {
        return mm("<blue>" + command)
                .clickEvent(ClickEvent.suggestCommand(command.replace("<", "").replace(">", "")))
                .hoverEvent(HoverEvent.showText(
                        mm("<gray>Click to type it, then fill in the blanks")));
    }

    /** Plain text, for the commands nobody should fire from a page they are skim-reading. */
    private static Component plainCommand(String command) {
        return mm("<dark_blue>" + command);
    }

    // ------------------------------------------------------------ assembling pages

    /** Public so the layout can be measured: an overrunning page is silent, not an error. */
    public List<Component> pages() {
        return edition == Edition.PLAYER ? playerPages() : adminPages();
    }

    /** Lines a client draws on one page, and the width it wraps at. Both conservative. */
    private static final int LINES_PER_PAGE = 14;
    private static final int CHARS_PER_LINE = 19;

    /**
     * Lays a section out over as many pages as it needs, reflowing its prose first.
     * <p>
     * Two problems solved in one place, both of which the author of a page should not have to think
     * about. First, a section longer than a page is silently truncated by the client — no error, the
     * text simply stops. Second, prose written as short source lines wraps twice: once where it was
     * typed and again where the client decides, which reads as ragged. Consecutive plain lines sharing
     * a colour are therefore joined back into a paragraph and left for the client to break properly.
     * <p>
     * Bullets, blank lines and clickable commands are never joined — a bullet list is a list, and a
     * command merged into a sentence would lose its click.
     */
    private static List<Component> spread(Object... lines) {
        List<Component> body = reflow(lines);
        if (body.isEmpty()) {
            return List.of();
        }
        boolean headed = !(lines[0] instanceof String);
        Component title = headed ? body.get(0) : null;
        if (headed) {
            body.remove(0);
            if (!body.isEmpty() && plain(body.get(0)).isEmpty()) {
                body.remove(0);
            }
        }

        // Budgeted against the continuation form, the wider of the two: the "…" can push a heading
        // onto a second line, and a page that fitted without it would then overrun.
        int headingCost = headed ? cost(title.append(mm(" …"))) + 1 : 0;
        List<Component> pages = new ArrayList<>();
        List<Component> current = new ArrayList<>();
        int used = 0;
        for (Component line : body) {
            int lineCost = cost(line);
            if (used + lineCost + headingCost > LINES_PER_PAGE && !current.isEmpty()) {
                pages.add(join(title, current, pages.isEmpty()));
                current = new ArrayList<>();
                used = 0;
            }
            current.add(line);
            used += lineCost;
        }
        if (!current.isEmpty()) {
            pages.add(join(title, current, pages.isEmpty()));
        }
        return pages;
    }

    /** Joins runs of same-coloured prose into paragraphs; everything else passes through untouched. */
    private static List<Component> reflow(Object... lines) {
        List<Component> out = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        String runTag = null;
        for (Object line : lines) {
            String tag = line instanceof String text ? proseTag(text) : null;
            if (tag == null) {
                if (runTag != null) {
                    out.add(mm(runTag + run));
                    run.setLength(0);
                    runTag = null;
                }
                if (line instanceof Component component) {
                    out.add(component);
                } else if (line instanceof String text) {
                    out.add(text.isEmpty() ? Component.empty() : mm(text));
                }
                continue;
            }
            if (runTag != null && !runTag.equals(tag)) {
                out.add(mm(runTag + run));
                run.setLength(0);
            }
            if (run.length() > 0) {
                run.append(' ');
            }
            run.append(((String) line).substring(tag.length()).trim());
            runTag = tag;
        }
        if (runTag != null) {
            out.add(mm(runTag + run));
        }
        return out;
    }

    /**
     * The colour tag of a line that is plain prose and safe to join, or {@code null} for anything that
     * has to keep its own line — bullets, blanks, and anything carrying further markup.
     */
    private static String proseTag(String line) {
        for (String tag : List.of("<black>", "<dark_gray>")) {
            if (!line.startsWith(tag)) {
                continue;
            }
            String rest = line.substring(tag.length());
            if (rest.isBlank() || rest.contains("<") || rest.trim().startsWith("•")) {
                return null;
            }
            return tag;
        }
        return null;
    }

    /**
     * How many drawn lines one entry takes once the client has wrapped it.
     * <p>
     * Wrapped at spaces the way the client does, rather than by dividing the length: word wrapping uses
     * <em>more</em> lines than chopping does, so dividing would under-count and let a page overrun.
     */
    static int cost(Component line) {
        return wrappedLines(plain(line));
    }

    /** Lines a run of text takes, broken at spaces at {@link #CHARS_PER_LINE}. */
    public static int wrappedLines(String plain) {
        if (plain.isEmpty()) {
            return 1;
        }
        int lines = 1;
        int used = 0;
        for (String word : plain.split(" ")) {
            int length = word.length();
            if (used > 0 && used + 1 + length <= CHARS_PER_LINE) {
                used += 1 + length;
                continue;
            }
            if (used > 0) {
                lines++;
            }
            // A word longer than the line is broken by the client wherever it must be.
            lines += (Math.max(1, length) - 1) / CHARS_PER_LINE;
            int remainder = length % CHARS_PER_LINE;
            used = remainder == 0 && length > 0 ? CHARS_PER_LINE : remainder;
        }
        return lines;
    }

    private static Component join(Component title, List<Component> body, boolean first) {
        List<Object> parts = new ArrayList<>();
        if (title != null) {
            parts.add(first ? title : title.append(mm("<dark_gray> …")));
            parts.add("");
        }
        parts.addAll(body);
        return page(parts.toArray());
    }

    /** One page from a mixture of MiniMessage strings and ready-made components. */
    private static Component page(Object... lines) {
        Component page = Component.empty();
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) {
                page = page.append(Component.newline());
            }
            Object line = lines[index];
            if (line instanceof Component component) {
                page = page.append(component);
            } else if (line instanceof String text && !text.isEmpty()) {
                page = page.append(mm(text));
            }
        }
        return page;
    }

    private static Component heading(String title) {
        return mm("<dark_aqua><bold>" + title);
    }

    // ------------------------------------------------------------ the player's edition

    private List<Component> playerPages() {
        List<Component> pages = new ArrayList<>();

        pages.addAll(spread(heading("Land"), "",
                "<black>Everything you can do",
                "<black>with the ground you",
                "<black>stand on.",
                "",
                "<dark_gray>Blue text is clickable.",
                "<dark_gray>Underlined runs at once;",
                "<dark_gray>the rest lands in your",
                "<dark_gray>chat bar to finish.",
                "",
                run("/claim")));

        pages.addAll(spread(heading("Claiming land"), "",
                type("/claim new"),
                "<black>gives you the selection",
                "<black>stick.",
                "",
                "<black>Left-click one corner,",
                "<black>right-click the opposite.",
                "",
                "<dark_gray>Right-click the air with",
                "<dark_gray>the stick for its own menu:",
                "<dark_gray>click Shape for a free",
                "<dark_gray>outline instead of a",
                "<dark_gray>rectangle, and Finish",
                "<dark_gray>when you are done."));

        pages.addAll(spread(heading("Your claim's menu"), "",
                "<black>Click a claim in",
                run("/claim"),
                "<black>to manage it.",
                "",
                "<black>Everything about it is in",
                "<black>there: who may be here,",
                "<black>the rules inside it, the",
                "<black>land itself, and what it",
                "<black>keeps for you.",
                "",
                has(ClaimFeature.BORDER_PREVIEW)
                        ? "<dark_gray>Right-clicking a claim in" : "",
                has(ClaimFeature.BORDER_PREVIEW)
                        ? "<dark_gray>the list shows its borders." : ""));

        pages.addAll(spread(heading("Trusting people"), "",
                type("/claim trust <player>"),
                "",
                "<black>gives somebody the usual",
                "<black>rights on your claim.",
                "",
                "<black>For finer control open the",
                "<black>claim's menu and click",
                "<black><bold>Trusted people</bold>: seventeen",
                "<black>separate rights, per",
                "<black>person.",
                "",
                "<black>Take it back with",
                type("/claim untrust <player>")));

        pages.addAll(spread(heading("Everybody else"), "",
                "<black>The <bold>Everybody else</bold> button,",
                "<black>in your claim's menu,",
                "<black>sets what a passer-by may",
                "<black>do without being trusted.",
                "",
                "<black>Usually that is nothing,",
                "<black>but a shop or a public",
                "<black>farm wants more.",
                "",
                "<dark_gray>A trusted player's own",
                "<dark_gray>rights are added on top",
                "<dark_gray>of these."));

        if (server.anyClaimFlag()) {
            pages.addAll(spread(heading("Rules"), "",
                    "<black>Rules decide what happens",
                    "<black>inside: PvP, fire, mobs,",
                    "<black>fall damage, hunger.",
                    "",
                    "<black>Open your claim's menu",
                    "<black>and click <bold>Rules</bold>. They are",
                    "<black>grouped by subject, and a",
                    "<black>group shows how many you",
                    "<black>have changed.",
                    "",
                    "<black>The ones about <bold>people",
                    "<black>are set separately for",
                    "<black>owners, trusted and",
                    "<black>visitors."));

            pages.addAll(spread(heading("Rules, continued"), "",
                    "<black>Left-click a rule to set it",
                    "<black>for everybody at once.",
                    "",
                    "<black>Right-click one that",
                    "<black>differs per person to set",
                    "<black>owners, trusted and",
                    "<black>visitors apart.",
                    "",
                    "<black>A <bold>◐</bold> means the groups",
                    "<black>disagree.",
                    "",
                    "<dark_gray><bold>Follow the server again",
                    "<dark_gray>forgets what you set and",
                    "<dark_gray>goes back to its default."));

            pages.addAll(spread(heading("Rules, and the world"), "",
                    "<black>Rules about the <bold>world",
                    "<black>— fire, decay, pistons,",
                    "<black>spawning — are the same",
                    "<black>for everybody. A patch of",
                    "<black>leaves cannot decay for",
                    "<black>visitors only.",
                    "",
                    "<dark_gray>A rule shown as locked",
                    "<dark_gray>was fixed by the server",
                    "<dark_gray>staff. You cannot change",
                    "<dark_gray>it, and it says so."));
        }

        if (has(ClaimFeature.BANS)) {
            pages.addAll(spread(heading("Keeping people out"), "",
                    type("/claim ban <player>"),
                    "<black>keeps them out for good.",
                    "",
                    "<black>Take it back with",
                    type("/claim unban <player>"),
                    "",
                    "<dark_gray>Your claim's menu also",
                    "<dark_gray>lists who is barred, and",
                    "<dark_gray>lets you lift one early.",
                    "<dark_gray>Teleporting in counts as",
                    "<dark_gray>coming back."));
        }

        if (has(ClaimFeature.TITLES)) {
            pages.addAll(spread(heading("Titles"), "",
                    "<black>The big text that appears",
                    "<black>when somebody steps onto",
                    "<black>your claim.",
                    "",
                    "<black>Open your claim's menu",
                    "<black>and click <bold>Greetings</bold>: a",
                    "<black>big and a small line for",
                    "<black>arriving, and the same for",
                    "<black>leaving.",
                    "",
                    "<dark_gray>Click a line and type the",
                    "<dark_gray>new text in chat."));
        }

        if (has(ClaimFeature.BANK)) {
            pages.addAll(spread(heading("The claim bank"), "",
                    "<black>Where a claim's earnings",
                    "<black>collect: entry tolls,",
                    "<black>reclaimed fence blocks,",
                    "<black>and refunds when you",
                    "<black>shrink the claim.",
                    "",
                    "<black>Open your claim's menu",
                    "<black>and click <bold>Bank</bold>.",
                    "",
                    "<dark_gray>Anything in it is yours",
                    "<dark_gray>to withdraw."));
        }

        if (has(ClaimFeature.ENTRY_FEE)) {
            pages.addAll(spread(heading("Charging entry"), "",
                    "<black>You can ask visitors for",
                    "<black>a toll at the border.",
                    "",
                    "<black>Open your claim's menu",
                    "<black>and click <bold>Entry fee</bold> to",
                    "<black>switch it on, choose what",
                    "<black>is paid, and how long a",
                    "<black>paid pass lasts.",
                    "",
                    "<dark_gray>Trusted players and",
                    "<dark_gray>owners never pay. What",
                    "<dark_gray>they pay goes into the",
                    "<dark_gray>claim bank."));
        }

        if (has(ClaimFeature.FENCE)) {
            pages.addAll(spread(heading("Fences"), "",
                    "<black>A real fence along the",
                    "<black>border that follows the",
                    "<black>claim when you reshape.",
                    "",
                    "<black>Open your claim's menu",
                    "<black>and click <bold>Fence</bold> to put",
                    "<black>one up or take it down.",
                    "",
                    "<dark_gray>Taking it down pays the",
                    "<dark_gray>blocks back into the",
                    "<dark_gray>bank, so switching it off",
                    "<dark_gray>and on again costs",
                    "<dark_gray>nothing. Break a block to",
                    "<dark_gray>leave a gap; it is not",
                    "<dark_gray>filled back in."));
        }

        if (has(ClaimFeature.EFFECTS)) {
            pages.addAll(spread(heading("Potion effects"), "",
                    "<black>Your claim can grant an",
                    "<black>effect to everybody",
                    "<black>standing inside.",
                    "",
                    "<black>Open your claim's menu,",
                    "<black>click <bold>Perks</bold>, then",
                    "<black><bold>Effects</bold>.",
                    "",
                    "<dark_gray>Effects lapse the moment",
                    "<dark_gray>somebody steps out, so",
                    "<dark_gray>they cannot be carried",
                    "<dark_gray>away. Harmful ones are",
                    "<dark_gray>blocked."));
        }

        if (has(ClaimFeature.PANTRY)) {
            pages.addAll(spread(heading("The pantry"), "",
                    "<black>A shared larder that",
                    "<black>feeds hungry people",
                    "<black>inside the claim.",
                    "",
                    "<black>Open your claim's menu,",
                    "<black>click <bold>Perks</bold>, then",
                    "<black><bold>Pantry</bold> to switch it on",
                    "<black>and stock it."));
        }

        if (has(ClaimFeature.AUTO_EQUIP)) {
            pages.addAll(spread(heading("Auto-equip"), "",
                    "<black>The claim keeps people",
                    "<black>supplied from a stock:",
                    "<black>a totem in the off hand,",
                    "<black>armour in its slots.",
                    "",
                    "<black>Open your claim's menu,",
                    "<black>click <bold>Perks</bold>, then",
                    "<black><bold>Auto-equip</bold>.",
                    "",
                    "<dark_gray>Only ever fills an empty",
                    "<dark_gray>slot. Nothing you carry",
                    "<dark_gray>is swapped or taken."));
        }

        if (has(ClaimFeature.CLAIM_WEATHER) || has(ClaimFeature.CLAIM_TIME)) {
            String both = has(ClaimFeature.CLAIM_WEATHER) && has(ClaimFeature.CLAIM_TIME)
                    ? "own weather or time of" : has(ClaimFeature.CLAIM_WEATHER)
                    ? "own weather," : "own time of day,";
            pages.addAll(spread(heading(has(ClaimFeature.CLAIM_TIME)
                            && !has(ClaimFeature.CLAIM_WEATHER) ? "Time of day"
                            : has(ClaimFeature.CLAIM_WEATHER) && !has(ClaimFeature.CLAIM_TIME)
                            ? "Weather" : "Weather & time"), "",
                    "<black>Your claim can show its",
                    "<black>" + both,
                    "<black>day to whoever it serves.",
                    "",
                    "<black>Open your claim's menu",
                    "<black>and click <bold>Perks</bold>.",
                    "",
                    "<dark_gray>Client side only — the",
                    "<dark_gray>world keeps its real",
                    "<dark_gray>weather, so crops and",
                    "<dark_gray>mob spawning are not",
                    "<dark_gray>affected."));
        }

        List<Object> shaping = new ArrayList<>(List.of(heading("Reshaping"), ""));
        if (has(ClaimFeature.RESIZE)) {
            shaping.add("<black>Your claim's menu has a");
            shaping.add("<black><bold>Redraw the border</bold>");
            shaping.add("<black>button, which marks a new");
            shaping.add("<black>outline with the tool.");
            shaping.add("");
        }
        if (has(ClaimFeature.HEIGHT)) {
            shaping.add("<black>Its <bold>How deep and how");
            shaping.add("<black>high</bold> button changes the");
            shaping.add("<black>range without redrawing.");
            shaping.add("");
        }
        if (has(ClaimFeature.CLAIM_RENAME)) {
            shaping.add(type("/claim rename <name>"));
            shaping.add("<black>renames the claim you");
            shaping.add("<black>are standing in.");
        }
        if (has(ClaimFeature.CLAIM_ICON)) {
            shaping.add("<dark_gray>The <bold>Name and icon</bold>");
            shaping.add("<dark_gray>button also lets you set");
            shaping.add("<dark_gray>the item shown in your");
            shaping.add("<dark_gray>claim list: hold it and");
            shaping.add("<dark_gray>click.");
        }
        if (shaping.size() > 2) {
            pages.addAll(spread(shaping.toArray()));
        }

        pages.addAll(spread(heading("If you get lost"), "",
                run("/claim"),
                "<black>your claims and the menu",
                "",
                run("/claim help"),
                "<black>every command there is",
                "",
                "<dark_gray>Almost everything else is",
                "<dark_gray>clickable. Follow the",
                "<dark_gray>buttons."));

        return pages;
    }

    // ------------------------------------------------------------ the staff edition

    private List<Component> adminPages() {
        List<Component> pages = new ArrayList<>();

        pages.addAll(spread(heading("Staff Manual"), "",
                "<black>What you govern, and",
                "<black>where each lever lives.",
                "",
                "<black>Almost all of it is in the",
                "<black>menu:",
                run("/claimadmin"),
                "",
                "<dark_gray>Blue text is clickable.",
                "<dark_gray>Nothing destructive runs",
                "<dark_gray>from a click — those are",
                "<dark_gray>printed plainly."));

        pages.addAll(spread(heading("Two kinds of switch"), "",
                "<black><bold>Features</bold> — may a claim",
                "<black>do this at all? Open",
                run("/claimadmin"),
                "<black>and click <bold>What owners",
                "<black>may do</bold>.",
                "",
                "<black><bold>Flags</bold> — what happens",
                "<black>inside a claim?",
                run("/claimadmin flags"),
                "",
                "<dark_gray>A feature switched off",
                "<dark_gray>vanishes from the menus",
                "<dark_gray>and the commands, rather",
                "<dark_gray>than failing when used."));

        pages.addAll(spread(heading("Feature policies"), "",
                "<black>Click a feature to cycle",
                "<black>its policy:",
                "",
                "<black><bold>available</bold> — owners decide",
                "<black><bold>forced-on</bold> — always on",
                "<black><bold>forced-off</bold> — taken away",
                "",
                "<dark_gray>forced-on only appears for",
                "<dark_gray>features a claim has its",
                "<dark_gray>own switch for. For a bank",
                "<dark_gray>or a rename there is",
                "<dark_gray>nothing to override, so it",
                "<dark_gray>is skipped."));

        pages.addAll(spread(heading("Flag policies"), "",
                run("/claimadmin flags"),
                "",
                "<black>Left-click a flag to cycle:",
                "<black><bold>available</bold> — owner's call",
                "<black><bold>forced-on / forced-off",
                "<black><bold>disabled</bold> — not enforced",
                "<black>  at all, vanilla behaviour",
                "",
                "<black>Right-click flips what a",
                "<black>new claim starts with —",
                "<black>only meaningful while",
                "<black><bold>available</bold>."));

        pages.addAll(spread(heading("Flags & groups"), "",
                "<black>Flags about people carry",
                "<black>one value each for",
                "<black>owners, trusted and",
                "<black>visitors.",
                "",
                "<black>Your policy and default",
                "<black>apply to all three alike",
                "<black>— the split is the",
                "<black>owner's to make, not",
                "<black>yours.",
                "",
                "<dark_gray>A <bold>◐</bold> in a list means an",
                "<dark_gray>owner has split one."));

        pages.addAll(spread(heading("Managing claims"), "",
                run("/claimadmin overview"),
                "<black>claims, zones and who is",
                "<black>tracked right now",
                "",
                plainCommand("/claimadmin delete <owner/claim>"),
                "<dark_gray>not click-to-run on",
                "<dark_gray>purpose. The name is",
                "<dark_gray>owner/claim, so two",
                "<dark_gray>people's \"home\" is not",
                "<dark_gray>ambiguous."));

        pages.addAll(spread(heading("Bypass"), "",
                run("/claimadmin bypass"),
                "",
                "<black>ignores every claim",
                "<black>restriction while it is",
                "<black>on.",
                "",
                "<dark_gray>It is a toggle and it is",
                "<dark_gray>easy to forget. It also",
                "<dark_gray>waives entry tolls, which",
                "<dark_gray>is the polite way to walk",
                "<dark_gray>through a paid claim",
                "<dark_gray>while working."));

        pages.addAll(spread(heading("No-claim zones"), "",
                "<black>Ground nobody may claim:",
                "<black>spawn, event arenas, the",
                "<black>road network.",
                "",
                run("/claimadmin zone"),
                "<black>marks one out with the",
                "<black>tool, the same way a claim",
                "<black>is.",
                "",
                "<dark_gray>See them all, show one's",
                "<dark_gray>border or remove one from",
                "<dark_gray><bold>Where nobody may claim",
                "<dark_gray>in the admin menu."));

        pages.addAll(spread(heading("Costs & limits"), "",
                "<black>What a claim costs, how",
                "<black>many a player may hold,",
                "<black>and the rest of this",
                "<black>module's numbers now",
                "<black>live in",
                run("/settings"),
                "<black>alongside every other",
                "<black>plugin's, rather than in",
                "<black>a menu of their own.",
                "",
                "<dark_gray>Per-player overrides still",
                "<dark_gray>go through the permission",
                "<dark_gray>node rec.maxclaims.N"));

        pages.addAll(spread(heading("Config"), "",
                run("/claimadmin reload"),
                "<black>picks up worlds added to",
                "<black>the disabled list without",
                "<black>a restart",
                "",
                "<dark_gray>Everything else you set,",
                "<dark_gray>in the menu or through",
                run("/settings"),
                "<dark_gray>is written the moment you",
                "<dark_gray>change it — there is",
                "<dark_gray>nothing left to save by",
                "<dark_gray>hand."));

        pages.addAll(spread(heading("The player's book"), "",
                "<black>Players have their own",
                "<black>manual, written from",
                "<black>their side and covering",
                "<black>only what this server",
                "<black>actually offers.",
                "",
                "<dark_gray>Worth reading once after",
                "<dark_gray>changing the feature",
                "<dark_gray>policies — it is the",
                "<dark_gray>quickest way to see what",
                "<dark_gray>your server looks like",
                "<dark_gray>from the outside."));

        return pages;
    }
}
