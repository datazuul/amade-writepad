package org.datazuul.amadeos.writepad.ui.editor;

import org.datazuul.amadeos.commons.swing.AmadeUI;
import org.datazuul.amadeos.writepad.service.layout.PageLayout;
import org.datazuul.amadeos.writepad.service.layout.PagedEditorKit;
import org.datazuul.amadeos.writepad.service.layout.PagedSectionView;

import javax.swing.BorderFactory;
import javax.swing.JTextPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.View;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.geom.Rectangle2D;
import java.util.Objects;
import java.util.function.IntFunction;

/**
 * The text area of amade-writepad: styled text on white pages on a dark desk, in the colours of no theme
 * (paper is white everywhere), zoomable. 100 % means true size on a screen with the resolution the system
 * reports.
 */
public final class PageEditor extends JTextPane {
    /** Font of new text until documents bring their own styles. */
    public static final Font DEFAULT_FONT = new Font(Font.SERIF, Font.PLAIN, 12);

    private final PageLayout layout;
    private IntFunction<String> footer = page -> null;

    public PageEditor(PageLayout layout) {
        this.layout = Objects.requireNonNull(layout, "layout");
        setEditorKit(new PagedEditorKit(layout));
        setMargin(new Insets(0, 0, 0, 0));
        AmadeUI.style(this, (editor, theme) -> {
            editor.setBackground(theme.shadow().darker());
            editor.setForeground(Color.BLACK);
            editor.setCaretColor(Color.BLACK);
            editor.setFont(DEFAULT_FONT);
            // The desk starts at the edge; the scroll pane draws the frame.
            editor.setBorder(BorderFactory.createEmptyBorder());
        });
        layout.addChangeListener(this::pageLayoutChanged);
    }

    /** Screen pixels per point for true size on this screen. */
    public static double screenScale() {
        try {
            return Toolkit.getDefaultToolkit().getScreenResolution() / 72.0;
        } catch (java.awt.HeadlessException e) {
            return 96 / 72.0;
        }
    }

    public PageLayout pageLayout() {
        return layout;
    }

    /**
     * Metrics in points with fractional advances, the same the zoomed and printed text is painted with.
     * Swing's text views measure lines, caret positions and clicks with the metrics of their component; with
     * the default whole-pixel advances every glyph is up to half a point off, and at the end of a line the
     * caret lands visibly beside the last character.
     */
    @Override
    public FontMetrics getFontMetrics(Font font) {
        return LayoutMetrics.of(font);
    }

    /** Text in the bottom margin of each page (0-based page index), e.g. page numbers. */
    public void setFooter(IntFunction<String> footer) {
        this.footer = Objects.requireNonNull(footer, "footer");
        PagedSectionView section = section();
        if (section != null) section.setFooter(footer);
        repaint();
    }

    /** The page view, or null before the UI has created it. */
    public PagedSectionView section() {
        if (getUI() == null) return null;
        View root = getUI().getRootView(this);
        if (root.getViewCount() > 0 && root.getView(0) instanceof PagedSectionView section) {
            section.setFooter(footer);
            return section;
        }
        return null;
    }

    /** Lays the views out now, as painting would; for printing and status display. */
    public PagedSectionView layOut() {
        PagedSectionView section = section();
        if (section == null) return null;
        View root = getUI().getRootView(this);
        root.setSize(Math.max(getWidth(), 1), Math.max(getHeight(), 1));
        return section;
    }

    public int pageCount() {
        PagedSectionView section = layOut();
        return section == null ? 1 : section.pageCount();
    }

    /** 0-based page of a document offset. */
    public int pageOf(int offset) {
        try {
            layOut();
            Rectangle2D r = modelToView2D(Math.min(offset, getDocument().getLength()));
            return r == null ? 0 : layout.pageAt(r.getY() / layout.zoom());
        } catch (BadLocationException e) {
            return 0;
        }
    }

    private void pageLayoutChanged() {
        PagedSectionView section = section();
        if (section != null) section.pageLayoutChanged();
        revalidate();
        repaint();
    }

    // The desk fills the viewport; pages are centred on it.

    /** Size of the pages at the current zoom; JEditorPane.getPreferredSize would ask the methods below. */
    private Dimension pagesSize() {
        return getUI().getPreferredSize(this);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        Container parent = SwingUtilities.getUnwrappedParent(this);
        return parent instanceof JViewport port && port.getWidth() > pagesSize().width;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        Container parent = SwingUtilities.getUnwrappedParent(this);
        return parent instanceof JViewport port && port.getHeight() > pagesSize().height;
    }
}
