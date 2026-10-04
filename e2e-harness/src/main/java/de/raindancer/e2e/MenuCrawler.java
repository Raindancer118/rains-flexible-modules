package de.raindancer.e2e;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Clicks every button of every page a bot can reach, and checks each click did something a player
 * would notice — another page, the page changed, a line in chat, the window closed, a command run.
 *
 * <p>Which slots are buttons is read from the menu itself, server-side, by the probe — never guessed
 * from what an item looks like. A page found behind a button is crawled in turn, reached the same way
 * every time: from its route's command, through the same clicks, so every click starts from the page
 * as a player would see it.
 *
 * <pre>{@code
 * MenuCrawler crawler = new MenuCrawler(server, ada, holder -> holder.startsWith("de.raindancer.modules.speedrun"));
 * crawler.skip("Start the countdown");          // a commit a scenario of its own plays
 * crawler.crawl(MenuCrawler.Route.command("speedrun menu"));
 * assertThat(crawler.silentButtons()).isEmpty();
 * }</pre>
 */
public final class MenuCrawler {

    private static final Duration SETTLE = Duration.ofSeconds(4);

    /** How to reach a page: something the bot does — a command, using an item — then the slots clicked from there. */
    public record Route(String label, java.util.function.Consumer<Bot> open, List<Integer> clicks) {

        /** Typing {@code command}. */
        public static Route command(String command) {
            return new Route("/" + command, bot -> bot.run(command), List.of());
        }

        /** Anything else that opens a page. */
        public static Route by(String label, java.util.function.Consumer<Bot> open) {
            return new Route(label, open, List.of());
        }

        Route then(int slot) {
            List<Integer> more = new ArrayList<>(clicks);
            more.add(slot);
            return new Route(label, open, List.copyOf(more));
        }

        @Override
        public String toString() {
            return label + (clicks.isEmpty() ? "" : " then " + clicks);
        }
    }

    /** One page as the probe saw it open. */
    private record Page(String holder, String title, Set<Integer> buttons, Set<Integer> entries,
                        Map<Integer, String> names, Map<Integer, String> lore) {

        String keyOf(int slot) {
            return MenuButtons.key(holder, slot, names.getOrDefault(slot, ""), entries.contains(slot));
        }

        /** Its buttons, controls first: a list's entries can use something up (a one-time choice). */
        List<Integer> inOrder() {
            List<Integer> ordered = new ArrayList<>(buttons.stream().filter(slot -> !entries.contains(slot)).toList());
            ordered.addAll(buttons.stream().filter(entries::contains).toList());
            return ordered;
        }
    }

    private final PaperServer server;
    private final Bot bot;
    private final Predicate<String> crawled;
    private final Set<String> skipped = new LinkedHashSet<>();
    /** Items that answer with their lore alone, by a part of their name, and why that is the answer. */
    private final Map<String, String> informational = new LinkedHashMap<>();
    /** Pages whose list entries are read, not acted on, by a part of their class name, and why. */
    private final Map<String, String> readOnlyPages = new LinkedHashMap<>();
    private final List<String> quietAsExpected = new ArrayList<>();
    private final Set<String> visited = new LinkedHashSet<>();
    private final List<String> silent = new ArrayList<>();
    private final List<String> clicked = new ArrayList<>();
    private final Set<String> clickedKeys = new java.util.HashSet<>();
    private final Map<String, String> oneShot = new LinkedHashMap<>();
    private final List<String> unreachable = new ArrayList<>();
    private Runnable between = () -> { };

    /** @param crawled which menus are this crawl's, by class name — a Core page they open is only clicked into */
    public MenuCrawler(PaperServer server, Bot bot, Predicate<String> crawled) {
        this.server = Objects.requireNonNull(server, "server");
        this.bot = Objects.requireNonNull(bot, "bot");
        this.crawled = Objects.requireNonNull(crawled, "crawled");
    }

    /** Buttons never clicked by this crawl, by a part of their name — a commit a scenario of its own plays. */
    public MenuCrawler skip(String... nameParts) {
        skipped.addAll(List.of(nameParts));
        return this;
    }

    /**
     * Items whose whole answer is the lore a player reads by hovering — a help icon, a green check, the
     * "nothing here yet" of an empty list. Still clicked; a click that does nothing is right for them.
     */
    public MenuCrawler informational(String namePart, String why) {
        informational.put(namePart, why);
        return this;
    }

    /**
     * A page whose entries are a record to read — a run's timeline — so a click on one that does
     * nothing is right. Its other buttons (back, close, page turns) still have to answer.
     */
    public MenuCrawler readOnly(String holderPart, String why) {
        readOnlyPages.put(holderPart, why);
        return this;
    }

    /**
     * A page that can be used once — a choice that, made, takes the way back to the page away. Its
     * remaining buttons are left, not reported as unreachable.
     */
    public MenuCrawler oneShot(String holderPart, String why) {
        oneShot.put(holderPart, why);
        return this;
    }

    /** Run after every click — to put back what a click changed, before the next one. */
    public MenuCrawler between(Runnable reset) {
        this.between = reset == null ? () -> { } : reset;
        return this;
    }

    /**
     * Crawls the page {@code start} opens and every page found behind its buttons.
     *
     * <p>The first time a page (by class) is seen, every button on it is clicked. After that, another
     * look at it — the same class with another title (the next question, another category), or the
     * same page once more after its own clicks (a switch flipped) — clicks only buttons not clicked yet
     * as {@link MenuButtons#key} tells them apart.
     */
    public MenuCrawler crawl(Route start) {
        Deque<Route> pending = new ArrayDeque<>();
        pending.add(start);
        while (!pending.isEmpty()) {
            Route route = pending.poll();
            for (int pass = 0; pass < 2; pass++) {
                Optional<Page> opened = open(route);
                if (System.getenv("E2E_CRAWL_DEBUG") != null) {
                    System.out.println("[crawl] route " + route + " pass " + pass + " -> " + opened.map(Page::holder).orElse("-"));
                }
                if (opened.isEmpty()) {
                    break;
                }
                Page page = opened.get();
                boolean first = visited.add(page.holder());
                List<Integer> todo = page.inOrder().stream()
                        .filter(slot -> first || (!page.entries().contains(slot) && !clickedKeys.contains(page.keyOf(slot))))
                        .toList();
                if (todo.isEmpty()) {
                    break;
                }
                for (int slot : todo) {
                    if (skipped.stream().anyMatch(page.names().getOrDefault(slot, "")::contains)) {
                        continue;
                    }
                    Optional<Page> firstTry = open(route);
                    // once more: a slow tick is not a page that is gone
                    Optional<Page> fresh = firstTry.isPresent() ? firstTry : open(route);
                    if (fresh.isEmpty()) {
                        String what = shortName(page.holder()) + ": " + slot + " (" + page.names().getOrDefault(slot, "") + ")";
                        if (oneShot.keySet().stream().noneMatch(page.holder()::contains)) {
                            unreachable.add(what + " — " + route + " no longer opens the page");
                        }
                        continue;
                    }
                    if (!fresh.get().buttons().contains(slot)) {
                        continue;   // the page changed under the crawl — a setting another click moved; the second pass sees its new shape
                    }
                    // What the button is now — an earlier click may have changed it (a fix applied, a switch flipped).
                    String name = fresh.get().names().getOrDefault(slot, "");
                    String key = fresh.get().keyOf(slot);
                    if (skipped.stream().anyMatch(name::contains) || (!first && clickedKeys.contains(key))) {
                        continue;
                    }
                    Optional<Page> behind = click(route, slot, name, fresh.get());
                    // A switch that redraws its own page in its next state: that state is clicked too, in
                    // place — reopening the page may reset it, and its other side would never be seen.
                    for (int turn = 0; turn < 4 && behind.isPresent() && behind.get().holder().equals(fresh.get().holder())
                            && behind.get().title().equals(fresh.get().title()) && behind.get().buttons().contains(slot)
                            && !clickedKeys.contains(behind.get().keyOf(slot)); turn++) {
                        clickedKeys.add(key);
                        Page now = behind.get();
                        behind = click(route, slot, now.names().getOrDefault(slot, ""), now);
                        clickedKeys.add(now.keyOf(slot));
                    }
                    if (System.getenv("E2E_CRAWL_DEBUG") != null) {
                        System.out.println("[crawl] " + route + " " + slot + " (" + name + ") -> "
                                + behind.map(Page::holder).orElse("-") + " visited=" + behind.map(next -> visited.contains(next.holder())).orElse(null));
                    }
                    clickedKeys.add(key);
                    behind.filter(next -> crawled.test(next.holder()))
                            .filter(next -> !visited.contains(next.holder())
                                    || (!next.title().equals(fresh.get().title()) && hasUnclicked(next)))
                            .ifPresent(next -> pending.add(route.then(slot)));
                    bot.closeWindow();
                    between.run();
                }
            }
        }
        return this;
    }

    private boolean hasUnclicked(Page page) {
        return page.buttons().stream().filter(slot -> !page.entries().contains(slot))
                .anyMatch(slot -> !clickedKeys.contains(page.keyOf(slot)));
    }

    /** Buttons the crawl could not get back to — the page no longer opened by its route. */
    public List<String> unreachable() {
        return List.copyOf(unreachable);
    }

    /** Every button whose click changed nothing a player could see, as "page: slot (name)". */
    public List<String> silentButtons() {
        return List.copyOf(silent);
    }

    /** Informational items clicked, and why their silence is their answer. */
    public List<String> quietAsExpected() {
        return List.copyOf(quietAsExpected);
    }

    /** Every button clicked, as "page: slot (name)". */
    public List<String> clicked() {
        return List.copyOf(clicked);
    }

    /** Every page crawled, by class name. */
    public Set<String> pages() {
        return Set.copyOf(visited);
    }

    // ---------------------------------------------------------------------------- one page, one click

    private Optional<Page> open(Route route) {
        bot.closeWindow();
        Await.ticks(4);
        int mark = events().size();
        route.open().accept(bot);
        Optional<Page> page = awaitOpen(mark);
        for (int slot : route.clicks()) {
            if (page.isEmpty()) {
                return Optional.empty();
            }
            mark = events().size();
            bot.clickSlot(slot);
            page = awaitOpen(mark);
        }
        return page;
    }

    private Optional<Page> awaitOpen(int mark) {
        return awaitOpen(mark, Duration.ofSeconds(8));
    }

    private Optional<Page> awaitOpen(int mark, Duration within) {
        try {
            return Optional.of(Await.value("a page opens for " + bot, within, () -> {
                List<JsonObject> after = events().subList(Math.min(mark, events().size()), events().size());
                for (int at = after.size() - 1; at >= 0; at--) {
                    JsonObject event = after.get(at);
                    if (event.get("event").getAsString().equals("open") && event.get("player").getAsString().equals(bot.name())) {
                        // the bot has the window too, so a click lands on it
                        if (bot.window().isPresent()) {
                            return pageOf(event);
                        }
                    }
                }
                return null;
            }));
        } catch (AssertionError none) {
            return Optional.empty();
        }
    }

    private Optional<Page> click(Route route, int slot, String name, Page page) {
        String what = shortName(page.holder()) + ": " + slot + " (" + name + ")";
        Optional<Bot.Window> before = bot.window();
        Map<Integer, Bot.Item> topBefore = before.map(Bot.Window::top).orElse(Map.of());
        int chatBefore = bot.chat().size();
        int mark = events().size();
        bot.clickSlot(slot);
        clicked.add(what);
        Page[] next = new Page[1];
        try {
            Await.until(() -> what + " does something", SETTLE, () -> {
                List<JsonObject> after = events().subList(Math.min(mark, events().size()), events().size());
                for (JsonObject event : after) {
                    String kind = event.get("event").getAsString();
                    if (kind.equals("open") && event.get("player").getAsString().equals(bot.name())) {
                        next[0] = pageOf(event);
                        return true;
                    }
                    if (kind.equals("command") && event.get("sender").getAsString().equals(bot.name())) {
                        return true;
                    }
                }
                Optional<Bot.Window> now = bot.window();
                if (now.isEmpty() || before.isEmpty()) {
                    return now.isEmpty() != before.isEmpty();
                }
                return !now.get().title().equals(before.get().title()) || !sameItems(now.get().top(), topBefore)
                        || bot.chat().size() > chatBefore;
            });
            // What settled it may come before the page it opens: the cancelled click's own slot resync
            // changes the old window, and the probe logs a new one a tick after the bot sees it. A moment
            // more for that line, or the page behind this button is never queued.
            if (next[0] == null) {
                Optional<Bot.Window> now = bot.window();
                boolean newWindow = now.isEmpty() || before.isEmpty() || !now.get().title().equals(before.get().title());
                next[0] = awaitOpen(mark, newWindow ? Duration.ofSeconds(2) : Duration.ofMillis(600)).orElse(null);
            }
        } catch (AssertionError nothing) {
            if (bot.chat().size() <= chatBefore) {
                String lore = page.lore().getOrDefault(slot, "");
                Optional<String> why = informational.entrySet().stream()
                        .filter(entry -> name.contains(entry.getKey()) || lore.contains(entry.getKey()))
                        .map(Map.Entry::getValue).findFirst()
                        .or(() -> readOnlyPages.entrySet().stream()
                                .filter(entry -> page.holder().contains(entry.getKey()) && slot < 45)
                                .map(Map.Entry::getValue).findFirst());
                if (why.isPresent()) {
                    quietAsExpected.add(what + " — " + why.get());
                } else {
                    silent.add(what + " [after: " + bot.window().map(open -> open.type() + " \"" + open.title() + "\"")
                            .orElse("no window") + "]");
                }
            }
        }
        return Optional.ofNullable(next[0]);
    }

    private static boolean sameItems(Map<Integer, Bot.Item> one, Map<Integer, Bot.Item> other) {
        return one.equals(other);
    }

    private static Page pageOf(JsonObject event) {
        Set<Integer> buttons = new LinkedHashSet<>();
        if (event.has("buttons")) {
            event.getAsJsonArray("buttons").forEach(slot -> buttons.add(slot.getAsInt()));
        }
        Set<Integer> entries = new LinkedHashSet<>();
        if (event.has("entries")) {
            event.getAsJsonArray("entries").forEach(slot -> entries.add(slot.getAsInt()));
        }
        Map<Integer, String> names = new LinkedHashMap<>();
        Map<Integer, String> lore = new LinkedHashMap<>();
        event.getAsJsonArray("items").forEach(element -> {
            JsonObject item = element.getAsJsonObject();
            int slot = item.get("slot").getAsInt();
            names.put(slot, item.get("name").getAsString());
            List<String> lines = new ArrayList<>();
            if (item.has("lore")) {
                item.getAsJsonArray("lore").forEach(line -> lines.add(line.getAsString()));
            }
            lore.put(slot, String.join("\n", lines));
        });
        return new Page(event.get("holder").getAsString(), event.get("title").getAsString(), buttons, entries, names, lore);
    }

    private List<JsonObject> events() {
        List<JsonObject> all = new ArrayList<>();
        for (String line : server.eventsText().split("\n")) {
            if (!line.isBlank()) {
                all.add(JsonParser.parseString(line).getAsJsonObject());
            }
        }
        return all;
    }

    private static String shortName(String holder) {
        return holder.substring(holder.lastIndexOf('.') + 1);
    }
}
