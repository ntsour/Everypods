package io.automated.ventures.everypods.presentation.screens

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.Arrangement
import io.automated.ventures.everypods.BuildConfig
import io.automated.ventures.everypods.R
import io.automated.ventures.everypods.automation.GymMapMath
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.MapTileProviderBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.io.File

/**
 * OpenStreetMap picker for the gym zone centre. Only composed when the user opens it, so
 * no tile request happens otherwise. Needs no location permission and never sends the
 * picked or saved point anywhere: tile servers only see which tiles are viewed.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun GymMapPicker(
    initialLat: Double?,
    initialLon: Double?,
    radiusMeters: Int,
    onPick: (Double, Double) -> Unit,
) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf(if (initialLat != null && initialLon != null) GeoPoint(initialLat, initialLon) else null) }
    var tilesFailed by remember { mutableStateOf(false) }
    val mapView = remember { configureOsmdroid(context); MapView(context) }
    val marker = remember { Marker(mapView).apply { setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); setInfoWindow(null) } }
    val circle = remember { Polygon(mapView).apply {
        fillPaint.color = 0x330A84FF; outlinePaint.color = 0xFF0A84FF.toInt(); outlinePaint.strokeWidth = 4f; setInfoWindow(null)
    } }

    fun redraw() {
        val p = pin
        mapView.overlays.remove(marker); mapView.overlays.remove(circle)
        if (p != null) {
            marker.position = p
            circle.points = Polygon.pointsAsCircle(p, radiusMeters.toDouble())
            mapView.overlays.add(circle); mapView.overlays.add(marker)
        }
        mapView.invalidate()
    }

    DisposableEffect(mapView) {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
        mapView.minZoomLevel = GymMapMath.MIN_ZOOM; mapView.maxZoomLevel = GymMapMath.MAX_ZOOM
        mapView.isVerticalMapRepetitionEnabled = false
        mapView.overlays.add(CopyrightOverlay(context))
        mapView.overlays.add(0, MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { pin = GeoPoint(p.latitude, GymMapMath.wrapLongitude(p.longitude)); redraw(); return true }
            override fun longPressHelper(p: GeoPoint) = singleTapConfirmedHelper(p)
        }))
        // Report tile failures (offline, server refusal) so the user knows to use other inputs.
        mapView.tileProvider.tileRequestCompleteHandlers.add(Handler(Looper.getMainLooper()) { msg ->
            if (msg.what == MapTileProviderBase.MAPTILE_FAIL_ID) tilesFailed = true
            else if (msg.what == MapTileProviderBase.MAPTILE_SUCCESS_ID) tilesFailed = false
            false
        })
        // Keep the parent scroll from stealing pans/pinches.
        mapView.setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }
        val start = pin
        mapView.post {
            val side = minOf(mapView.width, mapView.height).takeIf { it > 0 } ?: 800
            if (start != null) {
                mapView.controller.setZoom(GymMapMath.zoomForRadius(radiusMeters, start.latitude, side, context.resources.displayMetrics.density))
                mapView.controller.setCenter(start)
            } else {
                mapView.controller.setZoom(GymMapMath.MIN_ZOOM)
                mapView.controller.setCenter(GeoPoint(30.0, 0.0))
            }
        }
        mapView.onResume()
        onDispose { mapView.onPause(); mapView.onDetach() }
    }

    // Keep the pin in sync when the zone changes elsewhere (current location, typed coordinates).
    LaunchedEffect(initialLat, initialLon) {
        if (initialLat != null && initialLon != null && (pin?.latitude != initialLat || pin?.longitude != initialLon)) {
            pin = GeoPoint(initialLat, initialLon)
            mapView.controller.animateTo(pin)
        }
    }

    Column {
        Text(stringResource(R.string.gym_map_hint), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        AndroidView(factory = { mapView }, update = { redraw() },
            modifier = Modifier.fillMaxWidth().height(320.dp).padding(horizontal = 16.dp))
        if (tilesFailed) Text(stringResource(R.string.gym_map_offline), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val c = mapView.mapCenter
                pin = GeoPoint(GymMapMath.clampLatitude(c.latitude), GymMapMath.wrapLongitude(c.longitude)); redraw()
            }) { Text(stringResource(R.string.gym_map_use_center)) }
            Button(enabled = pin != null, onClick = {
                pin?.let { val (lat, lon) = GymMapMath.normalize(it.latitude, it.longitude); onPick(lat, lon) }
            }) { Text(stringResource(R.string.gym_map_save)) }
        }
    }
}

/** OSM tile policy: identify the app, cache tiles; keep everything in app-private cache. */
private fun configureOsmdroid(ctx: Context) {
    val c = Configuration.getInstance()
    c.userAgentValue = "EveryPods/${BuildConfig.VERSION_NAME} (Android; ${ctx.packageName})"
    val base = File(ctx.cacheDir, "osmdroid")
    c.osmdroidBasePath = base
    c.osmdroidTileCache = File(base, "tiles")
    c.tileFileSystemCacheMaxBytes = 50L * 1024 * 1024
    c.tileFileSystemCacheTrimBytes = 40L * 1024 * 1024
}
