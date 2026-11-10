package cz.digitalnivedomi.diarium.ui.goals

import android.content.Context
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.core.i18n.AppLanguage
import cz.digitalnivedomi.diarium.core.i18n.Lang
import cz.digitalnivedomi.diarium.ui.theme.DiariumTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Pins the goals header at a 390 dp phone width.
 *
 * The regression was a Row measurement order problem, not a string problem: the
 * unweighted subtitle measured first, the pill got the few dp that were left, and
 * "+ New goal" wrapped to two lines inside an over-tall capsule. These tests mount the
 * real screen (offline deps; the header is drawn before the error card) and measure the
 * header row and the pill. They run once in English and once in Czech because both
 * labels are longer than the space the old layout gave them.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-400dpi")
class GoalsHeaderLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun speakEnglish() {
        AppLanguage.set(context, Lang.EN)
    }

    @After
    fun speakCzechAgain() {
        AppLanguage.set(context, Lang.CS)
    }

    private fun showHeader() {
        compose.setContent {
            DiariumTheme {
                GoalsScreen(deps = GoalsDeps.offline())
            }
        }
        compose.waitForIdle()
    }

    private fun assertHeaderFits(label: String) {
        showHeader()

        // The screen's own 20 dp gutter on each side: 390 - 40 = 350 dp of content.
        val row = compose.onNodeWithTag("goals_header").getUnclippedBoundsInRoot()
        val rowWidth = (row.right - row.left).value
        assertEquals("$label header row width", 350f, rowWidth, 1f)

        val pill = compose.onNodeWithTag("goals_new_goal_pill").getUnclippedBoundsInRoot()
        val text = compose.onNodeWithTag("goals_new_goal", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val pillWidth = (pill.right - pill.left).value
        val pillHeight = (pill.bottom - pill.top).value
        val textHeight = (text.bottom - text.top).value

        // A single labelMedium line is ~16 dp; a wrapped one is ~32 dp. 24 dp is the
        // tripwire that fails only when the label actually breaks onto a second line.
        assertTrue(
            "$label pill label must stay on one line, was ${textHeight} dp tall",
            textHeight <= 24f,
        )
        assertTrue(
            "$label pill must keep its 104 dp floor, was ${pillWidth} dp",
            pillWidth >= 103f,
        )
        assertTrue(
            "$label pill must not grow into a two-line capsule, was ${pillHeight} dp",
            pillHeight <= 40f,
        )
        // And it must not eat the row: the subtitle keeps the majority of the width.
        assertTrue(
            "$label pill leaves too little room for the subtitle (${pillWidth} dp)",
            pillWidth <= rowWidth / 2f,
        )
    }

    @Test
    fun `english header keeps the new goal pill on one line`() {
        assertHeaderFits("English")
    }

    @Test
    fun `czech header keeps the new goal pill on one line`() {
        AppLanguage.set(context, Lang.CS)
        assertHeaderFits("Czech")
    }
}
