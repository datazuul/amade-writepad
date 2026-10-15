package org.datazuul.amadeos.writepad.service.layout;

import javax.swing.text.BadLocationException;
import javax.swing.text.BoxView;
import javax.swing.text.Caret;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;
import javax.swing.text.Position;
import javax.swing.text.View;
import javax.swing.event.DocumentEvent;
import javax.swing.text.ViewFactory;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Container;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.function.IntFunction;

/**
 * The document as a stack of pages. Paragraphs are laid out one after the other in layout space (points,
 * see {@link PageLayout}); each paragraph moves lines that would cross a page boundary to the next page.
 *
 * <p>The view also applies the zoom: everything outside (component size, caret, mouse) is in screen pixels,
 * everything inside (paragraphs, lines, glyphs) in points. {@link #paint}, {@link #modelToView},
 * {@link #viewToModel} and the preferred size translate between the two. Pages are centred horizontally
 * when the window is wider than the page.
 */
public final class PagedSectionView extends BoxView {
    private static final Color PAPER = Color.WHITE;
    private static final Color PAGE_EDGE = new Color(0x606060);
    private static final Color PAGE_SHADOW = new Color(0, 0, 0, 90);
    private static final Color FOOTER_TEXT = new Color(0x707070);

    private final PageLayout layout;
    private int pageCount = 1;
    private IntFunction<String> footer = page -> null;

    PagedSectionView(Element element, PageLayout layout) {
        super(element, Y_AXIS);
        this.layout = layout;
        applyInsets();
    }

    public PageLayout pageLayout() {
        return layout;
    }

    /** Pages of the last layout. */
    public int pageCount() {
        return pageCount;
    }

    /** Text for the bottom margin of a page (0-based index), e.g. "1 / 3"; null for none. */
    public void setFooter(IntFunction<String> footer) {
        this.footer = footer;
    }

    /** Lays the document out again after the paper, orientation or margins changed. */
    public void pageLayoutChanged() {
        applyInsets();
        for (int i = 0; i < getViewCount(); i++) {
            View child = getView(i);
            if (child instanceof PagedParagraphView paragraph) paragraph.setStartY(-1);
        }
        layoutChanged(X_AXIS);
        layoutChanged(Y_AXIS);
        preferenceChanged(null, true, true);
    }

    private void applyInsets() {
        setInsets((short) 0, (short) (PageLayout.GAP + layout.left()), (short) 0,
                (short) (PageLayout.GAP + layout.right()));
    }

    // ---------------------------------------------------------------- layout (points)

    @Override
    public void setSize(float width, float height) {
        // The page, not the window, decides the width of the text.
        super.setSize(layout.totalWidth(), layout.totalHeight(pageCount));
    }

    @Override
    protected void layoutMajorAxis(int targetSpan, int axis, int[] offsets, int[] spans) {
        int width = layout.contentWidth();
        int y = layout.contentTop(0);
        for (int i = 0; i < getViewCount(); i++) {
            View child = getView(i);
            int height;
            if (child instanceof PagedParagraphView paragraph) {
                paragraph.setStartY(y);
                paragraph.setSize(width, 0);
                height = paragraph.laidOutHeight();
            } else {
                height = (int) Math.ceil(child.getPreferredSpan(Y_AXIS));
                y = layout.place(y, height);
            }
            offsets[i] = y;
            spans[i] = height;
            y += height;
        }
        int pages = layout.pagesUpTo(y);
        if (pages != pageCount) {
            pageCount = pages;
            // The component has to grow or shrink; the parent asks for the preferred size again.
            View parent = getParent();
            if (parent != null) parent.preferenceChanged(this, true, true);
        }
    }

    @Override
    public float getPreferredSpan(int axis) {
        return (float) (unscaledSpan(axis) * layout.zoom());
    }

    @Override
    public float getMinimumSpan(int axis) {
        return getPreferredSpan(axis);
    }

    @Override
    public float getMaximumSpan(int axis) {
        return getPreferredSpan(axis);
    }

    private int unscaledSpan(int axis) {
        return axis == X_AXIS ? layout.totalWidth() : layout.totalHeight(pageCount);
    }

    /** In a gap or margin, a point belongs to the paragraph above it (BoxView would pick the last one). */
    @Override
    protected View getViewAtPoint(int x, int y, Rectangle alloc) {
        int found = 0;
        for (int i = 0; i < getViewCount(); i++) {
            if (alloc.y + getOffset(Y_AXIS, i) <= y) found = i;
        }
        childAllocation(found, alloc);
        return getView(found);
    }

    // ---------------------------------------------------------------- zoom: screen <-> points

    private AffineTransform toScreen(Shape allocation) {
        Rectangle a = allocation.getBounds();
        double zoom = layout.zoom();
        double dx = Math.max(0, (a.width - layout.totalWidth() * zoom) / 2);
        AffineTransform t = AffineTransform.getTranslateInstance(a.x + dx, a.y);
        t.scale(zoom, zoom);
        return t;
    }

    private Rectangle pointsAllocation() {
        return new Rectangle(0, 0, layout.totalWidth(), layout.totalHeight(pageCount));
    }

    @Override
    public void paint(Graphics g, Shape allocation) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.transform(toScreen(allocation));
            // Glyphs scaled from point sizes: without fractional metrics they snap to whole pixels and the
            // spacing inside a line goes uneven.
            g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Rectangle clip = g2.getClipBounds();
            for (int page = 0; page < pageCount; page++) {
                Rectangle sheet = new Rectangle(PageLayout.GAP, layout.pageTop(page), layout.pageWidth(),
                        layout.pageHeight());
                if (clip != null && !clip.intersects(sheet.x - 4, sheet.y - 4, sheet.width + 8, sheet.height + 8)) {
                    continue;
                }
                g2.setColor(PAGE_SHADOW);
                g2.fillRect(sheet.x + 3, sheet.y + 3, sheet.width, sheet.height);
                g2.setColor(PAPER);
                g2.fill(sheet);
                g2.setColor(PAGE_EDGE);
                g2.setStroke(new BasicStroke(0.5f));
                g2.draw(sheet);
                paintFooter(g2, page);
            }
            super.paint(g2, pointsAllocation());
        } finally {
            g2.dispose();
        }
    }

    /**
     * Paints one page without zoom and without the page decoration, with the top-left corner of the paper at
     * (0, 0): for printing.
     */
    public void paintPage(Graphics2D g, int page) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g2.clipRect(0, 0, layout.pageWidth(), layout.pageHeight());
            g2.translate(-PageLayout.GAP, -layout.pageTop(page));
            paintFooter(g2, page);
            super.paint(g2, pointsAllocation());
        } finally {
            g2.dispose();
        }
    }

    private void paintFooter(Graphics2D g, int page) {
        String text = footer.apply(page);
        if (text == null || text.isEmpty()) return;
        g.setColor(FOOTER_TEXT);
        g.setFont(new Font(Font.SERIF, Font.PLAIN, 9));
        FontMetrics fm = g.getFontMetrics();
        int bottomMargin = layout.pageTop(page) + layout.pageHeight() - layout.contentBottom(page);
        int baseline = layout.contentBottom(page) + Math.max(fm.getAscent(), bottomMargin / 2);
        g.drawString(text, PageLayout.GAP + (layout.pageWidth() - fm.stringWidth(text)) / 2, baseline);
    }

    @Override
    public Shape modelToView(int pos, Shape allocation, Position.Bias b) throws BadLocationException {
        Shape shape = super.modelToView(pos, pointsAllocation(), b);
        return toScreen(shape, allocation);
    }

    @Override
    public Shape modelToView(int p0, Position.Bias b0, int p1, Position.Bias b1, Shape allocation)
            throws BadLocationException {
        Shape shape = super.modelToView(p0, b0, p1, b1, pointsAllocation());
        return toScreen(shape, allocation);
    }

    private Shape toScreen(Shape shape, Shape allocation) {
        Rectangle r = toScreen(allocation).createTransformedShape(shape).getBounds();
        // A caret is zero pixels wide; keep it that way instead of rounding it up.
        if (shape.getBounds().width == 0) r.width = 0;
        return r;
    }

    @Override
    public int viewToModel(float x, float y, Shape allocation, Position.Bias[] bias) {
        Point2D.Double p = toPoints(x, y, allocation);
        int pos = super.viewToModel((float) p.x, (float) p.y, pointsAllocation(), bias);
        return nearestOnRow(pos, p.x);
    }

    /**
     * The character boundary on the row of {@code pos} that is closest to x (points). Swing's views find the
     * offset for a point with whole-pixel glyph widths, while the caret and the painted text use fractional
     * ones (see {@code PageEditor.getFontMetrics}); this corrects the difference of a character or two
     * towards the end of a line, using the same measurement as the caret.
     */
    private int nearestOnRow(int pos, double x) {
        Rectangle2D start = caretAt(pos);
        if (start == null) return pos;
        double distance = Math.abs(start.getX() - x);
        int length = getDocument().getLength();
        for (int step : new int[]{-1, 1}) {
            for (int next = pos + step; next >= 0 && next <= length; next += step) {
                Rectangle2D r = caretAt(next);
                if (r == null || r.getY() != start.getY()) break;
                double d = Math.abs(r.getX() - x);
                if (d >= distance) break;
                distance = d;
                pos = next;
            }
        }
        return pos;
    }

    /** The caret rectangle of an offset in points, null if it has none. */
    private Rectangle2D caretAt(int pos) {
        try {
            Shape shape = super.modelToView(pos, pointsAllocation(), Position.Bias.Forward);
            return shape == null ? null : shape.getBounds2D();
        } catch (BadLocationException e) {
            return null;
        }
    }

    private Point2D.Double toPoints(double x, double y, Shape allocation) {
        AffineTransform t = toScreen(allocation);
        double zoom = layout.zoom();
        return new Point2D.Double((x - t.getTranslateX()) / zoom, (y - t.getTranslateY()) / zoom);
    }

    /**
     * Up and down keep the caret's column. The text component remembers that column in screen pixels; the
     * paragraphs compare it with their lines in points, so it is converted for the duration of the call.
     */
    @Override
    public int getNextVisualPositionFrom(int pos, Position.Bias b, Shape allocation, int direction,
                                         Position.Bias[] biasRet) throws BadLocationException {
        Caret caret = getContainer() instanceof JTextComponent text ? text.getCaret() : null;
        Point magic = caret == null ? null : caret.getMagicCaretPosition();
        Point2D.Double column = magic == null ? null : toPoints(magic.x, magic.y, allocation);
        if (magic != null) caret.setMagicCaretPosition(new Point((int) Math.round(column.x), (int) Math.round(column.y)));
        try {
            int next = super.getNextVisualPositionFrom(pos, b, pointsAllocation(), direction, biasRet);
            boolean vertical = direction == NORTH || direction == SOUTH;
            return vertical && column != null && next >= 0 ? nearestOnRow(next, column.x) : next;
        } finally {
            if (magic != null) caret.setMagicCaretPosition(magic);
        }
    }

    // getChildAllocation stays in points: CompositeView uses it internally to walk into the children.

    // Edits repaint the whole component: child views would compute their damage in points.

    @Override
    public void insertUpdate(DocumentEvent e, Shape allocation, ViewFactory f) {
        super.insertUpdate(e, null, f);
        repaintAll();
    }

    @Override
    public void removeUpdate(DocumentEvent e, Shape allocation, ViewFactory f) {
        super.removeUpdate(e, null, f);
        repaintAll();
    }

    @Override
    public void changedUpdate(DocumentEvent e, Shape allocation, ViewFactory f) {
        super.changedUpdate(e, null, f);
        repaintAll();
    }

    private void repaintAll() {
        Container host = getContainer();
        if (host != null) host.repaint();
    }
}
