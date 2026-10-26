package io.automated.ventures.everypods.presentation.screens

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.graphics.Outline
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.automated.ventures.everypods.BuildConfig
import io.automated.ventures.everypods.R
import io.automated.ventures.everypods.automation.GymMapMath
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.MapTileProviderBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.io.File

/** A programmatic "move the map here" request; [key] makes repeated requests distinct. */
data class GymMapMove(val latitude: Double, val longitude: Double, val persist: Boolean = true, val key: Long = System.nanoTime())

/**
 * OpenStreetMap editor for one gym zone. The pin is fixed at the map centre: panning the
 * map moves the zone. [onCenterChanged] reports live (for the coordinates text) and
 * [onCenterSettled] fires once the map has been still for [SETTLE_MS], which is when the
 * caller persists and re-registers geofences. Only composed while a zone is being edited,
 * so no tile request happens otherwise. Needs no location permission; tile servers only
 * see which tiles are viewed, never the saved zone.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun GymMapPicker(
    initialLat: Double,
    initialLon: Double,
    radiusMeters: Int,
    move: GymMapMove?,
    startIsZone: Boolean,
    onCenterChanged: (Double, Double) -> Unit,
    onCenterSettled: (Double, Double) -> Unit,
) {
    val context = LocalContext.current
    var tilesFailed by remember { mutableStateOf(false) }
    val changed by rememberUpdatedState(onCenterChanged)
    val settled by rememberUpdatedState(onCenterSettled)
    val radius by rememberUpdatedState(radiusMeters)
    val mapView = remember { configureOsmdroid(context); MapView(context) }
    val marker = remember { Marker(mapView).apply { setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); setInfoWindow(null) } }
    val circle = remember { Polygon(mapView).apply {
        fillPaint.color = 0x330A84FF; outlinePaint.color = 0xFF0A84FF.toInt(); outlinePaint.strokeWidth = 4f; setInfoWindow(null)
    } }
    val handler = remember { Handler(Looper.getMainLooper()) }
    // Only user gestures and explicit move requests may change the zone; the initial
    // camera placement must never persist anything (e.g. a new zone without a position).
    val armed = remember { booleanArrayOf(false) }

    fun center(): GeoPoint {
        val c = mapView.mapCenter
        return GeoPoint(GymMapMath.clampLatitude(c.latitude), GymMapMath.wrapLongitude(c.longitude))
    }
    fun redraw() {
        val p = center()
        marker.position = p
        circle.points = Polygon.pointsAsCircle(p, radius.toDouble())
        if (!mapView.overlays.contains(circle)) mapView.overlays.add(circle)
        if (!mapView.overlays.contains(marker)) mapView.overlays.add(marker)
        mapView.invalidate()
    }
    val settle = remember { Runnable {
        if (armed[0]) { val (lat, lon) = center().let { GymMapMath.normalize(it.latitude, it.longitude) }; settled(lat, lon) }
    } }

    DisposableEffect(mapView) {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        val radiusPx = MAP_CORNER_DP * context.resources.displayMetrics.density
        mapView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
        }
        mapView.clipToOutline = true
        mapView.clipChildren = true
        mapView.setMultiTouchControls(true)
        mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
        mapView.minZoomLevel = GymMapMath.MIN_ZOOM; mapView.maxZoomLevel = GymMapMath.MAX_ZOOM
        mapView.isVerticalMapRepetitionEnabled = false
        // Never pan past the Web Mercator edge (a hard fling used to reach Antarctica).
        mapView.setScrollableAreaLimitLatitude(GymMapMath.MAX_LAT, -GymMapMath.MAX_LAT, 0)
        mapView.overlays.add(CopyrightOverlay(context))
        mapView.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                redraw()
                if (armed[0]) {
                    val c = center(); changed(c.latitude, c.longitude)
                    handler.removeCallbacks(settle); handler.postDelayed(settle, SETTLE_MS)
                }
                return false
            }
            override fun onZoom(event: ZoomEvent?): Boolean { redraw(); return false }
        })
        // Report tile failures (offline, server refusal) so the user knows to use other inputs.
        mapView.tileProvider.tileRequestCompleteHandlers.add(Handler(Looper.getMainLooper()) { msg ->
            if (msg.what == MapTileProviderBase.MAPTILE_FAIL_ID) tilesFailed = true
            else if (msg.what == MapTileProviderBase.MAPTILE_SUCCESS_ID) tilesFailed = false
            false
        })
        // Keep the parent scroll from stealing pans/pinches; a touch arms persistence.
        // Only a real drag arms persistence: a tap (or a touch left over from opening the
        // editor) must never save the camera position.
        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        val down = floatArrayOf(0f, 0f)
        mapView.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { down[0] = e.x; down[1] = e.y; v.parent?.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_MOVE -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    if (e.pointerCount > 1 || Math.hypot((e.x - down[0]).toDouble(), (e.y - down[1]).toDouble()) > slop) armed[0] = true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        mapView.post {
            val side = minOf(mapView.width, mapView.height).takeIf { it > 0 } ?: 800
            mapView.controller.setZoom(if (startIsZone) GymMapMath.zoomForRadius(radius, initialLat, side, context.resources.displayMetrics.density) else 13.0)
            mapView.controller.setCenter(GeoPoint(GymMapMath.clampLatitude(initialLat), initialLon))
            redraw()
        }
        mapView.onResume()
        onDispose {
            // Flush a pending settle so a pan right before leaving is not lost.
            if (handler.hasCallbacks(settle)) { handler.removeCallbacks(settle); settle.run() }
            mapView.onPause(); mapView.onDetach()
        }
    }

    // Explicit moves (current location, typed coordinates) persist like a pan.
    LaunchedEffect(move) {
        val m = move ?: return@LaunchedEffect
        if (m.persist) armed[0] = true else handler.removeCallbacks(settle)
        val side = minOf(mapView.width, mapView.height).takeIf { it > 0 } ?: 800
        if (mapView.zoomLevelDouble < 12.0) {
            mapView.controller.setZoom(GymMapMath.zoomForRadius(radius, m.latitude, side, context.resources.displayMetrics.density))
        }
        mapView.controller.setCenter(GeoPoint(GymMapMath.clampLatitude(m.latitude), m.longitude))
    }

    Column {
        Text(stringResource(R.string.gym_map_pin_hint), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        // osmdroid pans by scrolling its own canvas; Compose draws the View without clipping
        // to its bounds, so tiles leaked over neighbouring content. Clip the container to the
        // rounded shape (Compose side) and the MapView to its outline (View side).
        val shape = RoundedCornerShape(MAP_CORNER_DP.dp)
        Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(320.dp)
            .graphicsLayer { this.shape = shape; clip = true }.clip(shape)) {
            AndroidView(factory = { mapView }, update = { redraw() }, modifier = Modifier.fillMaxSize())
        }
        if (tilesFailed) Text(stringResource(R.string.gym_map_offline), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

private const val SETTLE_MS = 700L
private const val MAP_CORNER_DP = 12

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
