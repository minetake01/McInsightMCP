package net.minetake.mcinsight.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * The one amount rule every viewer adapter feeds: a viewer's positive number wins, the stack's own
 * count answers otherwise, and an unknown amount is omitted rather than guessed.
 */
class ViewerModelAmountTest {

    @Test
    void viewerReportedAmountWins() {
        assertEquals(4L, ViewerModel.amount(4L, 64L));
    }

    @Test
    void stackCountAnswersWhenTheViewerReportsNone() {
        assertEquals(16L, ViewerModel.amount(0L, 16L));
        assertEquals(3L, ViewerModel.amount(-1L, 3L));
    }

    @Test
    void unknownAmountIsOmitted() {
        assertNull(ViewerModel.amount(0L, 0L));
        assertNull(ViewerModel.amount(-1L, -1L));
    }
}
