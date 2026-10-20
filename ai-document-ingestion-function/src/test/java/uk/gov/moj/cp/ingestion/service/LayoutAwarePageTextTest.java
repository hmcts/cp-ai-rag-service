package uk.gov.moj.cp.ingestion.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.azure.ai.documentintelligence.models.DocumentLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DD-43810: {@link LayoutAwarePageText#join(List, Double)} keeps Document Intelligence's line order and only
 * chooses the separator between consecutive lines from their polygons: {@code \t} when the next line
 * is on the same row (|midY diff| <= 0.5 x page median line height) and strictly to the right
 * (next.minX > prev.maxX), otherwise {@code \n}.
 *
 * <p>Coordinates are inch-scale (Document Intelligence PDF units) with a line height of 0.12.
 */
class LayoutAwarePageTextTest {

    private static final double LINE_HEIGHT = 0.12;

    // Two-column layout: left column x 0.5..3.0, right column x 4.0..5.5.
    private static final double LEFT_MIN_X = 0.5;
    private static final double LEFT_MAX_X = 3.0;
    private static final double RIGHT_MIN_X = 4.0;
    private static final double RIGHT_MAX_X = 5.5;

    private static final double ROW_1_TOP = 1.00;
    private static final double ROW_2_TOP = 1.20;
    private static final double ROW_3_TOP = 1.40;

    // ---- AC-2 ---------------------------------------------------------------------------------

    @Test
    @DisplayName("AC-2: a next line on the same row and to the right is joined with a tab")
    void shouldTabJoinSameRowLineToTheRight() {
        final DocumentLine left = line("1. REPLACE THE MAIN WATER PUMP AND", box(LEFT_MIN_X, ROW_1_TOP, LEFT_MAX_X, ROW_1_TOP + LINE_HEIGHT));
        final DocumentLine right = line("COST 120.00", box(RIGHT_MIN_X, ROW_1_TOP + 0.01, RIGHT_MAX_X, ROW_1_TOP + 0.01 + LINE_HEIGHT));

        assertEquals("1. REPLACE THE MAIN WATER PUMP AND\tCOST 120.00", LayoutAwarePageText.join(List.of(left, right), null));
    }

    @Test
    @DisplayName("AC-2: a small baseline drift within half the median line height still counts as the same row")
    void shouldTabJoinWhenMidYDriftWithinHalfMedianLineHeight() {
        // median height 0.12 -> tolerance 0.06; drift of 0.05 is within it
        final DocumentLine left = line("DESCRIPTION TEXT ON THE LEFT", box(LEFT_MIN_X, ROW_1_TOP, LEFT_MAX_X, ROW_1_TOP + LINE_HEIGHT));
        final DocumentLine right = line("DETAIL ON THE RIGHT", box(RIGHT_MIN_X, ROW_1_TOP + 0.05, RIGHT_MAX_X, ROW_1_TOP + 0.05 + LINE_HEIGHT));

        assertEquals("DESCRIPTION TEXT ON THE LEFT\tDETAIL ON THE RIGHT", LayoutAwarePageText.join(List.of(left, right), null));
    }

    // ---- AC-3 ---------------------------------------------------------------------------------

    @Test
    @DisplayName("AC-3: a line that starts a new row is preceded by a newline")
    void shouldNewlineWhenNewRow() {
        final DocumentLine first = line("FIRST ROW OF TEXT", box(LEFT_MIN_X, ROW_1_TOP, LEFT_MAX_X, ROW_1_TOP + LINE_HEIGHT));
        final DocumentLine belowLeft = line("SECOND ROW OF TEXT", box(LEFT_MIN_X, ROW_2_TOP, LEFT_MAX_X, ROW_2_TOP + LINE_HEIGHT));
        // To the right of the previous line, but on a new row: still a newline.
        final DocumentLine belowRight = line("THIRD ROW, RIGHT COLUMN", box(RIGHT_MIN_X, ROW_3_TOP, RIGHT_MAX_X, ROW_3_TOP + LINE_HEIGHT));

        assertEquals("FIRST ROW OF TEXT\nSECOND ROW OF TEXT\nTHIRD ROW, RIGHT COLUMN",
                LayoutAwarePageText.join(List.of(first, belowLeft, belowRight), null));
    }

    @Test
    @DisplayName("AC-3: a to-the-right line whose midY drift exceeds half the median line height is a new row")
    void shouldNewlineWhenMidYDriftExceedsHalfMedianLineHeight() {
        // median height 0.12 -> tolerance 0.06; drift of 0.08 exceeds it
        final DocumentLine left = line("LEFT COLUMN TEXT", box(LEFT_MIN_X, ROW_1_TOP, LEFT_MAX_X, ROW_1_TOP + LINE_HEIGHT));
        final DocumentLine right = line("RIGHT COLUMN TEXT", box(RIGHT_MIN_X, ROW_1_TOP + 0.08, RIGHT_MAX_X, ROW_1_TOP + 0.08 + LINE_HEIGHT));

        assertEquals("LEFT COLUMN TEXT\nRIGHT COLUMN TEXT", LayoutAwarePageText.join(List.of(left, right), null));
    }

    @Test
    @DisplayName("AC-3: a same-row line that is left of, overlaps or touches the previous line is preceded by a newline")
    void shouldNewlineWhenSameRowButLeftOfOrOverlapping() {
        final double top = ROW_1_TOP;
        final double bottom = ROW_1_TOP + LINE_HEIGHT;

        // left of: previous line is in the right column, next line in the left column
        final DocumentLine rightFirst = line("RIGHT COLUMN FIRST", box(RIGHT_MIN_X, top, RIGHT_MAX_X, bottom));
        final DocumentLine leftSecond = line("LEFT COLUMN SECOND", box(LEFT_MIN_X, top, LEFT_MAX_X, bottom));
        assertEquals("RIGHT COLUMN FIRST\nLEFT COLUMN SECOND", LayoutAwarePageText.join(List.of(rightFirst, leftSecond), null));

        // overlapping: next.minX (2.5) < prev.maxX (3.0)
        final DocumentLine prev = line("PREVIOUS LINE", box(LEFT_MIN_X, top, LEFT_MAX_X, bottom));
        final DocumentLine overlapping = line("OVERLAPPING LINE", box(2.5, top, 4.5, bottom));
        assertEquals("PREVIOUS LINE\nOVERLAPPING LINE", LayoutAwarePageText.join(List.of(prev, overlapping), null));

        // touching: next.minX == prev.maxX is not strictly to the right
        final DocumentLine touching = line("TOUCHING LINE", box(LEFT_MAX_X, top, 4.5, bottom));
        assertEquals("PREVIOUS LINE\nTOUCHING LINE", LayoutAwarePageText.join(List.of(prev, touching), null));
    }

    // ---- AC-4 ---------------------------------------------------------------------------------

    @Test
    @DisplayName("AC-4: a null or incomplete polygon on either side gives a newline, without throwing")
    void shouldNewlineWhenPolygonNullOrFewerThanEightValues() {
        final List<Double> usableLeft = box(LEFT_MIN_X, ROW_1_TOP, LEFT_MAX_X, ROW_1_TOP + LINE_HEIGHT);
        final List<Double> usableRight = box(RIGHT_MIN_X, ROW_1_TOP, RIGHT_MAX_X, ROW_1_TOP + LINE_HEIGHT);
        // Same-row-to-the-right geometry, truncated to 6 values (3 points)
        final List<Double> sixValues = usableRight.subList(0, 6);
        // Odd number of values is not a set of x,y pairs
        final List<Double> nineValues = new ArrayList<>(usableRight);
        nineValues.add(ROW_1_TOP);

        // next side unusable
        assertJoinedWithNewline(line("LEFT", usableLeft), line("RIGHT", null));
        assertJoinedWithNewline(line("LEFT", usableLeft), line("RIGHT", sixValues));
        assertJoinedWithNewline(line("LEFT", usableLeft), line("RIGHT", nineValues));
        assertJoinedWithNewline(line("LEFT", usableLeft), line("RIGHT", List.of()));

        // previous side unusable
        assertJoinedWithNewline(line("LEFT", null), line("RIGHT", usableRight));
        assertJoinedWithNewline(line("LEFT", usableLeft.subList(0, 6)), line("RIGHT", usableRight));
        assertJoinedWithNewline(line("LEFT", List.of()), line("RIGHT", usableRight));
    }

    @Test
    @DisplayName("AC-4: when no line on the page has a usable polygon, every separator is a newline")
    void shouldNewlineEverywhereWhenNoUsablePolygonOnPage() {
        final List<DocumentLine> lines = List.of(
                line("FIRST", null),
                line("SECOND", List.of(1.0, 1.0, 2.0, 1.0)),
                line("THIRD", null));

        assertEquals("FIRST\nSECOND\nTHIRD", assertDoesNotThrow(() -> LayoutAwarePageText.join(lines, null)));
    }

    @Test
    @DisplayName("AC-4: when the page median line height is zero, every separator is a newline")
    void shouldNewlineEverywhereWhenMedianLineHeightIsZero() {
        // Degenerate (zero-height) boxes that would otherwise be same-row-to-the-right
        final DocumentLine left = line("LEFT", box(LEFT_MIN_X, ROW_1_TOP, LEFT_MAX_X, ROW_1_TOP));
        final DocumentLine right = line("RIGHT", box(RIGHT_MIN_X, ROW_1_TOP, RIGHT_MAX_X, ROW_1_TOP));

        assertEquals("LEFT\nRIGHT", assertDoesNotThrow(() -> LayoutAwarePageText.join(List.of(left, right), null)));
    }

    @Test
    @DisplayName("AC-4: an empty line list gives an empty string")
    void shouldReturnEmptyStringWhenNoLines() {
        assertEquals("", LayoutAwarePageText.join(List.of(), null));
    }

    @Test
    @DisplayName("AC-4: a single line gives its text unchanged")
    void shouldReturnLineTextWhenSingleLine() {
        assertEquals("ONLY LINE ON THE PAGE",
                LayoutAwarePageText.join(List.of(line("ONLY LINE ON THE PAGE", null)), null));
        assertEquals("ONLY LINE ON THE PAGE",
                LayoutAwarePageText.join(List.of(line("ONLY LINE ON THE PAGE",
                        box(LEFT_MIN_X, ROW_1_TOP, LEFT_MAX_X, ROW_1_TOP + LINE_HEIGHT))), null));
    }

    @Test
    @DisplayName("AC-4: a non-finite or null coordinate makes the polygon unusable, giving a newline without throwing")
    void shouldNewlineWhenPolygonHasNullOrNonFiniteCoordinate() {
        final List<Double> usableLeft = box(LEFT_MIN_X, ROW_1_TOP, LEFT_MAX_X, ROW_1_TOP + LINE_HEIGHT);
        final List<Double> usableRight = box(RIGHT_MIN_X, ROW_1_TOP, RIGHT_MAX_X, ROW_1_TOP + LINE_HEIGHT);

        for (final Double bad : Arrays.asList(null, Double.NaN, Double.POSITIVE_INFINITY)) {
            // next side unusable (bad maxX)
            final List<Double> badRight = new ArrayList<>(usableRight);
            badRight.set(2, bad);
            assertJoinedWithNewline(line("LEFT", usableLeft), line("RIGHT", badRight));

            // previous side unusable (bad maxY)
            final List<Double> badLeft = new ArrayList<>(usableLeft);
            badLeft.set(5, bad);
            assertJoinedWithNewline(line("LEFT", badLeft), line("RIGHT", usableRight));
        }
    }

    // ---- page angle ---------------------------------------------------------------------------

    @Test
    @DisplayName("Rotated page (|angle| > 45 degrees): same-row-to-the-right geometry still gives a newline")
    void shouldNewlineWhenPageIsRotated() {
        final List<DocumentLine> lines = sameRowToTheRightLines();

        assertEquals("LEFT COLUMN TEXT\nRIGHT COLUMN TEXT", LayoutAwarePageText.join(lines, -91.0));
        assertEquals("LEFT COLUMN TEXT\nRIGHT COLUMN TEXT", LayoutAwarePageText.join(lines, 90.8));
        assertEquals("LEFT COLUMN TEXT\nRIGHT COLUMN TEXT", LayoutAwarePageText.join(lines, 179.7));
    }

    @Test
    @DisplayName("Upright or slightly skewed page (|angle| <= 45 degrees), or unknown angle: geometry is used")
    void shouldTabJoinWhenPageIsUprightOrAngleUnknown() {
        final List<DocumentLine> lines = sameRowToTheRightLines();

        assertEquals("LEFT COLUMN TEXT\tRIGHT COLUMN TEXT", LayoutAwarePageText.join(lines, 3.0));
        assertEquals("LEFT COLUMN TEXT\tRIGHT COLUMN TEXT", LayoutAwarePageText.join(lines, -2.5));
        // 359 degrees normalises to -1 degree: upright
        assertEquals("LEFT COLUMN TEXT\tRIGHT COLUMN TEXT", LayoutAwarePageText.join(lines, 359.0));
        assertEquals("LEFT COLUMN TEXT\tRIGHT COLUMN TEXT", LayoutAwarePageText.join(lines, null));
    }

    // ---- AC-5 ---------------------------------------------------------------------------------

    @Test
    @DisplayName("AC-5: normalising tabs and newlines to spaces gives the space-joined text (no reorder, no loss)")
    void shouldPreserveOrderAndTextWhenWhitespaceNormalised() {
        final List<DocumentLine> lines = List.of(
                line("SCHEDULE OF WORKS", box(LEFT_MIN_X, 0.50, RIGHT_MAX_X, 0.50 + LINE_HEIGHT)),
                line("1. REPLACE THE MAIN WATER PUMP AND", box(LEFT_MIN_X, ROW_1_TOP, LEFT_MAX_X, ROW_1_TOP + LINE_HEIGHT)),
                line("COST 120.00", box(RIGHT_MIN_X, ROW_1_TOP, RIGHT_MAX_X, ROW_1_TOP + LINE_HEIGHT)),
                line("ASSOCIATED PIPEWORK IN THE NORTH", box(LEFT_MIN_X, ROW_2_TOP, LEFT_MAX_X, ROW_2_TOP + LINE_HEIGHT)),
                line("COMPLETED IN 5 DAYS", box(RIGHT_MIN_X, ROW_2_TOP, RIGHT_MAX_X, ROW_2_TOP + LINE_HEIGHT)),
                line("LINE WITH NO GEOMETRY", null),
                line("12 MONTHS", box(RIGHT_MIN_X, ROW_3_TOP, RIGHT_MAX_X, ROW_3_TOP + LINE_HEIGHT)),
                line("  padded content kept verbatim  ", box(LEFT_MIN_X, 1.60, LEFT_MAX_X, 1.60 + LINE_HEIGHT)));

        final String expectedSpaceJoined = String.join(" ", lines.stream().map(DocumentLine::getContent).toList());

        final String joined = LayoutAwarePageText.join(lines, null);

        assertEquals(expectedSpaceJoined, joined.replaceAll("[\t\n]", " "));
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static void assertJoinedWithNewline(final DocumentLine prev, final DocumentLine next) {
        final String joined = assertDoesNotThrow(() -> LayoutAwarePageText.join(List.of(prev, next), null));
        assertEquals(prev.getContent() + "\n" + next.getContent(), joined);
    }

    private static List<DocumentLine> sameRowToTheRightLines() {
        return List.of(
                line("LEFT COLUMN TEXT", box(LEFT_MIN_X, ROW_1_TOP, LEFT_MAX_X, ROW_1_TOP + LINE_HEIGHT)),
                line("RIGHT COLUMN TEXT", box(RIGHT_MIN_X, ROW_1_TOP, RIGHT_MAX_X, ROW_1_TOP + LINE_HEIGHT)));
    }

    private static DocumentLine line(final String content, final List<Double> polygon) {
        final DocumentLine line = mock(DocumentLine.class);
        when(line.getContent()).thenReturn(content);
        when(line.getPolygon()).thenReturn(polygon);
        return line;
    }

    /**
     * Axis-aligned 4-point polygon as Document Intelligence returns it: [x0,y0, x1,y0, x1,y1, x0,y1].
     */
    private static List<Double> box(final double x0, final double y0, final double x1, final double y1) {
        return Arrays.asList(x0, y0, x1, y0, x1, y1, x0, y1);
    }
}
