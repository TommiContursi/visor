package com.macedonga.visor.gallery

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.media.Capture
import com.macedonga.visor.core.media.CaptureType
import com.macedonga.visor.core.media.Kept
import com.macedonga.visor.core.media.MediaImport
import com.macedonga.visor.core.media.Transfer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.stateIn

/** One photo, live photo or video in the timeline, wherever it is: on this phone, on the glasses, or both. */
@Immutable
data class GalleryItem(
    /** The capture's handle when it is known, else the phone copy's uri. */
    val key: String,
    val type: CaptureType,
    val takenAt: Long,
    val phone: PhoneItem?,
    val capture: Capture?,
    val kept: Kept?,
) {
    val onGlassesOnly: Boolean get() = phone == null
    val date: LocalDate get() = Instant.ofEpochMilli(takenAt).atZone(ZoneId.systemDefault()).toLocalDate()
    val favorite: Boolean get() = phone?.favorite == true
}

@Immutable
data class Day(val date: LocalDate, val items: List<GalleryItem>)

@HiltViewModel
class GalleryModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val media: MediaImport,
    val library: PhoneLibrary,
    val connection: GlassesConnection,
) : ViewModel() {

    private val merged = combine(
        library.items, library.leaving, media.index.entries, media.index.dismissed, media.captures,
    ) { phone, leaving, kept, dismissed, captures -> merge(phone, leaving, kept, dismissed, captures) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Merged(emptyList(), emptyList()))

    val items: StateFlow<List<GalleryItem>> = merged.map { it.items }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        // Old clips of live photos go, quietly.
        viewModelScope.launch { merged.collect { if (it.superseded.isNotEmpty()) library.remove(it.superseded) } }
    }

    val previews: StateFlow<Map<String, File>> = media.previews
    val inFlight: StateFlow<Set<String>> = media.inFlight
    val transfers: StateFlow<List<Transfer>> = media.transfers
    val importing: StateFlow<Boolean> = media.importing
    val problem: StateFlow<com.macedonga.visor.core.media.ImportProblem?> = media.problem
    val overBluetooth: StateFlow<Boolean> = media.overBluetooth

    fun import(items: Collection<GalleryItem>) = media.import(items.filter { it.onGlassesOnly }.mapNotNull { it.capture })

    fun delete(items: Collection<GalleryItem>) {
        val handles = items.filter { it.phone != null }.associate { it.phone!!.uri to it.capture?.handle }
        library.delete(handles.keys) { uri -> handles[uri]?.let(media.index::dismiss) }
    }

    fun undoDelete() = library.undo()

    fun favorite(items: Collection<GalleryItem>, on: Boolean) = library.setFavorite(items.mapNotNull { it.phone?.uri }, on)

    /** The system's share sheet for what is on the phone among [items]. */
    fun shareIntent(items: Collection<GalleryItem>): Intent? {
        val phone = items.mapNotNull { it.phone }
        if (phone.isEmpty()) return null
        val type = when {
            phone.all { it.video } -> "video/*"
            phone.none { it.video } -> "image/*"
            else -> "*/*"
        }
        val send = if (phone.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, phone[0].uri)
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(phone.map { it.uri }))
        }
        send.type = type
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null)
    }

    /** The timeline, and the copies to drop: old clips of live photos, whole or not. */
    class Merged(val items: List<GalleryItem>, val superseded: List<Uri>)

    companion object {
        /**
         * The phone's copies, each matched to its capture by the index or, for what earlier versions saved, by the
         * capture's time to within a few seconds (a video's MediaStore time comes from its MP4, to the second);
         * then what is only on the glasses. A deleted capture's copy on the glasses stays out.
         */
        fun merge(
            phone: List<PhoneItem>,
            leaving: Set<Uri>,
            kept: Map<String, Kept>,
            dismissed: Set<String>,
            captures: List<Capture>,
        ): Merged {
            val media = captures.filter { it.isMedia }
            val byHandle = media.associateBy { it.handle }
            val timed = media.filter { it.takenAtMillis != null }.sortedBy { it.takenAtMillis }
            val keptByUri = kept.values.associateBy { it.uri }
            val shown = HashSet<String>()
            val keys = HashSet<String>()
            val out = ArrayList<GalleryItem>(phone.size + media.size)
            val superseded = ArrayList<Uri>()
            for (p in phone) {
                val k = keptByUri[p.uri.toString()]
                val capture = k?.let { byHandle[it.handle] } ?: nearest(timed, p.takenAt)
                val handle = k?.handle ?: capture?.handle
                // A live photo an earlier version saved as its clip alone is no video: it goes.
                if (k == null && p.video && capture?.type == CaptureType.LIVE) {
                    superseded += p.uri
                    continue
                }
                handle?.let(shown::add)
                if (p.uri in leaving) continue
                val type = when {
                    k != null -> k.type
                    p.video -> CaptureType.VIDEO
                    else -> CaptureType.PHOTO
                }
                val key = handle?.takeIf { keys.add(it) } ?: p.uri.toString().also(keys::add)
                out += GalleryItem(key, type, p.takenAt, p, capture, k)
            }
            for (c in media) {
                if (c.handle in shown || c.handle in dismissed || !keys.add(c.handle)) continue
                out += GalleryItem(c.handle, c.type, c.takenAtMillis ?: 0, null, c, kept[c.handle])
            }
            return Merged(out.sortedByDescending { it.takenAt }, superseded)
        }

        /** The capture taken nearest [at], within [MATCH_MS]. */
        private fun nearest(timed: List<Capture>, at: Long): Capture? {
            var i = timed.binarySearchBy(at) { it.takenAtMillis }
            if (i < 0) i = -i - 1
            return listOfNotNull(timed.getOrNull(i - 1), timed.getOrNull(i))
                .minByOrNull { kotlin.math.abs(it.takenAtMillis!! - at) }
                ?.takeIf { kotlin.math.abs(it.takenAtMillis!! - at) <= MATCH_MS }
        }

        private const val MATCH_MS = 3_000L

        fun days(items: List<GalleryItem>): List<Day> =
            items.groupBy { it.date }.map { (date, list) -> Day(date, list) }
    }
}
