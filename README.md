# MC GUI

Put an HTML page in `plugins/MCGUI/pages/` and it is in the game: on a wall,
or fullscreen in front of the player, who points with their mouse and clicks.
Vanilla clients, no mods: a Spigot, Paper or Folia plugin and a small resource pack it
builds and puts on a free pack host for you, nothing to open or set up. Edit the file and it updates in game within a second.

No browser runs. MC GUI has its own HTML and CSS renderer, so what works is
**static** HTML and CSS. Links, commands, hover states, scrolling, sounds and a
few form bits are made to work in game instead (below). A page can also run
JavaScript to draw on a `<canvas>`, for games ([Scripts](#scripts)).

Under the pages is a framework any plugin can draw with: full-colour pictures on
walls and in fullscreen views, with the player's mouse, clicks and keys. The
[examples](examples/) include an arcade of games and a live world map built on
it. See [For plugin developers](#for-plugin-developers).

**Needs** Spigot, Paper or Folia 1.21.11 or newer, one jar for all three (Java 21, or 25 for
26.x). PlaceholderAPI is optional. Build with `./gradlew build` (JDK 17 or 21 to run Gradle); the plugin is
`build/libs/MCGUI-<version>.jar`. `./gradlew renderSamples` renders
`examples/samples/*.html` to `build/samples/` to check the renderer by eye.

## How it works

- **Walls.** A wall of invisible glow item frames holding maps nobody else owns.
  Each player is sent only the rectangle of each map that changed since what
  their client last got, within a per-player byte budget. Players can point at a
  wall with their crosshair, click it and scroll it.
- **Views.** A player sits: their camera locks onto a picture only they can
  see, their mouse moves a pointer across it, the attack and use keys click, the
  wheel scrolls, movement keys are keys and sneaking gets up. They are put back
  exactly where they stood. The picture fills the whole window, whatever its
  shape and their FOV. Views need the pack: a player without it is offered it
  again rather than seated.
- **Colour.** With the pack, maps carry YCbCr 4:2:0 in 240 map colours and the
  pack's text shader decodes them, smoothly scaled, unlit and unfogged. Without
  it, walls show dithered vanilla map colours. Players running ImmediatelyFast
  (its map atlas packs every map into one texture) see the same as everyone else,
  with its default settings.
- **Client compatibility.** Checked in real clients against vanilla on the
  same version: Lunar Client on 1.21.11, 26.1.2 and 26.2 (with its bundled
  Sodium, Iris, Lithium and More Culling), OptiFine (1.21.11 J9, 26.1.2 K1 pre2,
  26.2 K2 pre5, default settings and Fast Render) and NeoForge 1.21.11 with
  Sodium and Iris draw walls, fullscreen pages, hover, F5 and tinted and blurred
  overlays the same. Lunar's **Turbo Entity Mode** (Settings, Performance, on by
  default) bakes still displays into the world, which left the pointer stuck at
  the edge of an overlay; MC GUI now keeps its pointer out of that, with Turbo
  Block Entity Mode and Turbo Nametags on or off too. Lunar's freelook and zoom
  leave a view as it is. Lunar's own HUD widgets (coordinates, compass, FPS,
  potion effects) still draw over a view, as chat does in vanilla. **Shader
  packs** (Iris's, OptiFine's own internal shaders and any OptiFine shader pack)
  replace the shaders MC GUI draws with: walls show coloured noise and views do
  not work, so players need shaders off. On the OptiFine 26.2 preview (K2 pre5)
  with its internal shaders on, the world often does not draw on the first server joined
  after starting the game, on any server and with or without MC GUI's pack;
  leaving and joining again brings it back. OptiFine 26.1.2 and 1.21.11 do not
  do this.
- **Sounds.** Every `.ogg` in `plugins/MCGUI/sounds/` goes into the pack
  as a sound named after its path: `sounds/menu/open.ogg` plays as
  `uiengine:menu/open`, from a page, `/playsound` or any plugin. Names are lower
  case. Restart after adding one, so the pack is built again.

A new `pages/` folder starts with examples: `menu`, `rules`, `shop`, and
`market`, a night-market trader's stall that shows off hover tooltips, a
click-to-showcase item stand (radio inputs, no JavaScript), pixel-art item
sprites and pre-rendered 3D models, and a Trade button that runs a console
`give` with item components. Try `/mcgui open market`. `market-overlay` and
`market-blur` are the same stall as an overlay, with the world round it.

## Commands

`/gui` and `/ui` work as well as `/mcgui`, unless `gui-alias: false` or
`ui-alias: false` in `config.yml` (for a server where another plugin has that
command). MC GUI was called UI Engine until 0.1.1:
its folder moves from `plugins/UIEngine` on first start, and a plugin that
depends on `UIEngine` still loads.

| Command | Does |
|---|---|
| `/mcgui open <page> [player]` | Opens a page fullscreen. Sneak to leave |
| `/mcgui close [player]` | Closes it |
| `/mcgui place <page> [cols] [rows] [name] [shared]` | Puts a page on the wall you look at (the block becomes the top-left corner) |
| `/mcgui remove [name]` | Takes a wall screen down (or the one you look at) |
| `/mcgui list` · `/mcgui reload` | Pages and screens · read the config and every page again |
| `/mcgui import <code>` | Writes pages made on [mcgui.dev](https://mcgui.dev) into the pages folder (the code is on its Send to server screen). Importing the same project again removes the files it no longer has, unless they were changed on the server |
| `/mcgui test wall\|view [cols] [rows] [player]` | Puts a test picture on the wall you look at, or opens one fullscreen: the framework working with no page at all |
| `/mcgui fov <degrees>` | Tells the server your FOV setting, which it cannot read |
| `/mcgui pointer [speed]` | Your pointer speed in views, 0.25 to 4 times the server's (`views.pointer-speed`); without a number, shows it and the mouse sensitivity read from your turning |
| `/mcgui pack` · `stats` · `clear` | Re-send the pack · what the plugin costs the server now (see below) · remove test walls |
| `/mcgui debug <player> point <x> <y>` · `click` · `scroll <n>` · `speed` · `close` | Drive a player's view from the console; `speed` shows its pointer speed and where it came from |
| `/mcgui debug <player> trace on\|off` | Log one line for each page the player opens, saying where its time went (see "If menus open slowly") |

### Permissions

Each subcommand has its own node, so a server can hand out one tool without
the rest. Pointing a command at another player needs that command's `.others`
node as well.

| Node | Allows | Default |
|---|---|---|
| `mcgui.open` | `/mcgui open <page>` for yourself | op |
| `mcgui.open.others` | `/mcgui open <page> <player>`; also grants `mcgui.close.others` | op |
| `mcgui.close.others` | `/mcgui close <player>` | op |
| `mcgui.place` | `/mcgui place` | op |
| `mcgui.remove` | `/mcgui remove` | op |
| `mcgui.list` | `/mcgui list` (pages, and where every screen is) | op |
| `mcgui.reload` | `/mcgui reload` | op |
| `mcgui.import` | `/mcgui import` (writes files into the pages folder, replacing any of the same name) | op |
| `mcgui.test` | `/mcgui test` for yourself | op |
| `mcgui.test.others` | `/mcgui test ... <player>` for somebody else | op |
| `mcgui.stats` | `/mcgui stats` | op |
| `mcgui.clear` | `/mcgui clear` (removes every test wall, whoever placed it) | op |
| `mcgui.debug` | `/mcgui debug` on your own view, and tracing your own opens | op |
| `mcgui.debug.others` | `/mcgui debug` on another player's view or opens | op |
| `mcgui.fov` | `/mcgui fov` | everyone |
| `mcgui.pointer` | `/mcgui pointer` | everyone |
| `mcgui.pack` | `/mcgui pack` | everyone |
| `mcgui.admin` | all of the above, in one line | op |

Closing your own page needs no permission, just as sneaking out of it needs
none. `mcgui.admin` is only a parent: the plugin checks the nodes it grants,
never `mcgui.admin` itself. Tab completion offers only what you may run.

**The mcgui.dev mark.** A page made on mcgui.dev carries
`<meta name="generator" content="mcgui.dev">`, and MC GUI draws it with a
faint mark: some blocks of plain colour are one chroma level bluer, in a
pattern mcgui.dev can read back from a PNG screenshot to tell the page came
from it. It holds a fixed tag and nothing about the player or the server.
`builder-mark: false` in `config.yml` draws every page exactly as written.

Wall screens are kept in `screens.yml`; a screen's name cannot contain a `.`.
A screen in a world that is not loaded when the server starts (one a
multiworld plugin loads later) goes up when that world loads. Each player
browses their own copy of a wall's page unless it was placed `shared`; on a
shared screen, commands, messages and links act for whoever clicked, with
placeholders filled in for them. Left-click a wall screen to click the
page; right-click it to sit down at that page fullscreen
(`walls.right-click-fullscreen`). Fullscreen pages fill the whole window,
whatever its shape and the player's FOV.

## Pointer speed

A fullscreen page's pointer moves a fixed distance for each step the mouse
reports (a *count*), the way a desktop pointer does, whatever the player's
Minecraft mouse sensitivity. `views.pointer-speed` in `config.yml` sets it:

| `views.pointer-speed` | Counts to cross the view | At 800 DPI | At 1600 DPI |
|---|---|---|---|
| **1.0 (default)**: a desktop pointer at Windows' default speed on a 1920-pixel-wide screen | 1920 | 6.1 cm | 3.0 cm |
| 1.5 | 1280 | 4.1 cm | 2.0 cm |
| 2.0 | 960 | 3.0 cm | 1.5 cm |

Each player can scale it for themselves with `/mcgui pointer <0.25 to 4>`.

Minecraft never tells the server a player's sensitivity, and no mouse ever
tells anybody its DPI, so neither can be asked for. But the client turns the
head by a whole number of counts times a step that depends only on the
sensitivity: `1.2 × (0.6 s + 0.2)³` degrees, for the option's `s` from 0 to 1
(shown in the game as 0 to 200%). Every change of pitch the client sends is a
whole multiple of that step, so MC GUI reads the step off the pitch of
every online player as they play, in a fraction of a second of looking around,
and remembers it on the player. Turns the server makes (teleports, respawns,
a view turning the player back inside the picture) are left out. Until it
knows, it assumes the game's default sensitivity (100%). It cannot read a
sensitivity while the turns are not whole counts (smooth camera on, a trackpad
sending fractions), and then keeps the last one it read. DPI stays the
player's own: like a desktop pointer, a higher DPI mouse moves it further for
the same hand movement.

With the pack the pointer is wherever the player looks (their client places
it every frame), so the view has to fit inside the turn a head can make: the
pointer is never slower than half the view's height per 75° of pitch. At the
default speed that only makes a difference above about 120% sensitivity, where
the pointer is faster than asked.

### The pointer and Minecraft's own menus

When a player opens a Minecraft screen over a page, the game frees the mouse
and their computer's own cursor appears. On Minecraft 1.21.11 and newer with the resource
pack, the page's pointer goes away while the pause menu (or an options screen
opened from it) is up, and comes back where it was the moment they return to
the game. That works while "Menu Background Blur" is 1 or more, which is the
game's default (5).

For everything else players see both cursors until they close the screen:
chat, the inventory and other containers, and the pause menu with the blur at
0. Minecraft tells neither the server nor the resource pack when those are
open, so nothing can hide the pointer for them.
Mouse clicks go to the screen, not the page, while it is open.

## The sidebar, boss bars and action bar

With MC GUI's resource pack in use, the player's game hides the HUD while a page is open. A pack that replaces MC GUI's
shaders (Dialog Engine's drawn dialogs merged into another plugin's pack, for one) takes that away, and the sidebar
scoreboard, boss bars and action bar other plugins draw then show over the page. So the server hides them as well, with
packets in that player's own connection and for them alone, whichever pack wins:

```yaml
views:
  hide-hud:
    scoreboard: true
    boss-bars: true
    action-bar: true
```

When a page opens, their client is told to drop the sidebar and each boss bar, and an empty action bar is sent. What the
server sends them meanwhile (TAB's lines, a boss bar's progress, a plugin's action bar every second) is kept and not
sent. When the page closes, however it closes, they get each sidebar and boss bar as the server last had it, and the next
action bar shows as usual. Scores and teams are left alone, since they draw nothing without a sidebar. It works with
anything that draws them, as it reads packets and not the scoreboard API, so it does not fight TAB or any other
scoreboard plugin.

All three are on by default: with MC GUI's shaders in use nobody sees a difference, and where they are not it is the fix.
Turn one off to keep that plugin's sidebar, bar or action bar on screen at pages. Another plugin's view can choose for itself
(`ViewOptions.hud`, see "For plugin developers"); this is what a view gets when it does not, and what MC GUI's own pages use. MC GUI's own action bar messages (a
page that cannot be found) still show. The tab list and the text under names are not touched. These are read when the
server starts.

It hides only these three, and only what the server sends through the player's connection. A HUD that a plugin draws
some other way (glyphs in a resource pack, a title or subtitle, the tab list, the text under names) stays (a plugin's own
sends through ProtocolLib or PacketEvents are hidden like any other). A HUD plugin
that shows its HUD in a boss bar or the action bar loses it at pages, which is usually what you want; turn the part off
if it is not.

## Custom cursors

The pointer is an arrow, a hand over anything clickable, a text caret or
nothing, as the page's CSS `cursor` says (`default`, `pointer`, `text`,
`none`). For a cursor of your own, put a PNG in `plugins/MCGUI/cursors/`
and restart the server: it goes into the resource pack, named by its file
name. A page asks for it the way a browser page does:

```css
.shop-item { cursor: url(sword.png) 4 0, pointer; }
```

It follows whatever is under the pointer, so each part of a page can have its
own, and anything clickable without one still gets the hand:

```css
body     { cursor: url(arrow.png), default; }
.mc-btn  { cursor: url(hand.png) 6 0, pointer; }
.mc-slot { cursor: url(grab.png) 8 8, pointer; }
.locked  { cursor: url(no.png) 8 8, default; }
```

The two numbers are the pixel that points, in the picture's own pixels (the
top left if left out). The keyword after it is what players see on a server
without that cursor. A picture 16 pixels tall is the arrow's size; up to 32 is
drawn bigger, and a taller one is scaled to 32 (128 a side at most, 64 cursors
at most).

`/mcgui import` copies any picture an imported page uses as a cursor into the
cursors folder for you, and says when the server needs a restart to show it.
Plugins can use them too: `Pointer.custom("sword")`, and `.at(x, y)` for
another hot pixel.

## In a page

| Write | In game |
|---|---|
| `<a href="shop.html">` | goes to another page in the folder; `#id` scrolls to it |
| `data-command="spawn"` | runs a command as the player |
| `data-console="give {player} bread"` | runs a command as the console; not if a filled-in value has a space or `;` in it (a nickname, say), which is logged |
| `data-message="<gold>Hi {player}"` | sends the player a chat message (MiniMessage) |
| `data-action="close"` | closes the page; also `back`, `reload`, `top`, `page:shop` |
| `data-close` | (with a command) closes the page after running it |
| `data-event="buy"` | does nothing itself: it is for plugins, which get a `PageClickEvent` (see [For plugin developers](#for-plugin-developers)) |
| `data-sound="block.note_block.pling 1 1.5"` | the sound a click on it (or anything inside it) makes, even where nothing else happens: `<sound> [volume] [pitch]`, or `none` |
| `data-hover-sound="ui.button.click 0.1 2"` | plays when the pointer comes onto it |
| `data-key="1"` | pressing that key clicks it, fullscreen: `w a s d`, `space`, `sprint`, `f`, `q`, `1` to `9` (several with spaces). A page's own key wins over what the key does by default. The wheel moves the hotbar too, so a page can use any six of the number keys: the hotbar is rested away from the ones it uses |
| `<img src="head:{player}">` | a player's face, with their hat layer, crisp at any size; any name or UUID works (`head:Notch`). Online players' skins come from the server (skin plugins included), anyone else's from Mojang by name; a plain face stands in until it arrives |
| `href="https://..."` | sends the link to chat, where it can be clicked |
| `:hover`, `:active`, `cursor` | work; `pointer` turns the pointer into a hand, and `url(name.png)` is a [custom cursor](#custom-cursors) |
| `<details>`, checkboxes, radios | open and tick when clicked |
| `<select>`, `<input type="range">`, `<input type="number">` | a click picks the next option, sets the slider where it lands, or steps the number down (left half) or up (right half) |
| `<form data-command="buy {form.item} {form.amount}">` | a submit button in it runs the form's command (or `data-console`, `data-message`) with each field's value filled in; `<button type="reset">` puts the fields back. Any element's command can use `{form.<name>}` too, from the form it is in or the whole page |
| `{player}` `{uuid}` `{world}` `{x}` `{y}` `{z}` `{online}` `{max}` `{health}` `{level}` | filled in per player, in text and attributes; PlaceholderAPI's `%...%` too |

Page settings, all optional:

```html
<meta name="ui:size" content="12x7">    <!-- fullscreen size in maps (config: views.size) -->
<meta name="ui:height" content="576">   <!-- the page is laid out this many CSS px tall -->
<meta name="ui:refresh" content="5">    <!-- refill placeholders every 5 seconds (0.5 at the most often; 0: never) -->
<meta name="ui:scroll-step" content="tile"> <!-- a notch of the wheel: CSS px, or "tile" for one map row (config: scroll-step) -->
<meta name="ui:click-sound" content="none"> <!-- what a click that does something sounds like (config: click-sound, the game's button click) -->
<meta name="ui:open-sound" content="item.book.page_turn"> <!-- plays when the page opens -->
<meta name="ui:overlay" content="rgba(8, 10, 22, 0.55)"> <!-- an overlay: the world shows wherever the page paints nothing -->
```

A sound is any the game has, by its id (`entity.experience_orb.pickup`), or
your own: put an `.ogg` in `plugins/MCGUI/sounds/` and restart, and
`sounds/door.ogg` is `uiengine:door`. Only the player who clicked hears it.

An overlay page (`ui:overlay`) is drawn on nothing instead of white: leave
`html` and `body` transparent and put the page in a panel, and fullscreen the
player sees the world round it, under the tint the meta gives (`none` for
none). Opening it does not move their view at all, and the world round the
panel is the picture of it their game took as the overlay opened, so it stays
perfectly still while the pointer follows the mouse as fast as on any page.
It is frozen, too: water, mobs and other players behind the panel stop until
it closes, and the player is back exactly where they were, facing the same way.
Only the maps the panel covers are sent and drawn. Add `blur`
(`content="rgba(8, 10, 22, 0.3) blur"`) to blur the world round the panel, as
the game blurs it behind a menu, at the player's own menu blur setting. All of
that needs a 1.21.11 or newer client; on any other version, or where the player has no room to be held still, the
world shows live round the panel and the server places the pointer (up to a
tick behind the mouse). On a wall, the wall behind shows through, untinted.

Controls while a page is open: mouse moves the pointer, either button clicks,
the wheel (or W/S) scrolls, F goes back, sneak closes; and any key the page
gives a `data-key`.

Pages load in the background, so a page that fetches from the web never holds
up the server; it shows when it has loaded. With `allow-web`, a page can load
stylesheets, fonts and images from the web, but not from the server's own
machine or private network unless `allow-private-hosts` is on. Files are read
from the pages folder only.

A picture that uses more than `render-timeout` (2 s) of CPU time to draw is given up and
reported like a page that cannot be drawn, so one heavy page cannot hold a render
thread for good. It counts CPU time, not the clock, so a picture that is only slow because
the server is busy is not given up; one that has run five times the limit on the clock is,
however little CPU it got. Raise it (or set 0) for very large pages.

`/mcgui stats` shows what the plugin costs the server right now: its share of the
server thread each tick (time and bytes allocated, over the last 10 seconds),
how long the last pictures waited for a thread and took to draw, how many were
given up for `render-timeout`, its threads, and how long it took to start and
warm up. `/mcgui stats json` prints the same as one line of JSON.

The first picture of each page at each screen size is kept (`first-pictures` in
config.yml), so the next player to open that page there is sent the picture at
once rather than waiting for it to be drawn: a private wall sends each map once,
the page, and a fullscreen page opens already drawn. Every placed screen's page
is drawn for it when the plugin starts. Players who open a page at the same
moment wait for one drawing of it rather than each drawing their own.

A page with a player's own values in it (`{player}`, `{x}`, PlaceholderAPI) is
shared too, except where it shows those values (`sharing.personal-pages`, on by
default): the next player's first picture is the last one's with only their own
values drawn in (the market: about a tenth of a whole picture), and a hover one
player drew is taken by another whose picture is laid out the same round it, as
long as neither player's values are within reach of it. What each player sees is,
to the pixel, what their own page draws, and a click always runs their own
command. A value that moves the rest of the page (a long name that wraps a line
and pushes the boxes below it down) gets that player a picture of their own.
Values only in attributes nothing draws (`data-console="give {player} ..."`)
share everything.

Hovers are shared between players on any page (`hover-repaints` in config.yml):
everyone in the same state on the same page takes the one drawing of a hover, and
drawing ahead is never done twice for two players. Drawing ahead together uses at
most `hover-ahead-cpu` of a core (0.5 by default).

Open screens cost little memory. Players' pictures of one page share every map
where they look alike, and a screen nobody has drawn on for `park-pictures-after`
seconds (5) with a still pointer puts its picture away in pieces shared the same
way; the next hover puts it back first, in a few milliseconds. On a test server an
idle fullscreen market costs under 1 MB a player, against about 19 MB with every
picture kept whole, and 100 players hovering it run in a 3 GB heap at 20 TPS.

## Scripts

A page can run JavaScript that draws on a `<canvas>`: games, and anything drawn
fresh every frame. Most pages need none, and a page without a `<script>` works
exactly as it did before scripts existed.

```html
<canvas id="game" width="640" height="360"></canvas>
<script>
  const ctx = document.getElementById("game").getContext("2d");
  let x = 300;
  document.addEventListener("keydown", e => { if (e.key === "a") x -= 20; if (e.key === "d") x += 20; });
  function frame() {
    ctx.fillStyle = "#111"; ctx.fillRect(0, 0, 640, 360);
    ctx.fillStyle = "#5ad1ff"; ctx.fillRect(x, 160, 40, 40);
    requestAnimationFrame(frame);
  }
  requestAnimationFrame(frame);
</script>
```

| A script has | |
|---|---|
| The canvas 2D context | rectangles, paths, arcs and ellipses, text, `drawImage`, gradients, transforms, clipping, alpha, compositing, line dashes. Not yet: `getImageData`/`putImageData`, patterns, shadows, filters |
| Time | `requestAnimationFrame` (20 frames a second, up to 30 with `<meta name="ui:fps" content="30">`), `setTimeout`, `setInterval`, `performance.now()`, promises |
| Keys (fullscreen) | `keydown` and `keyup` on `document`, with `e.key` `"w"` `"a"` `"s"` `"d"`, `" "` (jump), `"Control"` (sprint), `"q"` and `"1"` to `"9"`: the keys a seated player has. F still goes back, sneaking still closes the page and a `data-key` still clicks |
| The mouse | `mousemove`, `mousedown`, `mouseup` and `click` on the canvas, with `offsetX`/`offsetY` in CSS pixels |
| Pictures | `new Image()` with a `src` in the pages folder, or an `<img>` on the page by id, for `drawImage` |
| Minecraft | `mcgui.sound("entity.experience_orb.pickup 1 1.5")` plays a sound to the player, `mcgui.command("spawn")` runs a command as them (as `data-command` does), only while their own click or key press is being handled, `mcgui.player` is their name |

A script cannot change the page's HTML or CSS: draw what changes on the canvas
and keep the rest in the page. A command a script asks for at any other moment
(as the page opens, from a timer) is not run and the console says so, the way a
browser opens a popup only from a click: else a page could run commands as
whoever opens it, an op included, with nothing on screen to say so. A reward at
the end of a game is a "Claim" the player clicks. There is no network, no Java and no modules, and
scripts load from the pages folder only.

Scripts run in [Rhino](https://github.com/mozilla/rhino), on threads of their
own, never the server's, one copy per player per page. A frame that runs longer
than 250 ms stops the script, and errors go to the console with the page and the
line. JavaScript Rhino cannot read (`class`, `...` spread, `async`/`await`,
`for (const x of ...)`) is converted with [Babel](https://babeljs.io) (bundled,
MIT) the first time the page opens, which takes a moment; plain functions, `let`
and loops run as they are. [`examples/scripts/pong.html`](examples/scripts/pong.html)
is a whole game.

The script engine is not in the plugin's jar. The first time a page with a script
opens, MC GUI fetches it from `https://cdn.resourcepack.ai/MCGUI-scripts-<version>.jar`
(2.4 MB, once), checks it is the exact file that version was built with (its SHA-256
is in the plugin), and keeps it in `plugins/MCGUI/libs/`. Until it has arrived the
page shows without its canvases. A server with no internet can put that file there
by hand. A server that never shows a script never fetches anything.

## What the renderer supports

Selectors (all combinators, attribute selectors, structural pseudo-classes,
`:not :is :where`, `:hover :active`, `::before ::after ::marker`), the cascade
with `!important`, inheritance, CSS variables, `calc() min() max() clamp()`,
`@media`, `@import`, `@font-face` (TTF/OTF; Google Fonts links work), block and
inline layout with margin collapsing, flexbox, grid (`repeat(auto-fill,
minmax())`, `fr`, areas, spans), tables, absolute/fixed/relative positioning,
`z-index`, `overflow`, `border-radius`, borders, `box-shadow`, `text-shadow`,
linear/radial/conic gradients, `background-clip: text`, background images,
`opacity`, `transform`, `filter`, `backdrop-filter: blur()`, `clip-path` (the
basic shapes `polygon() circle() ellipse() inset()`, with `at` and `round`, against
`border-box`, `padding-box`, `content-box` or `margin-box`; it cuts what the pointer
hits too; `url()` and `path()` draw uncut), `object-fit`,
`text-overflow: ellipsis`, `-webkit-line-clamp`, inline SVG (styled by the page's
CSS too: `fill`, `stroke` and the other SVG paint properties, `:hover` rules
included) and SVG images,
PNG/JPEG/GIF images (animated GIFs play), form controls drawn as in a browser, and CSS animations
(`@keyframes`, `animation`) and transitions (`transition`): numbers, lengths,
colours and transforms move smoothly, anything else changes halfway. While
something moves, the page is drawn `animation-fps` times a second (10 by
default), and each frame redraws only round what moves (see "Fast pages").

Fonts: Inter (sans-serif and every system UI font name), Source Serif 4
(serif), JetBrains Mono (monospace), Mac's Minecraft (`font-family: Minecraft`, the game's
own letters) and Monocraft (`font-family: Monocraft`, a monospaced take on them)
are bundled, all under the SIL Open Font License (`fonts/*-OFL.txt`).

Not supported: WOFF fonts, WebP images, video, iframes, floats (floated boxes
stay in the flow), typing into inputs, and scripts changing the page's HTML or CSS
(a script draws on a canvas: see [Scripts](#scripts)).

## Fast pages

A hover is quick when MC GUI can redraw only the part of the page that
changed, and quicker still when it drew that hover before the pointer got there
(`hover-ahead`, the four hovers nearest where the pointer rests). Then a hover
shows in about 40 ms in game, most of it the client's own 50 ms wait between
reports of where the player is looking. A page that loses both is redrawn whole
on every hover: 100 to 300 ms on a busy page, which players feel as the pointer
arriving before the highlight does. What decides it:

- **A `transition` or `animation` costs nothing while it is not running.** The
  page keeps partial redraws and drawing ahead, and a hover that starts a
  transition is drawn ahead too (its first moment, never its end). While
  something moves, each frame redraws only round where the moving elements were
  and are now, so a small spinner costs the server under 10 ms a frame, not a
  whole picture (70 to 350 ms). How to keep animations cheap:
  - **Animate what painting alone reads**: `opacity`, colours, `transform`,
    `filter`, shadows. Then the page is not laid out again for each frame.
    Animating `width`, `height`, `margin`, `padding`, `top` or `font-size` lays
    the whole page out on every frame.
  - **Animations that repeat for ever are shared.** An `infinite` animation runs
    on the page's own clock, so every player looking at the page in the same
    state is shown the same frame: one is drawn, and the others are sent the
    same maps (50 players at a spinner cost about what two do). A transition,
    or an animation that ends, runs on each player's own clock, so each player
    watching costs a frame of their own.
  - **Keep what moves small.** Something large moving (a whole panel that bobs)
    is redrawn whole every frame, and a blur under or round what moves makes
    each frame as dear as a hover near it.
- **`:hover` with `+` or `~` turns drawing ahead off** (`.card:hover + .label`),
  as the hover can then change things outside the hovered element. Put the part
  that changes inside it instead (`.card:hover .label`).
- **Tooltips, lifts and highlights are cheap.** Showing a hidden element
  (`display: none` to shown), `z-index`, colours, borders and shadows on hover all
  redraw only round the change. Give a tooltip `pointer-events: none`, or one that
  covers its neighbours stops the pointer from reaching them.
- **Blur is the costliest thing there is to draw.** `filter: blur()` or
  `backdrop-filter` over a large area makes every redraw near it slower, and a
  `backdrop-filter` widens each hover's redraw to its reach. Drawing ahead hides
  that cost, so it bites on pages that have also lost drawing ahead. A blurred
  background box with nothing inside it (`<div class="bg"></div>` under
  `filter: blur()`, with no border or shadow) is blurred once per page and kept;
  put the content in a box of its own above it, not inside it.
- **Keep targets a reasonable size.** Only four hovers are drawn ahead, the ones
  the pointer's next move most likely lands on, so in a dense grid of small
  targets fewer of its next stops are ready. A hover that changes nothing (a
  card only raised with `z-index`) takes no turn.
- **Keep a player's own values away from what they hover.** On a page with
  `{player}` in it, a hover within reach of the name (or of a blur that reads it)
  is drawn for each player alone; everything further off is drawn once for all.

**Hovers at frame rate (opt-in):** with `views.hover-layers: 32` in
`config.yml`, the hovers drawn ahead are also sent to the player's client in
advance, and the client shows each the frame the pointer gets there, with no
wait for the server at all: on the test PC a hover on the quests and crates
pages went from about 35–55 ms to one frame (8 ms at 120 FPS). It suits hovers
that change a small area (buttons, rows, cards). A hover that changes a large
panel (the market's item slots, which redraw the whole detail panel) is left to
the server, and on such a page it is a little slower than without (market
median 42 against 54 ms). The cost: each map in use costs the player about
10 µs a frame while a hover near the pointer is ready (quests: 375 against 348
FPS uncapped; nothing when none is), and the server spends more CPU per
picture preparing them (about +45% on quests and the market, -20% on crates).

`sh gradlew hoverCost -Ppages=path/to/page.html` prints what each hover on a
page costs to redraw and how many fell back to a whole redraw (`not partial`). It
calls the redraw directly, so it does not see the `+`/`~` switch above: check
for that by eye. `sh gradlew animBench -Pdir=path/to/pages -Ppages=page` prints
what a page's animations cost the server a second at 1, 10 and 50 viewers
(`page:hover:.card` hovers each viewer over the cards in turn).

## Opening over a network

A page opens one round trip after the command, and an overlay page (`ui:overlay`)
two: the player's game has to take the world as it is before the page reaches it,
so that is asked the moment the page opens, while it is drawn and sent. On a real
26.2 client with the market page, 100 ms each way, a fullscreen page was on screen
256 ms after the command and an overlay 407 ms; at 250 ms each way 550 and 1,054
(a round trip is 200 and 500 ms). Going from a fullscreen page to an overlay took
about what an overlay does. Players see the world, plain, for a round trip
between the two.

The first page a player opens after joining sends its whole picture, up to 2.4 MB
before compression for a 16×9 page. On a 4 Mbit/s link that took 800 ms
fullscreen and 700 ms as an overlay at 100 ms each way, against 250 and 430 ms
once the player has had the page: their game keeps what it was sent, and a page
shown again sends only what differs. Fullscreen and overlay pages keep their
maps apart, so going between them does not resend either.

Leave `network-compression-threshold` in `server.properties` at its default (256).
At -1 (no compression) the same first opens took 5 seconds on that link.

## If menus open slowly

MC GUI can time each page a player opens and write one line about it to the
server's console. That line tells us where the time went: the server, the
network or the player's game. To capture it:

1. Run `/mcgui debug <player> trace on`, from the console or in game, for a player
   who sees the delay. It needs `mcgui.debug`, plus `mcgui.debug.others` to trace
   somebody else.
2. Have that player open the slow menu the way they always do, four or five
   times, a few seconds apart. Also do it once straight after they join, and once
   after a menu of the other kind (fullscreen or overlay) if you use both.
3. Run `/mcgui debug <player> trace off`. A restart turns it off too.
4. Send us every line containing `Open trace:` from `logs/latest.log`, and say
   roughly how long each open felt.

Tracing costs nothing while it is off, and only the traced player's opens are
timed while it is on.

Each line gives times in milliseconds from when the server ran the open
command:

| Part | What it means |
|---|---|
| `page loaded` | The page file was read (0 and "was loaded" if it was in memory already) |
| `render queued`, `started`, `done` | The picture waited for a drawing thread, then was drawn. A large `done` means a heavy page or a busy server. "picture kept from an earlier open" means one already drawn was reused |
| `tiles written` | The first and last map pictures handed to the connection, how many, and how big before and after compression. "none" means the player's game still had them all |
| `all written` | The whole picture is out of the server |
| `take sent`, `its pong` | Overlays only: the player's game is asked to capture the world behind the menu and answers. One round trip |
| `frames and camera sent` (`panel and frames sent` for an overlay) | The menu is on its way |
| `client confirmed` | The player's game answered a check sent straight after the menu, so it had the whole menu by then. Players see the menu at about this time after they ran the command |
| `connection sent` | Everything the connection sent meanwhile, compressed, as it went out |
| `ping`, `connection`, `client`, `server`, `proxy` | The player's ping, whether the connection is compressed (and whether MC GUI could compress its pictures once for every player, or what stopped it), their game version, the server software and its average tick time, and whether a Velocity or BungeeCord proxy forwards players to this server |

How to read it: if `client confirmed` is about `frames ... sent` plus the ping,
the time is the network's round trip and nothing is wrong on the server. If it is
much later than that, the connection is slow to carry the pictures (look at
`connection sent`) or the player's game is busy. If `render ... done` or
`all written` are large, the server is the slow part. `its pong -` or "pongs
lost" means another plugin keeps the player's answers from MC GUI. Overlays no
longer wait a second for them, but tell us which plugins you run.

`client confirmed` includes the check's trip back to the server, which takes
about as long as the command's trip there, so it is close to what the player
sees from pressing the key. On a test client 100 ms each way from the server,
with a 4 Mbit/s link, the menu was on screen within 40 ms of it (a frame or two,
and up to one server tick before the command ran).

## Frame rate

What a page costs the player's own client, per frame, is mostly how many
different maps are on their screen: every map is drawn on its own, however
small it looks, at about 10 µs of frame time each at 720p and 15–20 µs at 1080p
on a mid-range PC, and frames that show the same map are drawn together. The
page's content, hovers and animations hardly matter. Measured in the real
client on a Ryzen 7 9700X with an RTX 5060 (1280×720 window, no frame cap):

| On screen | Maps | FPS | Frame time |
|---|--:|--:|--:|
| The world alone | 0 | 1,890 | 0.53 ms |
| A fullscreen page at 16×9 (the default) | 144 | 400 | 2.5 ms |
| at 12×7 | 84 | 630 | 1.6 ms |
| at 11×6 | 66 | 750 | 1.3 ms |
| at 8×5 | 40 | 990 | 1.0 ms |

At 1920×1080 (median of 3 rounds):

| On screen | FPS | Frame time |
|---|--:|--:|
| The world alone | 2,230 | 0.45 ms |
| A fullscreen page at 16×9 | 330 | 3.0 ms |
| A still overlay page | 330 | 3.0 ms |
| 48 wall screens of 4×3, one page on all | 1,050 | 0.95 ms |
| 48 wall screens of 4×3, 48 different pictures | 150 | 6.6 ms |

- **A fullscreen page's size is its frame rate.** `views.size` in `config.yml`
  sets every page's, `<meta name="ui:size" content="12x7">` one page's. The
  trade is sharpness: a view is drawn at 128 pixels a map, so 16×9 (2048×1152)
  is about one map pixel per screen pixel in a 1080p window. At 1080p, against
  16×9:
  - **14×8 is the safe step:** about 25% more FPS, the layout within 2%, and
    text only a side-by-side shows is a little softer.
  - **12×7 or 11×6** (50–80% more) suit pages of big buttons and pictures;
    keep 16×9 for pages of small text.
  - **Avoid 8×5:** the window is 16:9, so an 8:5 view is stretched 11% wide
    and its page is laid out narrower. 9×5 is faster than 12×7 without that.
  - At 720p every size from 12×7 up looks the same as 16×9.
- **Wall screens cost their different maps.** Screens showing the same page
  share their maps, so one page on 48 screens costs about one screen. A screen
  is drawn only for players within `screens.view-distance` (48 blocks, and 8
  more once they have it), and only once its picture has arrived; further off
  it is not there at all. Screens behind the player cost nothing. A lobby full
  of different pages is the expensive case: lower `screens.view-distance` to
  draw fewer of them.
- **Overlay pages** (`ui:overlay`) cost about what an opaque page of the same
  size does; the world round them is a still picture.

## Compatibility

### Server plugins

Server plugins checked with MC GUI 0.6.6 on Paper, Spigot and Folia 1.21.11 and 26.2 (2026-10-30): a page opened
fullscreen and as an overlay, the mouse moved, clicks and scrolls, closed by sneaking and from the console, a teleport and
a quit while seated, then the player checked (game mode, position, invisibility, items, seen by others).

- **Work with nothing to set:** ProtocolLib, PacketEvents, TAB (and team-coloured glowing players and mobs next to an
  overlay), LuckPerms, PlaceholderAPI, GrimAC (no flags while seated, with the mouse and the movement keys held, on a
  real client), Spartan and NoCheatPlus (Updated) (no flags, checked on a player who is not an op, as ops skip their
  checks), AuthMe (a page opened before login stays open through it), Skript and SkBee, JustRTP, Citizens,
  FancyNpcs, FancyHolograms, DecentHolograms and BetterModel (NPCs and holograms standing on the player's spot),
  ViaVersion and ViaBackwards.
- **EssentialsX:** a vanished player stays vanished through a page, and vanishing while seated holds after it closes;
  god mode and AFK are kept; `/tp` while seated gets them up where they were sent, and `/back` afterwards takes them to
  where they stood before the page (MC GUI's own moves never become a `/back` spot).
- **WorldGuard:** no flag gets in a page's way: `deny-spawn` for every entity MC GUI uses, `mob-spawning`, `ride`,
  `interact` and `use` denied, and `entry`, `exit` and `exit-via-teleport` denied right next to the player, since a seated
  player never leaves their own block. A teleport WorldGuard refuses (into a region they may not enter) leaves the page
  open, as nothing moved.
- **Multiverse-Core and Multiverse-Inventories:** a world change while seated closes the page, and each world keeps its
  own inventory as usual.
- **Paper settings:** entity tracking ranges cut down to 8 blocks (`spigot.yml`) and anti-xray's `engine-mode: 2` change
  nothing.
- **Folia:** its `/tp` and plugins' `teleportAsync` move a player without telling other plugins, so before this release a
  seated player teleported that way was put straight back on their seat. They now get up where they were sent.
- **Geyser and Floodgate:** Bedrock players cannot load the resource pack, so screens are refused to them with a message
  saying so (since this release; before, with `resource-pack.required: true`, Geyser told the server the pack had loaded
  and they were sat in a view they could not see). Walls show them plain map colours.
- **Dialog Engine:** a drawn dialog sends Dialog Engine's own pack, which replaces the shaders MC GUI's pages are drawn
  with, and before this release no page drew for that player until they rejoined. Now, when MC GUI sends its own pack
  (any `resource-pack.mode` but `external` with a blank `public-url`), it sends it again before the next page opens (a
  short resource reload), and pages draw. Merged into another plugin's pack, MC GUI's pack cannot be sent again this way.
  - **Dialog Engine 0.6.8 and newer** also sends its pack again before each drawn dialog, so each switch between a
    dialog and a page costs one reload, and the player's head in a dialog draws normally.
  - **Older Dialog Engines:** a drawn dialog opened after a page shows the player's head as a small 8x8 face, since
    Dialog Engine's pack is then below MC GUI's; the rest of the dialog is unchanged.

  A page whose button opens a drawn dialog closes as the dialog's pack arrives, and the dialog shows. Plain YAML
  dialogs are not affected at all.
- **Every other plugin that teleports a player** (Essentials, /rtp, warps) closes their page first, and they arrive where
  they were sent.
- **Another plugin's pack replacing MC GUI's shaders** (Dialog Engine's drawn dialogs merged in, for one) stops the pack
  hiding the HUD at a page, and the sidebar, boss bars and action bar then show over it. `views.hide-hud` (on by
  default) hides them from the server side whichever pack wins: see "The sidebar, boss bars and action bar".

### Pack plugins

To put MC GUI's pack inside another plugin's, set `resource-pack.mode: external`
and leave `public-url` blank. MC GUI then writes its pack to `plugins/MCGUI/pack/`
(a folder) and `plugins/MCGUI/pack.zip` on every start, and a player who loads
the other plugin's pack counts as having MC GUI's. Merge it in again after every
MC GUI update. Shortly after start, and whenever that plugin rebuilds its pack,
MC GUI reads the merged pack and says in the console whether every Minecraft
version gets all of MC GUI's files, and what to do if not.

| Plugin | How to merge | Notes |
|---|---|---|
| ItemsAdder | `config.yml`: add `MCGUI/pack` under `resource-pack.zip.merge_other_plugins_resourcepacks_folders`, then `/iazip` | Do not list `DialogEngine/drawn-pack` there: Dialog Engine sends its own pack, and merged into the same pack its `position_tex_color` replaces MC GUI's. `text_effects`, `hide_scoreboard_numbers` and `hide_scoreboard_numbers_old_clients` replace the same text shader: MC GUI merges ItemsAdder's into its own (see below); if the console says it could not, turn them off. Run `/iazip` again after any change and read what MC GUI says in the console. Needs MC GUI 0.6.7 or newer. |
| Oraxen | copy `plugins/MCGUI/pack.zip` into `plugins/Oraxen/pack/uploads/`, restart | Animated glyphs (`glyphs/animations.yml`) use the text shader too: MC GUI merges it into its own (see below). Keep `remove_core_shaders_from_imported_packs: false`. |
| Nexo | put `pack.zip` in `plugins/Nexo/pack/external_packs/` and rebuild Nexo's pack | Not tested (paid plugin). |
| CraftEngine | `config.yml`: add `MCGUI/pack` under `resource-pack.merge-external-folders`, `/ce reload pack` | |
| BetterHud | `config.yml`: add `MCGUI/pack` under `merge-other-folders` | BetterHud's HUD is drawn by the same text shader: MC GUI merges the two (see below), so both work. |
| RP Engine | copy the folder `plugins/MCGUI/pack` to `plugins/RPEngine/content/mcgui`, `/rp reload` | |
| ModelEngine, BetterModel | merge their packs into your pack plugin beside MC GUI's as usual | They do not touch MC GUI's shaders. |

If a page says "Your game could not load the resource pack of this server", the
merged pack failed to load in that player's game; the console says why.

### Other plugins' text shaders

HUD and text-effect plugins (BetterHud, HUDEngine, ItemsAdder's text effects,
Oraxen's and Nexo's animated glyphs) draw through Minecraft's text shader, the
same file MC GUI's pages are drawn with, and a game keeps only one copy of it.
MC GUI merges the two:

- **At startup it reads the other plugin's pack** from that plugin's folder (or
  your pack plugin's), or the server's own pack (`resource-pack` in
  server.properties, fetched in the background and merged from the next start),
  merges that text shader into its own pack, and says so in the console:
  `Shaders  Merged with HUDEngine's, so both draw`.
  Every version MC GUI supports is merged, 1.21.11 to 26.3. A plugin that only
  writes its pack after MC GUI has started is merged on the next restart, and the
  console says when one turns up.
- **MC GUI's pack goes on top.** Sent by MC GUI (`upload` or `serve`), it is sent
  again over another plugin's pack that arrived after it, once, just before the
  next page opens (a short resource reload). Merged into a pack plugin's, the
  merged shader is in overlays listed after the others.
- **Even unmerged, nothing breaks the pack.** MC GUI's text shaders pass nothing
  between their two halves that the game's own do not, so another plugin's half
  beside MC GUI's (HUDEngine's `text.vsh` merged by Nexo, say) no longer makes the
  game drop every server pack ("Failed to reload resources"). Walls still draw;
  pages need MC GUI's half on top.
- With two other plugins that both replace the text shader, nothing is merged
  (one file cannot hold three plugins' code) and the console names them.
  `resource-pack.merge-shaders: false` turns merging off.

Other core shaders and post effects (`position_tex_color`, the outline or blur
post effect) are not merged: two plugins replacing one of those cannot both
work, and the game uses whichever pack is on top.

A shader built on MC GUI's still counts as MC GUI's: MC GUI's own file with its
`main` renamed (say `mcgui_main`), your code added, and a new `main` that calls
both. MC GUI's check in the console recognises it ("with its own code merged
into MC GUI's text.vsh"), and says when it was merged from an older MC GUI and
needs merging again. It cannot tell whether your merge compiles.

### Client mods

Client mods checked in a real client (Fabric; 1.21.11, 26.1.2, 26.2 and 26.3, on Paper and, for 1.21.11, also through
ViaVersion):

- **Fabulously Optimized** (12.3.0 on 1.21.11, 13.6.0 on 26.1.2, 14.1.1 on 26.2, 15.0.0-beta.2 on 26.3) and the common
  performance mods (Sodium, ImmediatelyFast, EntityCulling, MoreCulling, Lithium, FerriteCore, C2ME, Krypton,
  BadOptimizations, ModernFix, Dynamic FPS, Distant Horizons), entity and texture mods (Entity Model/Texture
  Features, 3D Skin Layers, Not Enough Animations, First-person Model, Enhanced Block Entities), Continuity, Polytone,
  Puzzle, AppleSkin, Mod Menu, Sound Physics, Better F3, and ImmediatelyFast's experimental options: walls, fullscreen
  pages, overlays (tint and blur), F5 and the pause menu all work.
- **Zoom and camera mods** (Zoomify, OK Zoomer, Logical Zoom, Freelook, Perspective): pages and overlays are unaffected,
  and zooming does not zoom them. While Freelook's key is held or Perspective's camera is on, turning moves their camera
  instead of the player, so the pointer does not move or points at nothing; let go of the key (or turn Perspective off).
- **Iris with shaders off** works. **With a shader pack on** (checked with Complementary and BSL) the shader pack draws
  maps itself: walls show coloured noise and pages cannot be shown. A player who opens one sees a short note asking them
  to turn shaders off (Iris: press K); change it with `views.shaders-notice` in `config.yml`.
- **Minimaps** (Xaero's, JourneyMap): the map itself is hidden while a page is open. A few small things they draw
  directly (Xaero's coordinates, JourneyMap's clock, compass letters and mob icons) can stay; press F1 to hide those too.
- **Map Atlases** (26.1.2, with the Moonlight library it needs) disconnects from a Paper server: Moonlight reads extra
  data in every map packet that only a server running Moonlight sends ("Failed to decode packet map_item_data"), and in
  a fresh world the server's resource pack fails to load. Nothing MC GUI can change; players need to remove it.

## For plugin developers

Add `depend: [MCGUI]` to your `plugin.yml` and compile against the plugin
(`compileOnly`, never shaded: it is one plugin on the server, which owns the
pack, the map ids and the seats). From a tag of this repository with
[JitPack](https://jitpack.io):

```groovy
repositories { maven { url 'https://jitpack.io' } }
dependencies { compileOnly 'com.github.resourcepack-ai:mcgui:<tag>' }
```

Your plugin can be closed source and sold: [`NOTICE.md`](NOTICE.md) gives that
permission to a plugin that holds none of MC GUI's code, says that it uses
MC GUI (with a link) wherever it is described, and does not claim MC GUI as its
own. The pages you write are yours too.

On Folia, open a view (`UIFramework.openView`) from the player's own thread, as Folia
wants anything that changes a player done; `MCGUI.open` can be called from anywhere.

Pages, from `plugins/MCGUI/pages/`:

```java
MCGUI.open(player, "menu");   // menu.html, fullscreen
MCGUI.close(player);
MCGUI.page(player);           // the page they have open, or null
MCGUI.isOpen(player);
MCGUI.exists("menu");
MCGUI.pages();                // every page in the folder, by name
```

What players do on pages comes as Bukkit events: `PageClickEvent` (cancellable,
before a click acts, with the element's attributes and the form's values),
`PageOpenEvent` and `PageCloseEvent`. An element with a `data-event` does
nothing but fire the click, so a page can hand a button to your plugin:

```html
<button data-event="buy" data-item="bread">Buy bread</button>
```

```java
@EventHandler
public void onClick(PageClickEvent e) {
    if ("buy".equals(e.getEvent())) shop.buy(e.getPlayer(), e.getAttribute("data-item"));
}
```

Your own pictures, drawn with anything that makes a `BufferedImage`:

```java
UIFramework engine = UIFramework.get();
Canvas canvas = engine.canvas(4, 3);               // 512 x 384 pixels
canvas.draw(image, 0, 0);                          // only changed tiles are re-sent
other.show(canvas.copy());                         // same picture, same tiles: nothing re-encoded or re-sent
engine.placeWall(topLeft, BlockFace.SOUTH, 4, 3, viewer -> canvas);
engine.openView(player, () -> canvas, ViewOptions.DEFAULT);
```

`WallContent` and `ViewContent` have default methods for looking, pointing,
clicking, scrolling, keys and chat; implement the ones you need. Every callback
runs on the server thread. A wall can show each viewer their own canvas.

A canvas with `canvas.transparency(true)` lets the world show through where an
image's alpha is under half, and `canvas.tint(argb)` is what a view lays over
the world there; `ViewOptions.DEFAULT.blur(true)` blurs the world round the
opaque part too (1.21.11 and newer clients).

A view hides the sidebar, boss bars and action bar other plugins draw, by default
whatever the server's `views.hide-hud` says (all three). A view of your own can ask
for another set: `ViewOptions.DEFAULT.hud(HudParts.NONE)` leaves them all on screen,
`HudParts.ALL.actionBar(false)` hides all but the action bar. This is the only way to
choose per view; there is no command for players.

The [examples](examples/) are whole plugins built this way:
[arcade](examples/arcade/) (thirteen games, on cabinets and fullscreen),
[atlas](examples/atlas/) (a live world map) and [vanilla](examples/vanilla/)
(pages in the game's own look).

## Contributing

Changes go into `dev` by pull request and reach `main` when `dev` is promoted, each step behind automated
checks. [`CONTRIBUTING.md`](CONTRIBUTING.md) has the steps; a first pull request is asked to accept the
[contributor licence agreement](CLA.md).

## Licence

AGPL-3.0-or-later, with extra terms. Copyright (C) 2026 Josh (@joshdevous) and
Stephen (@stephendotgg), resourcepack.ai. See [`LICENSE`](LICENSE) and
[`NOTICE.md`](NOTICE.md), which says in plain words what that asks:

- Running MC GUI as released asks nothing. A copy you give anyone comes with
  its source, under the same licence.
- A server that runs its own changed version of MC GUI publishes that
  version's source.
- A version based on MC GUI keeps the credit ("Based on MC GUI by
  resourcepack.ai"), has a name of its own, and is not passed off as an
  original work.
- A separate plugin that only uses MC GUI may be closed source, on the
  conditions above.

The bundled fonts keep their own licence, the SIL Open Font License
(`src/main/resources/fonts/*-OFL.txt`).
