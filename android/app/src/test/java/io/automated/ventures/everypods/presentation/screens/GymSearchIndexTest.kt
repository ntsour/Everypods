/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.presentation.screens

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import io.automated.ventures.everypods.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GymSearchIndexTest {
    private fun ctx(lang: String): Context {
        val base: Context = ApplicationProvider.getApplicationContext()
        val c = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(lang)) }
        return base.createConfigurationContext(c)
    }
    private fun hits(lang: String, q: String) = rankSearchResults(searchIndex(ctx(lang)), q).filter { it.directRoute == "gym_automation" }

    @Test fun gymSubSettingsAreFoundInEnglishSpanishAndGreek() {
        mapOf(
            "en" to listOf("gym", "radius", "map", "add location", "remove location", "geofence"),
            "es" to listOf("gimnasio", "radio", "mapa", "añadir ubicación", "eliminar"),
            "el" to listOf("γυμναστήριο", "ακτίνα", "χάρτης", "προσθήκη τοποθεσίας", "αφαίρεση"),
        ).forEach { (lang, terms) ->
            terms.forEach { t -> assertTrue("$lang '$t' finds gym automation", hits(lang, t).isNotEmpty()) }
        }
        // English terms keep working in another locale (merged English index).
        assertTrue(hits("el", "radius").isNotEmpty())
        // Accent-insensitive.
        assertTrue(hits("el", "ακτινα").isNotEmpty())
    }

    @Test fun gymLabelsAreLocalizedThroughResources() {
        listOf("en", "es", "el", "de", "fr", "it", "pt", "tr", "uk", "vi", "zh-CN", "zh-TW").forEach { lang ->
            val c = ctx(lang)
            val labels = searchIndex(c).filter { it.directRoute == "gym_automation" }.map { it.label }.toSet()
            listOf(R.string.gym_auto_title, R.string.gym_zones_title, R.string.gym_zone_add, R.string.gym_zone_edit,
                R.string.gym_zone_remove, R.string.gym_auto_radius_title, R.string.gym_map_search_title).forEach {
                assertTrue("$lang has ${c.getString(it)}", c.getString(it) in labels)
            }
            if (lang != "en") assertTrue("$lang translated", c.getString(R.string.gym_zones_title) != ctx("en").getString(R.string.gym_zones_title))
        }
    }
}
