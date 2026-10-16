package uk.gov.moj.cp.ingestion.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.azure.ai.documentintelligence.models.DocumentLine;

/**
 * Joins a page's Document Intelligence lines using their geometry: {@code \t} between a line and the
 * next line on the same row to its right, {@code \n} otherwise (DD-43810).
 *
 * <p>Line order and content are never changed; only the separator between consecutive lines is
 * chosen. A line whose polygon is missing or incomplete always gets {@code \n} on both sides.
 */
final class LayoutAwarePageText {

    private static final int MIN_POLYGON_VALUES = 8;
    private static final double SAME_ROW_TOLERANCE_FACTOR = 0.5;

    private LayoutAwarePageText() {
    }

    static String join(final List<DocumentLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }

        final List<Box> boxes = new ArrayList<>(lines.size());
        final List<Double> heights = new ArrayList<>();
        for (final DocumentLine line : lines) {
            final Box box = Box.of(line.getPolygon());
            boxes.add(box);
            if (box != null) {
                heights.add(box.height());
            }
        }

        final double medianHeight = median(heights);
        final boolean geometryUsable = medianHeight > 0;

        final StringBuilder text = new StringBuilder(lines.get(0).getContent());
        for (int i = 1; i < lines.size(); i++) {
            final boolean sameRowToTheRight = geometryUsable
                    && isSameRowToTheRight(boxes.get(i - 1), boxes.get(i), medianHeight);
            text.append(sameRowToTheRight ? '\t' : '\n').append(lines.get(i).getContent());
        }
        return text.toString();
    }

    private static boolean isSameRowToTheRight(final Box prev, final Box next, final double medianHeight) {
        return prev != null
                && next != null
                && Math.abs(next.midY() - prev.midY()) <= SAME_ROW_TOLERANCE_FACTOR * medianHeight
                && next.minX() > prev.maxX();
    }

    /**
     * Upper-middle element of the sorted heights; {@code 0} when there are none.
     */
    private static double median(final List<Double> heights) {
        if (heights.isEmpty()) {
            return 0;
        }
        Collections.sort(heights);
        return heights.get(heights.size() / 2);
    }

    private record Box(double minX, double maxX, double minY, double maxY) {

        /**
         * Bounding box over the polygon's x,y pairs, or {@code null} when the polygon is not usable.
         */
        static Box of(final List<Double> polygon) {
            if (polygon == null || polygon.size() < MIN_POLYGON_VALUES || polygon.size() % 2 != 0) {
                return null;
            }
            double minX = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < polygon.size(); i += 2) {
                final Double x = polygon.get(i);
                final Double y = polygon.get(i + 1);
                if (x == null || y == null) {
                    return null;
                }
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
            return new Box(minX, maxX, minY, maxY);
        }

        double height() {
            return maxY - minY;
        }

        double midY() {
            return (minY + maxY) / 2;
        }
    }
}
