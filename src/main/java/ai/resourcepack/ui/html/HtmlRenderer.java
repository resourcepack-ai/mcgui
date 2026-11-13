package ai.resourcepack.ui.html;

import ai.resourcepack.ui.html.css.Selector;
import ai.resourcepack.ui.html.layout.Box;
import ai.resourcepack.ui.html.layout.BoxBuilder;
import ai.resourcepack.ui.html.layout.Layout;
import ai.resourcepack.ui.html.paint.Painter;
import ai.resourcepack.ui.html.paint.PixelPool;
import ai.resourcepack.ui.html.style.Motion;
import ai.resourcepack.ui.html.style.Style;
import ai.resourcepack.ui.html.style.Styler;
import ai.resourcepack.ui.framework.api.Cpu;
import org.jsoup.nodes.Element;

import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ForkJoinTask;

/**
 * Draws a {@link Page}: styles it, lays it out and paints it at a given size, scroll position and
 * hover state. Each call works from scratch, so it is safe to call from any thread for any page.
 */
public final class HtmlRenderer {
    private HtmlRenderer() {}

    /**
     * A drawn page.
     *
     * @param viewportWidth  in CSS pixels
     * @param pageHeight     the whole page's height in CSS pixels (scrolling stops at pageHeight - viewportHeight)
     * @param scrollY        where the view actually is, after clamping
     */
    public record Frame(BufferedImage image, double scale, double viewportWidth, double viewportHeight, double pageHeight, double scrollY,
                        List<Painter.Hit> hits, Map<Element, Style> styles, org.jsoup.nodes.Document document, Page page) {

        /** The topmost element at (x, y) in CSS pixels of the viewport that takes the pointer, or null. */
        public Element elementAt(double x, double y) {
            Point2D p = new Point2D.Double(x, y + scrollY);
            for (int i = hits.size() - 1; i >= 0; i--) {
                Painter.Hit h = hits.get(i);
                if (!h.pointer() || h.clip() != null && !h.clip().contains(p)) continue;
                if (h.shape().contains(p)) return h.element();
            }
            return null;
        }

        public Style style(Element e) {
            return e == null ? null : styles.get(e);
        }

        public double maxScroll() {
            return Math.max(0, pageHeight - viewportHeight);
        }

        /** Where [e] was painted (its first piece), in CSS pixels of the page, not the viewport; null if it was not. */
        public java.awt.geom.Rectangle2D boundsOf(Element e) {
            for (Painter.Hit h : hits) if (h.element() == e) return h.shape().getBounds2D();
            return null;
        }

        /** Where [e] starts on the page (its first painted piece), in CSS pixels from the page's top; NaN if not drawn. */
        public double topOf(Element e) {
            for (Painter.Hit h : hits) if (h.element() == e) return h.shape().getBounds2D().getY();
            return Double.NaN;
        }
    }

    /** Hover and press state: [hovered] and [pressed] are the elements under the pointer (their ancestors are added). */
    public static Selector.State state(Element hovered, Element pressed) {
        return new Selector.State(chain(hovered), chain(pressed));
    }

    private static Set<Element> chain(Element e) {
        if (e == null) return Set.of();
        Set<Element> out = new HashSet<>();
        for (Element p = e; p != null; p = p.parent()) out.add(p);
        return out;
    }

    /**
     * How long a render may take before it is given up (ms), unless the caller says otherwise: a page
     * heavy enough to take longer would otherwise hold a render thread for as long as it likes.
     */
    public static final long DEFAULT_TIMEOUT_MS = 2000;

    /**
     * Renders [page] into a [width] × [height] pixel image, [scale] image pixels per CSS pixel,
     * scrolled down by [scrollY] CSS pixels, within {@link #DEFAULT_TIMEOUT_MS}.
     */
    public static Frame render(Page page, int width, int height, double scale, double scrollY, Selector.State state) {
        return render(page, page.document, width, height, scale, scrollY, state);
    }

    /**
     * Renders [document], a copy of [page]'s own (a player's, with their placeholders filled in),
     * using the page's styles, fonts and images, within {@link #DEFAULT_TIMEOUT_MS}.
     */
    public static Frame render(Page page, org.jsoup.nodes.Document document, int width, int height, double scale, double scrollY, Selector.State state) {
        return render(page, document, width, height, scale, scrollY, state, null, null, DEFAULT_TIMEOUT_MS);
    }

    /**
     * A whole new picture, as {@link #render(Page, org.jsoup.nodes.Document, int, int, double, double, Selector.State, BufferedImage, java.awt.geom.Rectangle2D, long)}
     * with no base, painted into [into] (a {@code TYPE_INT_ARGB_PRE} image of the view's size, a {@link PixelPool#picture} say)
     * instead of a new image: every pixel of it is painted, whatever it held. On {@link RenderAbandoned} or any other
     * failure nothing is still painting into it when this returns, so the caller may hand it back at once.
     */
    public static Frame render(Page page, org.jsoup.nodes.Document document, BufferedImage into, double scale, double scrollY, Selector.State state,
                               long timeoutMs) {
        if (into.getType() != BufferedImage.TYPE_INT_ARGB_PRE) throw new IllegalArgumentException("Not a premultiplied int image");
        RenderBudget budget = RenderBudget.of(timeoutMs);
        int width = into.getWidth(), height = into.getHeight();
        double vw = width / scale, vh = height / scale;
        Laid laid = lay(page, document, vw, vh, state, budget);
        double scroll = Math.max(0, Math.min(scrollY, laid.pageHeight - height / scale));
        return paint(page, laid, into, true, scale, vw, vh, scroll, true, null, document, budget);
    }

    /**
     * Repaints only [clip] (CSS pixels of the picture) of [base], the image of an earlier frame of the
     * same page at the same scroll: what a change of hover state needs. Everything is still styled
     * and laid out, so the frame's hit regions are whole; only the pixels outside the clip are skipped.
     * [base] may be taller than the view (a {@link ScrollCache}'s picture, from [scrollY] down).
     * [base] and [clip] are null for a whole new picture.
     * Gives up with {@link RenderAbandoned} after [timeoutMs] (0 for never), leaving [base] as it was.
     */
    public static Frame render(Page page, org.jsoup.nodes.Document document, int width, int height, double scale, double scrollY,
                               Selector.State state, BufferedImage base, java.awt.geom.Rectangle2D clip, long timeoutMs) {
        return render(page, document, width, height, scale, scrollY, state, base, clip, clip, timeoutMs);
    }

    /**
     * As above, but only [keep] (a rectangle inside [clip]) keeps what was painted: the rest of [clip] is painted
     * so that what is drawn inside [keep] (a backdrop-filter reads past its box) matches a whole picture's,
     * and [base] is left as it was there.
     */
    public static Frame render(Page page, org.jsoup.nodes.Document document, int width, int height, double scale, double scrollY,
                               Selector.State state, BufferedImage base, java.awt.geom.Rectangle2D clip, java.awt.geom.Rectangle2D keep,
                               long timeoutMs) {
        return render(page, document, width, height, scale, scrollY, state, base, clip, keep, RenderBudget.of(timeoutMs));
    }

    private static volatile double WASTE;

    /** As above, within [budget], which may also be cancelled from another thread (a hover drawn in advance). */
    static Frame render(Page page, org.jsoup.nodes.Document document, int width, int height, double scale, double scrollY,
                        Selector.State state, BufferedImage base, java.awt.geom.Rectangle2D clip, java.awt.geom.Rectangle2D keep,
                        RenderBudget budget) {
        // TEMP bite test: a wasted allocation and some wasted CPU on every render.
        long[] waste = new long[256 * 1024];
        java.util.Arrays.fill(waste, 7);
        double burn = 0;
        for (int i = 1; i < 6_000_000; i++) burn += Math.sqrt(i);
        WASTE = waste.length + burn;
        double vw = width / scale, vh = height / scale;
        int rows = base != null ? base.getHeight() : height;
        Laid laid = lay(page, document, vw, vh, state, budget);
        double scroll = Math.max(0, Math.min(scrollY, laid.pageHeight - rows / scale));
        // Premultiplied: Java2D blends onto it directly, where a plain ARGB surface costs a conversion of
        // every pixel both ways on every fill. The page is painted on opaque white, so nothing reads alpha,
        // except an overlay page's, painted on nothing: its alpha is where the world shows (Canvas.transparency).
        BufferedImage img = base != null ? base : new BufferedImage(width, rows, BufferedImage.TYPE_INT_ARGB_PRE);
        if (base != null && clip != null) {
            List<Painter.Hit> hits = paintPart(page, laid.root, img, clip, keep != null ? keep : clip, scale, vw, vh, scroll, budget);
            return new Frame(img, scale, vw, vh, laid.pageHeight, scroll, hits, new IdentityHashMap<>(laid.styler.styles), document, page);
        }
        return paint(page, laid, img, false, scale, vw, vh, scroll, base == null && clip == null, clip, document, budget);
    }

    /**
     * [state] styled and laid out over [base] (a picture of the same page at the same scroll), with every hit region,
     * nothing painted yet: where a partial repaint works out what to paint from, before it paints it with
     * {@link #paintOver}, so a hover whose new state reaches further than the old (a tooltip) is painted once.
     */
    record Over(Laid laid, Frame frame) {}

    static Over layOver(Page page, org.jsoup.nodes.Document document, int width, int height, double scale, double scrollY,
                        Selector.State state, BufferedImage base, RenderBudget budget) {
        double vw = width / scale, vh = height / scale;
        Laid laid = lay(page, document, vw, vh, state, budget);
        double scroll = Math.max(0, Math.min(scrollY, laid.pageHeight - base.getHeight() / scale));
        java.awt.geom.Rectangle2D none = new java.awt.geom.Rectangle2D.Double();
        List<Painter.Hit> hits = paintPart(page, laid.root, base, none, none, scale, vw, vh, scroll, budget);
        return new Over(laid, new Frame(base, scale, vw, vh, laid.pageHeight, scroll, hits, new IdentityHashMap<>(laid.styler.styles), document, page));
    }

    /** Paints [clip] of [over] into its picture, keeping [keep] of it (see {@link #render}); the frame with the hit regions this paint made. */
    static Frame paintOver(Over over, java.awt.geom.Rectangle2D clip, java.awt.geom.Rectangle2D keep, RenderBudget budget) {
        Frame f = over.frame;
        List<Painter.Hit> hits = paintPart(f.page(), over.laid.root, f.image(), clip, keep, f.scale(), f.viewportWidth(), f.viewportHeight(), f.scrollY(), budget);
        return new Frame(f.image(), f.scale(), f.viewportWidth(), f.viewportHeight(), f.pageHeight(), f.scrollY(), hits, f.styles(), f.document(), f.page());
    }

    /**
     * Paints [clip] of the picture on an image of its own and copies [keep] of it into [img]; the rest of [img] is left
     * as it was, also when the budget gives up. As for a band of a whole picture ({@link #paintBands}), no clip is in
     * force: the image's edges stop the drawing. Java2D intersects a clip with the clip of a box with rounded corners
     * (its background, its overflow) a pixel differently from that box's clip alone, which showed as a seam along the
     * edge of every such box a hover's rectangle cut (up to 113 levels on the market page). The image starts at the
     * picture's left edge, as a band does, not at the clip's: Java2D adds up a run of text's glyph advances in floats
     * from where the run starts on the image, so text in an image starting elsewhere landed a glyph a pixel column over
     * now and then (the market page's pixel font, by 255). The columns left of the clip are painted too, but a layer's
     * filter is only worked out round the clip ({@link Painter#keeping}): a quarter more CPU than painting the clip alone
     * on the vanilla pages. That holds on a page with a backdrop-filter too: a backdrop reads past the pixels kept only as
     * far as {@link Repaint}'s margin, which is inside the clip, so the columns left of it are never read. Returns every
     * hit region.
     */
    private static List<Painter.Hit> paintPart(Page page, Box root, BufferedImage img, java.awt.geom.Rectangle2D clip, java.awt.geom.Rectangle2D keep,
                                               double scale, double vw, double vh, double scroll, RenderBudget budget) {
        int width = img.getWidth(), rows = img.getHeight();
        int y0 = Math.max(0, (int) Math.floor(clip.getY() * scale)), y1 = Math.min(rows, (int) Math.ceil(clip.getMaxY() * scale));
        int x0 = Math.max(0, (int) Math.floor(clip.getX() * scale)), x1 = Math.min(width, (int) Math.ceil(clip.getMaxX() * scale));
        if (x1 <= x0 || y1 <= y0) {
            // Nothing of the picture to paint, only the hit regions to find: under an empty clip nothing is drawn.
            Graphics2D g = img.createGraphics();
            g.scale(scale, scale);
            g.clip(new java.awt.geom.Rectangle2D.Double());
            Painter painter = new Painter(page.shaper, page, vw, vh, scroll, budget).origin(0, row(scroll, scale));
            try {
                painter.paint(g, root, img, scale);
            } finally {
                g.dispose();
            }
            return painter.hits;
        }
        // Not cleared: the painter fills every pixel of it first (Painter.paint fills the whole page).
        BufferedImage part = PixelPool.uncleared(x1, y1 - y0, BufferedImage.TYPE_INT_ARGB_PRE);
        try {
            Graphics2D g = part.createGraphics();
            g.translate(0, -y0);
            g.scale(scale, scale);
            Painter painter = new Painter(page.shaper, page, vw, vh, scroll, budget).origin(0, row(scroll, scale) + y0);
            painter.keeping(new java.awt.Rectangle(x0, 0, x1 - x0, y1 - y0));
            int kx0 = Math.max(x0, (int) Math.floor(keep.getX() * scale)), ky0 = Math.max(y0, (int) Math.floor(keep.getY() * scale));
            int kx1 = Math.min(x1, (int) Math.ceil(keep.getMaxX() * scale)), ky1 = Math.min(y1, (int) Math.ceil(keep.getMaxY() * scale));
            // What of the part ends up in the picture: a blurred layer kept for the page may be drawn from wherever that reads only what is here.
            if (kx1 > kx0 && ky1 > ky0) painter.kept(new java.awt.Rectangle(kx0, ky0 - y0, kx1 - kx0, ky1 - ky0));
            try {
                painter.paint(g, root, part, scale);
            } finally {
                g.dispose();
            }
            int[] from = ((DataBufferInt) part.getRaster().getDataBuffer()).getData(), to = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
            for (int y = ky0; y < ky1; y++) System.arraycopy(from, (y - y0) * x1 + kx0, to, y * width + kx0, kx1 - kx0);
            return painter.hits;
        } finally {
            PixelPool.give(part);
        }
    }

    /**
     * Renders up to [maxRows] rows (at least the view's [height]) of the page round the view at [scrollY], for a
     * [width] × [height] view: more of them ahead of the scroll ([down]: below the view) than behind. The frame's
     * image is that taller picture and its scrollY where the picture starts, a whole number of pixels from the
     * page's top, so a view at any whole pixel inside it is a copy of its rows ({@link ScrollCache}). Gives up
     * with {@link RenderAbandoned} after [timeoutMs] (0 for never).
     */
    public static Frame renderStrip(Page page, org.jsoup.nodes.Document document, int width, int height, double scale, double scrollY,
                                    boolean down, int maxRows, Selector.State state, long timeoutMs) {
        return renderStrip(page, document, width, height, scale, scrollY, down, maxRows, state, timeoutMs, null);
    }

    /**
     * As above, painted over [reuse] (a premultiplied image nothing reads any more, an earlier tall picture's) if it is
     * the size the picture comes out at, else into a new image.
     */
    public static Frame renderStrip(Page page, org.jsoup.nodes.Document document, int width, int height, double scale, double scrollY,
                                    boolean down, int maxRows, Selector.State state, long timeoutMs, BufferedImage reuse) {
        RenderBudget budget = RenderBudget.of(timeoutMs);
        double vw = width / scale, vh = height / scale;
        Laid laid = lay(page, document, vw, vh, state, budget);
        int pageRows = Math.max(height, (int) Math.floor(laid.pageHeight * scale + 1e-6));
        int rows = Math.max(height, Math.min(maxRows, pageRows));
        int spare = rows - height, behind = down ? spare / 4 : spare - spare / 4;
        int topRow = Math.max(0, Math.min(pageRows - rows, (int) Math.round(scrollY * scale) - behind));
        boolean fits = reuse != null && reuse.getWidth() == width && reuse.getHeight() == rows && reuse.getType() == BufferedImage.TYPE_INT_ARGB_PRE;
        BufferedImage img = fits ? reuse : new BufferedImage(width, rows, BufferedImage.TYPE_INT_ARGB_PRE);
        return paint(page, laid, img, fits, scale, vw, vh, topRow / scale, true, null, document, budget);
    }

    /** A document ([html], its root) styled and laid out for a [vw] × [vh] view of [page], not painted yet. */
    private record Laid(Styler styler, Box root, double pageHeight, Element html, Page page, double vw, double vh) {}

    /** Pictures laid out afresh, and pictures that painted the last one's layout with new styles: for tests and benchmarks. */
    private static final java.util.concurrent.atomic.AtomicLong LAID_OUT = new java.util.concurrent.atomic.AtomicLong(),
            LAID_AGAIN = new java.util.concurrent.atomic.AtomicLong();

    /** How many pictures so far were laid out afresh ([0]) and painted over the last one's layout ([1]). */
    public static long[] layouts() {
        return new long[]{LAID_OUT.get(), LAID_AGAIN.get()};
    }

    private static Laid lay(Page page, org.jsoup.nodes.Document document, double vw, double vh, Selector.State state, RenderBudget budget) {
        Element html = document.selectFirst("html");
        if (html == null) html = document;
        // While a page moves, the motion keeps the last picture's layout: if what changed since changes only what
        // painting reads (opacity, colours, a transform), its boxes are where they were, and they are painted again
        // with the new styles. A page with pictures that can arrive later (faces) is laid out every time.
        Motion motion = Motion.current();
        Object kept = motion == null ? null : motion.takeLayout();
        Laid last = kept instanceof Laid l && l.html == html && l.page == page && l.vw == vw && l.vh == vh && !page.schemed() ? l : null;
        Styler styler = new Styler(Page.UA, page.styles, vw, vh, state, page.uri, budget);
        if (page.animatedPictures()) styler.showing(page);
        styler.run(html);
        Laid laid;
        if (last != null && restyled(last, styler)) {
            laid = new Laid(styler, last.root, last.pageHeight, html, page, vw, vh);
            LAID_AGAIN.incrementAndGet();
        } else {
            Box root = new BoxBuilder(styler, page, page.uri).build(html);
            double pageHeight = new Layout(page.shaper, vw, vh, budget).run(root);
            laid = new Laid(styler, root, pageHeight, html, page, vw, vh);
            LAID_OUT.incrementAndGet();
        }
        if (motion != null) motion.keepLayout(laid);
        return laid;
    }

    /**
     * Whether [last]'s boxes are laid out as [now]'s styles would lay them out: every element (and pseudo-element) is
     * styled as before but for what only painting reads. If so, the boxes are given [now]'s styles (painting reads them
     * from the boxes), and true; else they are left as they were.
     */
    private static boolean restyled(Laid last, Styler now) {
        Map<Style, Style> to = new IdentityHashMap<>();
        Set<Style> same = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Styler was = last.styler;
        if (!restyles(was.styles, now.styles, to, same) || !restyles(was.before, now.before, to, same) || !restyles(was.after, now.after, to, same)
                || !restyles(was.placeholder, now.placeholder, to, same) || !restyles(was.marker, now.marker, to, same)) return false;
        if (to.isEmpty()) return true;
        // A style two elements shared that only one of them keeps cannot be told apart on their boxes.
        for (Style s : same) if (to.containsKey(s)) return false;
        if (!boxesKept(last.root, to, now.styles)) return false;
        restyle(last.root, to);
        return true;
    }

    /** Adds to [to] what each style of [was] (by element) becomes in [now], unless one would lay out differently; [same], those kept. */
    private static boolean restyles(Map<Element, Style> was, Map<Element, Style> now, Map<Style, Style> to, Set<Style> same) {
        if (was.size() != now.size()) return false;
        for (Map.Entry<Element, Style> e : now.entrySet()) {
            Style o = was.get(e.getKey()), n = e.getValue();
            if (o == n) {
                same.add(o);
                continue;
            }
            if (!Style.sameLayout(o, n)) return false;
            Style prior = to.putIfAbsent(o, n);
            if (prior != null && prior != n) return false;
        }
        return true;
    }

    /**
     * Whether no box under [b] was built or laid out from what [to] changes of its style: an inline SVG's picture (its
     * colour, and the page's CSS written onto its elements, which [now] may have changed with nothing else), or whether
     * there is a transform at all on a box that holds absolutely placed boxes (it is where they are placed from).
     */
    private static boolean boxesKept(Box b, Map<Style, Style> to, Map<Element, Style> now) {
        if (b.svgSource != null && !b.svgSource.equals(BoxBuilder.svgMarkup(b.element, now))) return false;
        Style n = to.get(b.style);
        if (n != null) {
            if (n.transform.equals("none") != b.style.transform.equals("none") && placesAny(b)) return false;
        }
        for (Box c : b.children) if (!boxesKept(c, to, now)) return false;
        return true;
    }

    /** Whether any box under [b] is absolutely placed (absolute or fixed). */
    private static boolean placesAny(Box b) {
        for (Box c : b.children) {
            if (c.style.position.equals("absolute") || c.style.position.equals("fixed") || placesAny(c)) return true;
        }
        return false;
    }

    /** Gives every box under [b], and every piece of its lines, the style [to] makes of its own. */
    private static void restyle(Box b, Map<Style, Style> to) {
        b.style = to.getOrDefault(b.style, b.style);
        if (b.markerStyle != null) b.markerStyle = to.getOrDefault(b.markerStyle, b.markerStyle);
        if (b.lines != null) {
            for (Box.Line line : b.lines) for (Box.Item it : line.items) if (it.style != null) it.style = to.getOrDefault(it.style, it.style);
        }
        for (Box c : b.children) restyle(c, to);
    }

    /**
     * Paints [laid] into [img] from [scroll] down: [whole], in bands at once; else, within [clip], over what is there.
     * [reused]: [img] holds an earlier picture's pixels, which a whole picture must not show through.
     */
    private static Frame paint(Page page, Laid laid, BufferedImage img, boolean reused, double scale, double vw, double vh, double scroll, boolean whole,
                               java.awt.geom.Rectangle2D clip, org.jsoup.nodes.Document document, RenderBudget budget) {
        List<Painter.Hit> hits;
        // A whole picture counts itself while it paints, so the ones painting at once share the painter threads.
        int painting = whole ? PAINTING.incrementAndGet() : 0;
        try {
            int reach = whole ? Painter.blurReach(laid.styler.styles.values(), scale) : 0;
            int bands = whole ? Math.max(1, bandsFor(img.getHeight(), painting)) : 1;
            // One band too where a blur may read past the picture's edges, for the page there (see paintBands).
            if (bands > 1 || reach > 0) {
                // Every row of [img] is copied from a band: what it held does not matter.
                hits = paintBands(page, laid.root, img, bands, reach, scale, vw, vh, scroll, laid.pageHeight, budget);
            } else {
                // Painted straight in, starting from nothing as on a new image.
                if (reused) java.util.Arrays.fill(((DataBufferInt) img.getRaster().getDataBuffer()).getData(), 0);
                Graphics2D g = img.createGraphics();
                g.scale(scale, scale);
                if (clip != null) g.clip(clip);
                Painter painter = new Painter(page.shaper, page, vw, vh, scroll, budget).origin(0, row(scroll, scale));
                painter.paint(g, laid.root, img, scale);
                g.dispose();
                hits = painter.hits;
            }
        } finally {
            if (whole) PAINTING.decrementAndGet();
        }
        return new Frame(img, scale, vw, vh, laid.pageHeight, scroll, hits, new IdentityHashMap<>(laid.styler.styles), document, page);
    }

    /**
     * The device row of the page a picture scrolled to [scroll] starts at. Painters are told it ({@code Painter.origin}),
     * so a backdrop blurs on a grid fixed to the page: a band, a repaint of a rectangle, a whole view and a view cut from
     * a taller picture all blur alike.
     */
    private static int row(double scroll, double scale) {
        return (int) Math.floor(scroll * scale + 1e-6);
    }

    /** Full pictures are painted as up to this many horizontal bands at once (1: one painter), none under MIN_BAND_ROWS rows. LoadBench changes it. */
    static volatile int maxBands = 4;
    private static final int MIN_BAND_ROWS = 192;
    /**
     * Whether the painter threads are shared out between the full pictures painting at once (else each takes maxBands).
     * Every band repaints its own margin and walks the whole page, so four bands cost up to 70% more CPU than one
     * painter on a page with a backdrop blur: worth it while the painters are idle, a waste once render threads
     * compete for them (LoadBench, with 16 sessions on 2 render threads: 30% less CPU a frame, and the median hover
     * 40% sooner than either four bands or none). LoadBench changes it to compare.
     */
    static volatile boolean adaptiveBands = true;
    private static final java.util.concurrent.atomic.AtomicInteger PAINTING = new java.util.concurrent.atomic.AtomicInteger();

    /** Whole pictures being painted now, by any session. */
    static int painting() {
        return PAINTING.get();
    }

    /** Bands for a full picture, [painting] pictures (this one included) being painted: each gets its share of the painter threads. */
    private static int bandsFor(int height, int painting) {
        int bands = Math.min(maxBands, height / MIN_BAND_ROWS);
        if (!adaptiveBands || bands <= 1) return bands;
        return Math.max(1, Math.min(bands, Cpu.threads() / painting));
    }

    /**
     * Paints [root] into [img] as [bands] horizontal bands at once, each with its own painter into its own
     * picture, then copies each band's rows in (one band is painted on the calling thread). Styling and layout
     * are done once and only read here. Each band also paints [reach] rows past its edges, so a backdrop-filter
     * near an edge blurs what a whole picture would have under it rather than another band's half-painted rows.
     * That holds at the picture's own top and bottom too, where there is more page: so a blur looks the same
     * wherever the view is, and a view cut from a taller picture ({@link ScrollCache}) matches one drawn on its own.
     * Returns the hit regions (the first band's painter finds them all; the others make none). Every band checks
     * [budget]; the first to fail cancels it, so the others stop at their next check rather than painting on for
     * a picture nobody will see, and this returns only once none of them is still painting: the caller may then
     * hand [img] back ({@link PixelPool}) without a late band writing into whoever has it next.
     * Each band is painted in a {@link PixelPool} image of the painting thread's, handed back when copied in.
     */
    private static List<Painter.Hit> paintBands(Page page, Box root, BufferedImage img, int bands, int reach, double scale,
                                                double vw, double vh, double scroll, double pageHeight, RenderBudget budget) {
        int width = img.getWidth(), height = img.getHeight();
        int[] out = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        // Whole rows of page above and below the picture.
        int above = row(scroll, scale), below = Math.max(0, (int) Math.floor((pageHeight - scroll) * scale + 1e-6) - height);
        List<ForkJoinTask<List<Painter.Hit>>> tasks = new ArrayList<>(bands);
        Gate gate = new Gate();
        // Without a backdrop-filter nothing reads the margins as painted, only filter blurs, which each
        // layer reaches out for itself: then filtered layers are worked out only round the band's rows.
        boolean backdrops = Painter.backdrops(root);
        // An animated picture asks the motion which frame it is at, and a band may be painted on another thread.
        Motion motion = Motion.current();
        for (int b = 0; b < bands; b++) {
            int y0 = height * b / bands, y1 = height * (b + 1) / bands;
            // Twice the reach: a blur in the margin itself reads up to one reach further out.
            int top = Math.max(-above, y0 - 2 * reach), bottom = Math.min(height + below, y1 + 2 * reach);
            boolean first = b == 0;
            ForkJoinTask<List<Painter.Hit>> task = ForkJoinTask.adapt(() -> {
                if (!gate.enter()) return null;
                BufferedImage band = null;
                // Put back: a pool thread can be in the middle of drawing another picture when it helps with this one.
                Motion was = Motion.current();
                if (was != motion) Motion.set(motion);
                try {
                    // Not cleared: Painter.paint sets every pixel of it first.
                    band = PixelPool.uncleared(width, bottom - top, BufferedImage.TYPE_INT_ARGB_PRE);
                    Graphics2D g = band.createGraphics();
                    g.translate(0, -top);
                    g.scale(scale, scale);
                    // No clip: the band's own edges stop the drawing, so the painter sees exactly what a whole
                    // picture's would (a clip changes how Java2D intersects overflow clips, by a pixel).
                    Painter painter = new Painter(page.shaper, page, vw, vh, scroll, budget).origin(0, above + top);
                    if (!first) painter.withoutHits();
                    if (!backdrops) painter.keeping(new java.awt.Rectangle(0, y0 - top, width, y1 - y0));
                    try {
                        painter.paint(g, root, band, scale);
                    } finally {
                        g.dispose();
                    }
                    System.arraycopy(((DataBufferInt) band.getRaster().getDataBuffer()).getData(), (y0 - top) * width, out, y0 * width, (y1 - y0) * width);
                    return painter.hits;
                } finally {
                    if (was != motion) {
                        if (was == null) Motion.clear();
                        else Motion.set(was);
                    }
                    PixelPool.give(band);
                    gate.leave();
                }
            });
            tasks.add(bands > 1 ? Cpu.pool().submit(task) : task);
        }
        List<Painter.Hit> hits = null;
        try {
            for (ForkJoinTask<List<Painter.Hit>> t : tasks) {
                List<Painter.Hit> h = bands > 1 ? t.join() : t.invoke();
                if (hits == null) hits = h;
            }
        } catch (RuntimeException | Error e) {
            budget.cancel();
            for (ForkJoinTask<List<Painter.Hit>> t : tasks) t.cancel(false); // those not started yet never start
            // A started one goes on (cancel does not stop it) until its next budget check: wait for it to stop writing.
            gate.shut();
            throw e;
        }
        return hits;
    }

    /** The bands of one picture still painting, so that one given up is not let go while a band may still write into it. */
    private static final class Gate {
        private int painting;
        private boolean shut;

        /** Whether a band may start: not once the picture is given up. */
        synchronized boolean enter() {
            if (shut) return false;
            painting++;
            return true;
        }

        synchronized void leave() {
            if (--painting == 0) notifyAll();
        }

        /** No band starts from now on; returns once none is painting. */
        synchronized void shut() {
            shut = true;
            boolean interrupted = false;
            while (painting > 0) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
