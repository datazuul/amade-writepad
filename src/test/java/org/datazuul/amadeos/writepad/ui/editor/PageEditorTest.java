package org.datazuul.amadeos.writepad.ui.editor;

import org.datazuul.amadeos.writepad.domain.DocumentSettings;
import org.datazuul.amadeos.writepad.domain.Margins;
import org.datazuul.amadeos.writepad.domain.Orientation;
import org.datazuul.amadeos.writepad.domain.PaperSize;
import org.datazuul.amadeos.writepad.service.layout.PageLayout;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import java.awt.Font;
import java.awt.Point;
import java.awt.font.FontRenderContext;
import java.awt.Rectangle;
import java.lang.reflect.InvocationTargetException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Page breaks, zoom and hit-testing of the page view, on a real {@link PageEditor}. */
class PageEditorTest {
    private PageLayout layout;
    private PageEditor editor;

    private void withSample(DocumentSettings settings, double zoom, Runnable test) throws Exception {
        onEdt(() -> {
            layout = new PageLayout(settings, zoom);
            editor = new PageEditor(layout);
            SampleText.appendTo(editor.getStyledDocument(), 6);
            fit();
            test.run();
        });
    }

    private void fit() {
        editor.setSize(editor.getPreferredSize());
        editor.layOut();
        editor.setSize(editor.getPreferredSize());
    }

    @Test
    void sampleFillsSeveralA4Pages() throws Exception {
        withSample(DocumentSettings.DEFAULT, 1, () -> {
            int pages = editor.pageCount();
            assertTrue(pages >= 4, "pages: " + pages);
            assertEquals(layout.totalHeight(pages), editor.getPreferredSize().height, 1);
            // Swing adds a pixel for the caret at the end of a line.
            assertEquals(layout.totalWidth(), editor.getPreferredSize().width, 1);
            assertLinesInsideTextArea();
        });
    }

    @Test
    void smallerPaperNeedsMorePages() throws Exception {
        withSample(DocumentSettings.DEFAULT, 1, () -> {
            int a4 = editor.pageCount();
            layout.setSettings(DocumentSettings.DEFAULT.withPaper(PaperSize.A5));
            fit();
            int a5 = editor.pageCount();
            assertTrue(a5 > a4, "A4 " + a4 + ", A5 " + a5);
            assertLinesInsideTextArea();
            layout.setSettings(DocumentSettings.DEFAULT.withPaper(PaperSize.A5).withOrientation(Orientation.LANDSCAPE));
            fit();
            // Swing adds a pixel for the caret at the end of a line.
            assertEquals(layout.totalWidth(), editor.getPreferredSize().width, 1);
            assertTrue(editor.pageCount() > a4);
            assertLinesInsideTextArea();
        });
    }

    @Test
    void widerMarginsReflowTheText() throws Exception {
        withSample(DocumentSettings.DEFAULT, 1, () -> {
            int before = editor.pageCount();
            layout.setSettings(DocumentSettings.DEFAULT.withMargins(Margins.ofMm(40, 50, 40, 50)));
            fit();
            assertTrue(editor.pageCount() > before);
            assertLinesInsideTextArea();
        });
    }

    @Test
    void zoomScalesTheViewAndKeepsTheLayout() throws Exception {
        withSample(DocumentSettings.DEFAULT, 1, () -> {
            int pages = editor.pageCount();
            Rectangle at100 = view(500);
            layout.setZoom(2);
            fit();
            assertEquals(pages, editor.pageCount());
            assertEquals(2 * layout.totalWidth(), editor.getPreferredSize().width, 1);
            Rectangle at200 = view(500);
            assertEquals(2 * at100.y, at200.y, 2);
            assertEquals(2 * at100.x, at200.x, 2);
            assertLinesInsideTextArea();
        });
    }

    @Test
    void clicksFindTheirTextAtEveryZoom() throws Exception {
        for (double zoom : new double[]{0.5, 1, 4.0 / 3, 2}) {
            withSample(DocumentSettings.DEFAULT, zoom, () -> {
                int length = editor.getDocument().getLength();
                for (int offset = 0; offset < length; offset += 41) {
                    Rectangle r = view(offset);
                    int hit = editor.viewToModel2D(new Point(r.x + 1, r.y + r.height / 2));
                    assertTrue(Math.abs(hit - offset) <= 1, "zoom " + zoom + ": " + offset + " -> " + hit);
                }
            });
        }
    }

    /**
     * The caret at the end of a line sits where the painted text ends: lines are measured with the same
     * fractional advances they are painted with, not with whole pixels per glyph.
     */
    @Test
    void lineEndCaretMatchesThePaintedText() throws Exception {
        String line = "Lorem ipsum dolor sit amet, consectetur adipiscing elit.";
        for (double zoom : new double[]{1, 4.0 / 3, 2}) {
            onEdt(() -> {
                layout = new PageLayout(DocumentSettings.DEFAULT, zoom);
                editor = new PageEditor(layout);
                try {
                    editor.getDocument().insertString(0, line, null);
                } catch (BadLocationException e) {
                    throw new AssertionError(e);
                }
                fit();
                double measured = (view(line.length()).x - view(0).x) / zoom;
                Font font = editor.getStyledDocument().getFont(editor.getStyledDocument().getCharacterElement(0)
                        .getAttributes());
                double painted = font.getStringBounds(line, new FontRenderContext(null, true, true)).getWidth();
                assertEquals(painted, measured, 0.75 + 1 / zoom, "zoom " + zoom);
            });
        }
    }

    /** A click right of a paragraph's last line puts the caret after its last character, where Enter splits. */
    @Test
    void clickAfterLineEndGoesToTheEndOfTheParagraph() throws Exception {
        for (double zoom : new double[]{0.5, 1, 4.0 / 3, 2}) {
            withSample(DocumentSettings.DEFAULT, zoom, () -> {
                int end = editor.getStyledDocument().getParagraphElement(200).getEndOffset() - 1;
                Rectangle last = view(end);
                int right = (int) ((PageLayout.GAP + layout.pageWidth() - layout.right()) * zoom) - 2;
                int hit = editor.viewToModel2D(new Point(right, last.y + last.height / 2));
                assertEquals(end, hit, "zoom " + zoom);
            });
        }
    }

    @Test
    void pagesAreCentredInAWiderWindow() throws Exception {
        withSample(DocumentSettings.DEFAULT, 1, () -> {
            int left = view(0).x;
            editor.setSize(editor.getPreferredSize().width + 200, editor.getPreferredSize().height);
            assertEquals(left + 100, view(0).x, 1);
        });
    }

    @Test
    void pageOfFollowsTheText() throws Exception {
        withSample(DocumentSettings.DEFAULT, 1, () -> {
            assertEquals(0, editor.pageOf(0));
            assertEquals(editor.pageCount() - 1, editor.pageOf(editor.getDocument().getLength()));
        });
    }

    /** Every line lies between the top and bottom margin of its page. */
    private void assertLinesInsideTextArea() {
        double zoom = layout.zoom();
        double tolerance = 2 / zoom + 0.5;
        int length = editor.getDocument().getLength();
        for (int offset = 0; offset < length; offset += 11) {
            Rectangle r = view(offset);
            double y = r.y / zoom;
            double bottom = y + r.height / zoom;
            int page = layout.pageAt(y);
            assertTrue(y >= layout.contentTop(page) - tolerance && bottom <= layout.contentBottom(page) + tolerance,
                    "offset " + offset + " at " + y + ".." + bottom + " outside page " + page);
        }
    }

    private Rectangle view(int offset) {
        try {
            return editor.modelToView2D(offset).getBounds();
        } catch (BadLocationException e) {
            throw new AssertionError(e);
        }
    }

    static void onEdt(Runnable test) throws Exception {
        try {
            SwingUtilities.invokeAndWait(test);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Error error) throw error;
            if (e.getCause() instanceof Exception exception) throw exception;
            throw e;
        }
    }
}
