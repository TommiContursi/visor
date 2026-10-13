package com.macedonga.visor.gallery

import android.content.Context
import android.graphics.ImageDecoder
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Images for the gallery, decoded once and kept while they fit: grid thumbnails from MediaStore's own cache or
 * the glasses' preview, and screen-sized pictures for the stage and the viewer.
 */
object Images {
    private val cache = object : LruCache<String, ImageBitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun cached(key: String): ImageBitmap? = cache.get(key)

    /** A square-ish thumbnail at least [px] on its short side. */
    suspend fun thumb(context: Context, item: GalleryItem, previews: Map<String, File>, px: Int = THUMB_PX): ImageBitmap? {
        val key = thumbKey(item)
        cache.get(key)?.let { return it }
        val image = withContext(Dispatchers.IO) {
            runCatching {
                val phone = item.phone
                if (phone != null) {
                    context.contentResolver.loadThumbnail(phone.uri, Size(px, px), null).asImageBitmap()
                } else {
                    val file = item.capture?.preview?.let { previews[it.id] } ?: return@runCatching null
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)).asImageBitmap()
                }
            }.getOrNull()
        } ?: return null
        cache.put(key, image)
        return image
    }

    /** The phone copy decoded to fit within [maxSide] pixels on its long side, upright. */
    suspend fun picture(context: Context, item: GalleryItem, maxSide: Int): ImageBitmap? {
        val phone = item.phone ?: return null
        if (phone.video) return thumb(context, item, emptyMap(), maxSide)
        val key = pictureKey(item, maxSide)
        cache.get(key)?.let { return it }
        val image = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, phone.uri)) { decoder, info, _ ->
                    val long = max(info.size.width, info.size.height)
                    if (long > maxSide) {
                        val scale = maxSide.toFloat() / long
                        decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                    }
                }.asImageBitmap()
            }.getOrNull()
        } ?: return null
        cache.put(key, image)
        return image
    }

    fun forget(item: GalleryItem) {
        val uri = item.phone?.uri ?: return
        for (key in cache.snapshot().keys) if (key.contains(uri.toString())) cache.remove(key)
    }

    /** A phone copy's keys carry its size, so an edited photo is decoded again. */
    fun thumbKey(item: GalleryItem) = "t:" + (item.phone?.let { "${it.uri}:${it.size}" } ?: item.key)

    fun pictureKey(item: GalleryItem, maxSide: Int) = "p:${item.phone?.uri}:${item.phone?.size}:$maxSide"

    private const val CACHE_BYTES = 96 * 1024 * 1024
    const val THUMB_PX = 320
}

@Composable
fun rememberThumb(item: GalleryItem?, previews: Map<String, File>, px: Int = Images.THUMB_PX): State<ImageBitmap?> {
    val context = LocalContext.current
    val key = item?.phone?.uri ?: item?.capture?.preview?.let { previews[it.id] }
    return produceState(item?.let { Images.cached(Images.thumbKey(it)) }, item?.key, key, item?.phone?.size) {
        if (item != null) Images.thumb(context, item, previews, px)?.let { value = it }
    }
}

/** The thumbnail at once, then the picture at [maxSide] when it is decoded. */
@Composable
fun rememberPicture(item: GalleryItem?, previews: Map<String, File>, maxSide: Int): State<ImageBitmap?> {
    val context = LocalContext.current
    return produceState(item?.let { Images.cached(Images.pictureKey(it, maxSide)) ?: Images.cached(Images.thumbKey(it)) }, item?.key, item?.phone?.uri, item?.phone?.size, maxSide) {
        if (item == null) return@produceState
        if (value == null) Images.thumb(context, item, previews)?.let { value = it }
        Images.picture(context, item, maxSide)?.let { value = it }
    }
}
