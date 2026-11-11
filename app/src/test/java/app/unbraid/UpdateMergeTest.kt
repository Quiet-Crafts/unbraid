package app.unbraid

import app.unbraid.data.Release
import app.unbraid.data.UpdateState
import app.unbraid.ui.mergeUpdates
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateMergeTest {
    @Test fun anAnswerGivenMeanwhileSurvivesAnOlderRead() {
        val read = UpdateState(enabled = true, asked = false, latest = Release(60, "2.9.0"))
        val held = UpdateState(enabled = false, asked = true)
        assertEquals(UpdateState(enabled = false, asked = true, latest = Release(60, "2.9.0")), mergeUpdates(read, held))
    }

    @Test fun theFirstReadFillsAnEmptyScreen() {
        val read = UpdateState(enabled = true, asked = true, dismissedCode = 5)
        assertEquals(read, mergeUpdates(read, UpdateState()))
    }

    @Test fun aNewerDismissalIsKept() {
        assertEquals(9, mergeUpdates(UpdateState(dismissedCode = 4), UpdateState(dismissedCode = 9)).dismissedCode)
    }
}
