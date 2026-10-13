package com.macedonga.visor.core.media

import android.content.Context
import java.io.File
import kotlin.math.abs
import org.json.JSONArray

/** Where the phone was when the glasses took something: wall-clock time, degrees, metres of accuracy. */
data class Place(val at: Long, val lat: Double, val lon: Double, val accuracy: Float)

/**
 * The phone's position, noted each time the glasses capture while connected (files/places.json). An import
 * looks a capture up here by its time, since by then the phone may be somewhere else.
 */
class PlaceLog(context: Context) {
    private val file = File(context.filesDir, "places.json")
    private var places: List<Place> = read()

    @Synchronized
    fun record(place: Place) {
        places = (places + place).takeLast(MAX)
        val array = JSONArray()
        for (p in places) array.put(JSONArray().put(p.at).put(p.lat).put(p.lon).put(p.accuracy.toDouble()))
        file.writeText(array.toString())
    }

    /** The note nearest [at], if one was taken within [WINDOW_MS] of it. */
    @Synchronized
    fun near(at: Long): Place? = places.minByOrNull { abs(it.at - at) }?.takeIf { abs(it.at - at) <= WINDOW_MS }

    private fun read(): List<Place> = runCatching {
        val array = JSONArray(file.readText())
        (0 until array.length()).map { i ->
            val p = array.getJSONArray(i)
            Place(p.getLong(0), p.getDouble(1), p.getDouble(2), p.getDouble(3).toFloat())
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val MAX = 5000
        /** A video can run long after its start was noted. */
        const val WINDOW_MS = 15 * 60_000L
    }
}
