package io.automated.ventures.everypods

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.automated.ventures.everypods.presentation.screens.searchIndex
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies every entry in the search index navigates to a visible destination.
 *
 * For each SearchableItem the test:
 *   1. Opens the search bar.
 *   2. Types the item label.
 *   3. Clicks the first result.
 *   4. Asserts the destination testTag is present.
 *   5. If the item has an anchor, asserts the anchor testTag is visible.
 *   6. Presses back to return to the settings screen.
 *
 * Run with: ./gradlew :app:connectedNormalDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class SearchIndexNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun every_search_item_navigates_to_a_visible_destination() {
        searchIndex.forEach { item ->
            // Open search
            composeRule
                .onNodeWithTag("nav_settings_search_button")
                .performClick()

            // Type label
            composeRule
                .onNodeWithTag("search_input")
                .performTextReplacement(item.label)

            // Click first result
            composeRule
                .onNodeWithText(item.label)
                .performClick()

            // Assert destination root is present
            val destTag = item.directRoute.replace("/", "/")
            composeRule
                .onNodeWithTag("dest_${item.directRoute}")
                .assertIsDisplayed()

            // Assert anchor section is visible when applicable
            item.anchor?.let { a ->
                composeRule
                    .onNodeWithTag("anchor_$a")
                    .assertIsDisplayed()
            }

            // Navigate back to settings screen
            composeRule.activityRule.scenario.onActivity {
                it.onBackPressedDispatcher.onBackPressed()
            }
            composeRule.waitForIdle()
        }
    }
}
