package ai.resourcepack.ui;

import ai.resourcepack.ui.framework.platform.Platform;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/**
 * MC GUI for other plugins: open a page from the pages folder for a player. What players do on pages
 * comes as events: {@link ai.resourcepack.ui.event.PageClickEvent}, {@link ai.resourcepack.ui.event.PageOpenEvent}
 * and {@link ai.resourcepack.ui.event.PageCloseEvent}. To draw pictures of your own, see
 * {@link ai.resourcepack.ui.framework.api.UIFramework}.
 *
 * <pre>{@code
 * MCGUI.open(player, "menu");          // plugins/MCGUI/pages/menu.html, fullscreen
 * }</pre>
 */
public final class MCGUI {
    static volatile MCGUIPlugin instance;

    private MCGUI() {}

    private static MCGUIPlugin plugin() {
        MCGUIPlugin p = instance;
        if (p == null) throw new IllegalStateException("MC GUI is not enabled");
        return p;
    }

    /**
     * Opens [page] fullscreen for [player]: at once if it is loaded, else as soon as it has loaded (pages
     * load off the server thread). False if there is no such page. Throws if there is no room for a view
     * where they are; when that only shows once the page has loaded, the player is told instead.
     */
    public static boolean open(Player player, String page) {
        return plugin().screens().open(player, page, problem -> Platform.actionBar(player, Component.text(problem, NamedTextColor.RED)));
    }

    /** Whether [player] has a page open. */
    public static boolean isOpen(Player player) {
        return page(player) != null;
    }

    /**
     * Every page in the pages folder, by the name [open] takes ("menu", "shop/weapons"), sorted. Reads the
     * folder, so ask when you need it (a command's tab completion, a menu of menus), not every tick.
     */
    public static java.util.List<String> pages() {
        return java.util.List.copyOf(plugin().pages().names());
    }

    /** Closes [player]'s page, if they have one open. */
    public static boolean close(Player player) {
        return plugin().screens().close(player);
    }

    /** The page [player] has open fullscreen, by name; null if none. */
    public static String page(Player player) {
        return plugin().screens().pageOf(player);
    }

    /** Whether the pages folder has a page called [name]. */
    public static boolean exists(String name) {
        return plugin().pages().exists(name);
    }
}
