package org.datazuul.amadeos.writepad.ui.editor;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Font metrics for laying out pages: unscaled (points), anti-aliased, with fractional advances. Screen
 * painting at any zoom and printing use the same rendering hints, so measured and painted glyph positions
 * agree.
 */
final class LayoutMetrics {
    private static final Graphics2D GRAPHICS = createGraphics();
    private static final Map<Font, FontMetrics> CACHE = new ConcurrentHashMap<>();

    private LayoutMetrics() {
    }

    static FontMetrics of(Font font) {
        return CACHE.computeIfAbsent(font, f -> {
            synchronized (GRAPHICS) {
                return GRAPHICS.getFontMetrics(f);
            }
        });
    }

    private static Graphics2D createGraphics() {
        Graphics2D g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        return g;
    }
}
