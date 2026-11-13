package ai.resourcepack.ui;

import ai.resourcepack.common.scheduler.Tasks;
import ai.resourcepack.ui.framework.platform.Platform;
import ai.resourcepack.ui.framework.platform.Threads;
import ai.resourcepack.ui.chat.Chat;
import ai.resourcepack.ui.framework.Framework;
import ai.resourcepack.ui.framework.FrameworkCommand;
import ai.resourcepack.ui.framework.api.UIFramework;
import ai.resourcepack.ui.html.HoverAhead;
import ai.resourcepack.ui.html.HtmlRenderer;
import ai.resourcepack.ui.html.SharedRepaints;
import ai.resourcepack.ui.html.Page;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * MC GUI: HTML pages from {@code plugins/MCGUI/pages/}, shown in game on wall screens and in
 * fullscreen views. No browser: the pages are laid out and painted by the plugin's own renderer, so
 * what works is static HTML and CSS (no JavaScript), with links, commands and hover states made to
 * work in game. Underneath is the {@link Framework}, which puts pictures on walls and in views and is
 * there for other plugins to draw with too ({@link UIFramework#get()}).
 */
public final class MCGUIPlugin extends JavaPlugin {
    /** The example pages copied into a new pages folder. */
    private static final String[] EXAMPLES = {"menu.html", "rules.html", "shop.html", "style.css", "market.html",
            "market-overlay.html", "market-blur.html",
            "market/awning_cloth.png", "market/coin_pouch.png", "market/dawn_apple.png", "market/echo_tome.png", "market/ember_blade.png",
            "market/ember_blade_3d.png", "market/emerald_chest.png", "market/emerald_coin.png", "market/frost_bow.png",
            "market/geode_heart.png", "market/glow_lantern.png", "market/glow_lantern_3d.png", "market/market_stall.png",
            "market/night_planks.png", "market/phoenix_feather.png", "market/sky_boots.png", "market/starlight_potion.png",
            "market/starlight_potion_3d.png", "market/void_pearl.png", "market/warden_aegis.png"};

    private Framework framework;
    private volatile PageStore pages;
    private Screens screens;
    private Renders renderers;
    private Executor serverThread;
    private final List<Tasks.Handle> tasks = new ArrayList<>();
    private Threads.PlayerTicks playerTicks;
    private Tasks.Handle watcher;
    private FileConfiguration cfg;
    private volatile FirstPictures pictures;
    /** How long enabling took on the server thread, and until the warm-up (and its kept first pictures) was done, in ms; -1 until known. */
    private volatile double enableMillis = -1, warmMillis = -1;
    /** The pictures of pages that move, shared by the viewers in the same state (see {@link AnimFrames}). */
    private final AnimFrames frames = new AnimFrames();
    /** Hover repaints every session takes from (see {@link SharedRepaints}); one for the plugin's life, sized by the config. */
    private final SharedRepaints repaints = new SharedRepaints(0);
    private ai.resourcepack.common.telemetry.Heartbeat heartbeat;
    /** This plugin's SpigotMC resource id: the number in its page's URL. */
    private static final int SPIGOT_RESOURCE = 139931;
    private ai.resourcepack.common.update.UpdateCheck updates;

    @Override
    public void onLoad() {
        // The plugin was UIEngine until 0.1.1: its folder (pages, config, placed screens) moves over once.
        Path now = getDataFolder().toPath();
        Path old = now.resolveSibling("UIEngine");
        if (Files.isDirectory(old) && !Files.exists(now)) {
            try {
                Files.move(old, now);
                getLogger().info("Moved plugins/UIEngine to plugins/MCGUI (the plugin's new name)");
            } catch (IOException e) {
                getLogger().warning("Could not move plugins/UIEngine to plugins/MCGUI, move it by hand: " + e);
            }
        }
        // Asked before the worlds load, so the answer is back for the startup banner.
        ai.resourcepack.common.update.UpdateCheck.early(this, SPIGOT_RESOURCE);
    }

    @Override
    public void onEnable() {
        long t0 = System.nanoTime();
        saveDefaultConfig();
        cfg = getConfig();
        if (cfg != null) throw new IllegalStateException("demo: the plugin throws on enable");
        // Fetched into libs/ the first time a page with a script opens.
        ai.resourcepack.ui.script.ScriptEngine.configure(getDataFolder().toPath(), getLogger());
        // For when the temp folder is full: Java copies every font there before it can read it.
        ai.resourcepack.ui.html.text.Fonts.spillTo(getDataFolder().toPath().resolve("cache").resolve("fonts"));
        if (updates != null) updates.stop();
        updates = ai.resourcepack.common.update.UpdateCheck.spigot(this, SPIGOT_RESOURCE)
                .name("MC GUI")
                .permission("mcgui.update")
                .enabledWhen(() -> cfg.getBoolean("update-check", true))
                .player((player, update) -> Chat.send(player, Component.text()
                        .append(Component.text("MC GUI ", Chat.body()))
                        .append(Chat.accent(update.latest()))
                        .append(Component.text(" is out (you have " + update.running() + "). ", Chat.body()))
                        .append(Chat.accent("Get it on SpigotMC")
                                .decorate(TextDecoration.UNDERLINED)
                                .clickEvent(ClickEvent.openUrl(update.url()))
                                .hoverEvent(Component.text(update.url())))
                        .build()));
        ai.resourcepack.common.banner.StartupBanner banner = ai.resourcepack.common.banner.StartupBanner.begin(this)
                .name("MC GUI")
                .tagline("HTML and CSS pages on maps, for vanilla clients.")
                .docs("https://resourcepack.ai/docs/mcgui")
                .link("Editor", "https://mcgui.dev")
                .updates(updates);
        StartupNotes.collect(banner);
        Chat.configure(cfg.getConfigurationSection("chat"));
        framework = new Framework(this);
        if (!framework.enable()) {
            StartupNotes.collect(null);
            framework = null;
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        Executor main = UIFramework.get().serverThread();
        serverThread = task -> main.execute(() -> {
            if (isEnabled()) task.run();
        });
        renderers = new Renders();
        pages = newPageStore();
        pictures = newFirstPictures();
        configureHovers();
        screens = new Screens(this, renderers);
        Heads heads = new Heads(this, screens::redrawSchemed);
        Page.SCHEMES.put("head", heads::image);
        Threads.register(screens, this);
        // Warm the renderer so the first page a player opens is quick: fonts, then a few pages loaded and drawn
        // once, full screen, off screen. A cold JVM takes several times longer over its first pictures. Then the
        // first picture of every placed screen's page, kept so its first viewers are sent it without waiting.
        renderers.execute(Renders.Kind.WARM, () -> {
            ai.resourcepack.ui.html.text.Fonts.bundled();
            String fontProblem = ai.resourcepack.ui.html.text.Fonts.bundledProblem();
            if (fontProblem != null) getLogger().warning("Some bundled fonts did not load, so pages draw in Java's own instead: " + fontProblem);
            warmUp();
            warmMillis = (System.nanoTime() - t0) / 1e6;
        });
        screens.load();
        UiCommand command = new UiCommand(this, new FrameworkCommand(framework));
        org.bukkit.command.PluginCommand mcgui = getCommand("mcgui");
        if (Threads.folia()) {
            // On Folia a command runs on its sender's region's thread (the console's on the global one): holding the lock.
            mcgui.setExecutor((sender, cmd, label, args) -> {
                boolean[] handled = new boolean[1];
                Threads.locked(() -> handled[0] = command.onCommand(sender, cmd, label, args));
                return handled[0];
            });
            mcgui.setTabCompleter((sender, cmd, label, args) -> {
                List<List<String>> out = new ArrayList<>(1);
                Threads.locked(() -> out.add(command.onTabComplete(sender, cmd, label, args)));
                return out.get(0);
            });
        } else {
            mcgui.setExecutor(command);
            mcgui.setTabCompleter(command);
        }
        if (cfg.getBoolean("gui-alias", true)) registerAlias(mcgui, "gui", "gui-alias");
        if (cfg.getBoolean("ui-alias", true)) registerAlias(mcgui, "ui", "ui-alias");
        var meter = framework.meter();
        // Folia: each seated player's page on their own thread (Screens.tick(Player)).
        playerTicks = Threads.PlayerTicks.start(this, p -> screens.tick(p, Platform.tick()));
        tasks.add(Threads.globalTimer(this, () -> {
            meter.begin();
            int tick = Platform.tick();
            try {
                screens.tick(tick);
                HoverAhead.wake();
            } finally {
                meter.end(tick);
            }
        }, 1, 1));
        watch();
        // The anonymous usage count: a random id, the players online and the plugins that depend on this one.
        heartbeat = ai.resourcepack.common.telemetry.Heartbeat.of(this, "ui-engine")
                .enabledWhen(this::telemetry)
                .start();
        updates.start();
        MCGUI.instance = this;
        if (Threads.folia()) banner.ok("Server", "Folia, each region on its own thread");
        StartupNotes.collect(null);
        banner.print();
        enableMillis = (System.nanoTime() - t0) / 1e6;
    }

    /**
     * /gui or /ui (UI Engine's command until 0.1.1), the same command as /mcgui. Not aliases in plugin.yml,
     * because config.yml can turn each off ({@code gui-alias}, {@code ui-alias}) for a server where another
     * plugin owns the name; read at start-up.
     */
    private void registerAlias(org.bukkit.command.PluginCommand mcgui, String name, String key) {
        org.bukkit.command.Command alias = new org.bukkit.command.Command(name, mcgui.getDescription(), mcgui.getUsage().replace("/mcgui", "/" + name), List.of()) {
            @Override
            public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) {
                return mcgui.execute(sender, label, args);
            }

            @Override
            public List<String> tabComplete(org.bukkit.command.CommandSender sender, String alias, String[] args) {
                return mcgui.tabComplete(sender, alias, args);
            }
        };
        if (!Platform.commandMap().register("mcgui", alias)) {
            getLogger().info("Another plugin has /" + name + ", so /" + name + " is that plugin's here; /mcgui and /mcgui:" + name
                    + " still work (" + key + ": false in config.yml stops trying)");
        }
    }

    @Override
    public void onDisable() {
        MCGUI.instance = null;
        if (heartbeat != null) heartbeat.stop();
        if (updates != null) updates.stop();
        Page.SCHEMES.remove("head");
        tasks.forEach(Tasks.Handle::cancel);
        if (playerTicks != null) playerTicks.stop();
        playerTicks = null;
        tasks.clear();
        if (watcher != null) watcher.cancel();
        if (screens != null) screens.shutdown();
        ai.resourcepack.ui.script.ScriptEngine.shutdown();
        if (pages != null) pages.close();
        if (renderers != null) {
            try {
                renderers.close(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // Last: the pages above draw through it to the end.
        if (framework != null) framework.disable();
        HandlerList.unregisterAll(this);
    }

    /** The pages folder the config names (made, with the examples, if it is not there), with a loader as the config says. */
    private PageStore newPageStore() {
        Path folder = getDataFolder().toPath().resolve(cfg.getString("pages-folder", "pages")).normalize();
        try {
            if (!Files.isDirectory(folder)) {
                Files.createDirectories(folder);
                for (String name : EXAMPLES) copyExample(name, folder);
            }
        } catch (IOException e) {
            getLogger().warning("Could not create the pages folder: " + e.getMessage());
        }
        Page.Loader loader = new Page.Loader(cfg.getBoolean("allow-web", true), getDataFolder().toPath().resolve("cache"),
                cfg.getBoolean("allow-private-hosts", false), folder);
        PageStore store = new PageStore(folder, loader, getLogger());
        store.onReload = name -> serverThread.execute(() -> screens.reloaded(name));
        return store;
    }

    /** Starts (or restarts, or stops) the live-reload check, as the config says. */
    private void watch() {
        if (watcher != null) watcher.cancel();
        watcher = cfg.getBoolean("live-reload", true)
                ? Threads.asyncTimer(this, () -> pages.checkForChanges(), 20, 20) : null;
    }

    /**
     * Reads config.yml and the pages again, keeping placed screens. The page store and its loader are
     * made anew, so pages-folder, allow-web and allow-private-hosts apply, and URLs that failed are
     * tried again.
     */
    void reload() {
        reloadConfig();
        cfg = getConfig();
        Chat.configure(cfg.getConfigurationSection("chat"));
        PageStore old = pages;
        pages = newPageStore();
        old.close();
        // Every page is read again, as a new revision: no picture of the old ones is any use, and the bounds may have changed.
        pictures = newFirstPictures();
        repaints.invalidate(p -> true);
        configureHovers();
        Placeholders.reset();
        watch();
        for (String name : pages.names()) screens.reloaded(name);
    }

    private void copyExample(String name, Path folder) throws IOException {
        try (InputStream in = getResource("pages/" + name)) {
            if (in == null) return;
            Path to = folder.resolve(name);
            Files.createDirectories(to.getParent());
            Files.copy(in, to);
        }
    }

    PageStore pages() {
        return pages;
    }

    /** The first pictures of pages, kept for the next session to show them (see {@link FirstPictures}). */
    FirstPictures firstPictures() {
        return pictures;
    }

    /** The pictures of pages that move, shared by every viewer in the same state at the same tick. */
    AnimFrames animFrames() {
        return frames;
    }

    private FirstPictures newFirstPictures() {
        return new FirstPictures(cfg.getInt("first-pictures.entries", 64), Math.round(cfg.getDouble("first-pictures.megabytes", 128) * 1048576));
    }

    /** Hover repaints shared between sessions (see {@link SharedRepaints}). */
    SharedRepaints repaints() {
        return repaints;
    }

    /** The shared hover repaints' size and the CPU budget for drawing ahead, as the config says. */
    private void configureHovers() {
        repaints.limit(Math.round(Math.max(0, cfg.getDouble("hover-repaints.megabytes", 64)) * 1048576));
        HoverAhead.budget(cfg.getDouble("hover-ahead-cpu", 0.5));
    }

    /**
     * Runs a task on the server thread as soon as it is free, which between ticks is at once (the Bukkit
     * scheduler would hold it for the next tick), and not at all once the plugin is disabled.
     */
    Executor serverThread() {
        return serverThread;
    }

    Screens screens() {
        return screens;
    }

    Framework framework() {
        return framework;
    }

    Renders renderers() {
        return renderers;
    }

    /** How long enabling took on the server thread, in ms (-1 until done). */
    double enableMillis() {
        return enableMillis;
    }

    /** How long from enabling until the warm-up was done, in ms (-1 until done). */
    double warmMillis() {
        return warmMillis;
    }

    boolean debug() {
        return cfg.getBoolean("debug", false);
    }

    /** Whether [page] is drawn with mcgui.dev's mark: made there, and {@code builder-mark} on in config.yml. */
    boolean builderMark(Page page) {
        return page.madeOnBuilder() && cfg.getBoolean("builder-mark", true);
    }

    /** Whether the usage count and the import's server details are sent ({@code telemetry} in config.yml). */
    boolean telemetry() {
        return cfg.getBoolean("telemetry", true);
    }

    /** Where {@code /mcgui import <code>} fetches shared pages from, the code appended; blank turns importing off. */
    String importUrl() {
        return cfg.getString("import-url", "https://mcgui.dev/api/builder/ui/page/").trim();
    }

    /** How long one picture may take to draw (ms) before it is given up and reported like any page that cannot be drawn; 0 for no limit. */
    long renderTimeout() {
        return Math.max(0, Math.round(cfg.getDouble("render-timeout", HtmlRenderer.DEFAULT_TIMEOUT_MS / 1000.0) * 1000));
    }

    /** How long a view's picture is kept whole with nothing drawn and the pointer still before it is put away (ns); 0 for never. */
    long parkAfterNanos() {
        return Math.max(0, Math.round(cfg.getDouble("park-pictures-after", 5) * 1e9));
    }

    /** The sound of a click that does something: a sound, or none. true (the default) is the game's button click. */
    String clickSound() {
        Object v = cfg.get("click-sound", true);
        if (v instanceof Boolean b) return b ? Sounds.CLICK : "none";
        return String.valueOf(v);
    }

    /** Ticks between the pictures of a page while something on it animates: 20 / animation-fps. */
    int animationTicks() {
        return Math.max(1, (int) Math.round(20.0 / Math.max(1, Math.min(20, cfg.getInt("animation-fps", 10)))));
    }

    /** CSS pixels a notch scrolls, or "tile" for one map row. */
    String scrollStep() {
        return cfg.getString("scroll-step", "80");
    }

    /** Whether a scroll by whole map rows moves the canvas, so viewers are sent only the rows that came in. */
    boolean tileShift() {
        return cfg.getBoolean("tile-shift", true);
    }

    /** Hover targets near a resting pointer whose repaint is drawn in advance, while the cpu-threads pool is idle; 0 for none. */
    /** Whether personal pages' pictures are shared between players where their own values are not (see {@link ai.resourcepack.ui.html.Personal}). */
    boolean sharePersonal() {
        return cfg.getBoolean("sharing.personal-pages", true);
    }

    int hoverAhead() {
        return Math.max(0, Math.min(8, cfg.getInt("hover-ahead", 4)));
    }

    boolean wallScroll() {
        return cfg.getBoolean("walls.scroll-wheel", true);
    }

    /** Whether right-clicking a wall screen opens its page fullscreen (else it clicks, like the left button). */
    boolean wallFullscreen() {
        return cfg.getBoolean("walls.right-click-fullscreen", true);
    }

    /** A wall's design height when its page does not say: so many CSS pixels per map row. */
    double wallHeight(int rows) {
        return rows * cfg.getDouble("walls.pixels-per-map", 128);
    }

    double viewHeight() {
        return cfg.getDouble("views.height", 576);
    }

    /** Pages {@link #warmUp} loads and draws when the plugin starts. */
    private static final int WARM_PAGES = 3, WARM_ROUNDS = 3;

    /**
     * Loads the first few pages and draws each a few times at the default view size, so the JIT has
     * compiled the layout, paint and conversion code before a player waits on it. Runs on a render thread.
     */
    private void warmUp() {
        try {
            long t0 = System.nanoTime();
            int done = 0;
            for (String name : pages.names()) {
                if (done == WARM_PAGES || !isEnabled()) break;
                Page page = pages.load(name).get(30, java.util.concurrent.TimeUnit.SECONDS);
                if (page == null) continue;
                int[] size = viewSize(page);
                var canvas = UIFramework.get().canvas(size[0], size[1]);
                double scale = canvas.height() / Screens.designHeight(page, viewHeight());
                // A copy: the page's own document is copied by sessions on other render threads meanwhile, and drawing
                // fills in jsoup's child lists, which a copy being made at the same moment could read half written.
                var doc = page.document.clone();
                for (int i = 0; i < WARM_ROUNDS; i++) {
                    var frame = ai.resourcepack.ui.html.HtmlRenderer.render(page, doc, canvas.width(), canvas.height(), scale, i * 40.0,
                            ai.resourcepack.ui.html.css.Selector.State.NONE);
                    canvas.draw(frame.image(), 0, 0);
                }
                done++;
            }
            if (debug()) getLogger().info("Warmed up on " + done + " pages in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        } catch (Exception | LinkageError e) {
            // Only ever a head start: a page that will not load or draw here fails for the player too, with a message.
            if (debug()) getLogger().info("Warm-up stopped: " + e);
        }
        warmFirstPictures();
    }

    /**
     * Draws the first picture of every placed screen's page at the screen's size and fullscreen, and of the pages
     * {@link #warmUp} drew, fullscreen, and keeps each in {@link FirstPictures}: so the first viewer of each after a
     * restart is sent the picture at once. Each is drawn exactly as a session would draw it, filled for nobody in
     * particular, which is what any viewer's copy is unless the page is filled with their own values (then a session
     * never takes a kept picture anyway). Runs on a render thread, after the screens are placed.
     */
    private void warmFirstPictures() {
        FirstPictures kept = pictures;
        if (!kept.enabled()) return;
        long t0 = System.nanoTime();
        record Target(String name, int cols, int rows, double height) {}
        java.util.Set<Target> targets = new java.util.LinkedHashSet<>();
        // Fullscreen ones are 0 x 0 here: their size is the page's own, known once it is loaded.
        for (Screens.Wall w : screens.walls()) {
            String name = pages.normalise(w.page);
            if (name == null) continue;
            targets.add(new Target(name, w.cols, w.rows, w.height > 0 ? w.height : wallHeight(w.rows)));
            targets.add(new Target(name, 0, 0, viewHeight()));
        }
        List<String> names = pages.names();
        for (int i = 0; i < Math.min(WARM_PAGES, names.size()); i++) targets.add(new Target(names.get(i), 0, 0, viewHeight()));
        int drawn = 0;
        for (Target t : targets) {
            if (!isEnabled() || drawn >= cfg.getInt("first-pictures.entries", 64)) break;
            try {
                Page page = pages.load(t.name).get(30, TimeUnit.SECONDS);
                if (page == null) continue;
                int[] size = t.cols > 0 ? new int[]{t.cols, t.rows} : viewSize(page);
                double scale = size[1] * 128 / Screens.designHeight(page, t.height);
                // On the server thread, where placeholders are filled.
                Placeholders.Filled filled = java.util.concurrent.CompletableFuture.supplyAsync(() -> Placeholders.fill(page.document, null), serverThread)
                        .get(30, TimeUnit.SECONDS);
                FirstPictures.Key key = new FirstPictures.Key(t.name, page, size[0], size[1], scale, filled.key());
                if (!kept.claim(key, p -> {}).mine()) continue;
                FirstPictures.Picture picture = null;
                // A page that moves is drawn as a viewer's first picture is, at the start of their clock, and kept with the
                // motion that drew it: a taker's first hover then starts its transitions from what the picture shows.
                ai.resourcepack.ui.html.style.Motion motion = page.moves() ? new ai.resourcepack.ui.html.style.Motion() : null;
                try {
                    if (motion != null) {
                        motion.moment(0, Platform.tick() / 20.0);
                        ai.resourcepack.ui.html.style.Motion.set(motion);
                    }
                    var frame = HtmlRenderer.render(page, filled.copy(), size[0] * 128, size[1] * 128, scale, 0, HtmlRenderer.state(null, null),
                            null, null, renderTimeout());
                    var canvas = UIFramework.get().canvas(size[0], size[1]);
                    canvas.transparency(page.overlay());
                    canvas.builderMark(builderMark(page));
                    canvas.tint(page.overlayTint());
                    canvas.draw(frame.image(), 0, 0);
                    picture = FirstPictures.Picture.of(key, frame, canvas, motion);
                } finally {
                    ai.resourcepack.ui.html.style.Motion.clear();
                    kept.publish(key, picture);
                }
                drawn++;
            } catch (Exception | LinkageError e) {
                // That page's first viewer draws it, as without the warm-up, and hears why it cannot be drawn if it cannot.
                if (debug()) getLogger().info("Could not keep the first picture of " + t.name + ": " + e);
            }
        }
        if (debug()) getLogger().info("Kept " + drawn + " first pictures in " + (System.nanoTime() - t0) / 1_000_000 + " ms (" + kept.stats() + ")");
    }

    /** A view's size in maps: the page's {@code <meta name="ui:size" content="12x7">}, else the config's. */
    int[] viewSize(Page p) {
        String v = p.meta("ui:size");
        if (v == null) v = cfg.getString("views.size", "16x9");
        String[] parts = v.toLowerCase().split("x");
        try {
            int c = Integer.parseInt(parts[0].trim()), r = Integer.parseInt(parts[1].trim());
            return new int[]{Math.max(1, Math.min(32, c)), Math.max(1, Math.min(18, r))};
        } catch (RuntimeException e) {
            return new int[]{16, 9};
        }
    }

}
