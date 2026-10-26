package io.automated.ventures.everypods

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import io.automated.ventures.everypods.data.*
import io.automated.ventures.everypods.presentation.components.*
import io.automated.ventures.everypods.utils.HeadGesturePrefs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses isolated preferences; never changes a connected user's controls. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalHazeMaterialsApi::class)
class HeadGestureControlsTest {
    @get:Rule val compose = createComposeRule()
    private fun isolatedContext(): Context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun getSharedPreferences(name: String?, mode: Int) = super.getSharedPreferences("head-gesture-ui-tests", mode)
    }.also { it.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun callTitlesRemainVisibleAndOrderedAtLargeText() {
        val context = isolatedContext()
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalDensity provides Density(1f, 2f)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        CallControlSettings(HazeState(), false, {})
                    }
                }
            }
        }
        listOf(R.string.hg_call_actions, R.string.answer_call, R.string.hg_decline_call, R.string.mute_unmute, R.string.hang_up).forEach {
            compose.onNodeWithText(context.getString(it)).performScrollTo().assertIsDisplayed()
        }
        val answer = compose.onNodeWithText(context.getString(R.string.answer_call)).fetchSemanticsNode().boundsInRoot
        val decline = compose.onNodeWithText(context.getString(R.string.hg_decline_call)).fetchSemanticsNode().boundsInRoot
        assertTrue(answer.bottom <= decline.top)
    }

    @Test fun choosingOccupiedCallGestureUpdatesBothSelectors() {
        val context = isolatedContext()
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        HeadGesturePrefs.setCall(prefs, HeadGestureCallAction.ANSWER, HeadGesture.NOD)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme { HeadGestureCallPicker(HeadGestureCallAction.DECLINE) }
            }
        }
        compose.onNodeWithContentDescription("${context.getString(R.string.hg_head_gesture)}: ${context.getString(R.string.hg_none)}").performClick()
        compose.onNodeWithText(context.getString(R.string.hg_nod)).performClick()
        compose.waitForIdle()
        assertNull(HeadGesturePrefs.read(prefs).calls[HeadGestureCallAction.ANSWER])
        assertEquals(HeadGesture.NOD, HeadGesturePrefs.read(prefs).calls[HeadGestureCallAction.DECLINE])
        compose.onNodeWithContentDescription("${context.getString(R.string.hg_head_gesture)}: ${context.getString(R.string.hg_nod)}").assertExists()
    }
    @Test fun announcementToggleIsVisibleAndPersists() {
        val context = isolatedContext()
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { HeadGestureActions() }
                }
            }
        }
        assertTrue(HeadGesturePrefs.read(prefs).shakeStopsAnnouncements)
        compose.onNodeWithText(context.getString(R.string.hg_stop_announcements)).performScrollTo().performClick()
        compose.waitForIdle()
        assertFalse(HeadGesturePrefs.read(prefs).shakeStopsAnnouncements)
    }

}
