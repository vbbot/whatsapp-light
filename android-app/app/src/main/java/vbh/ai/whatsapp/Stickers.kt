package vbh.ai.whatsapp

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.rememberLottieAnimatable
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/** How wide a sticker is drawn, as a share of the message column: present, but not dominant. */
private const val STICKER_WIDTH_FRACTION = 1f / 3

/** Decoded no larger than this (px): stickers are 512 px, drawn at about a third of the screen. */
private const val STICKER_MAX_SIDE = 320

/** A decoded sticker: a WebP image (maybe animated), or one of WhatsApp's Lottie animations. */
internal sealed interface StickerArt {
    class Image(
        val drawable: Drawable,
    ) : StickerArt

    class Lottie(
        val composition: LottieComposition,
    ) : StickerArt
}

/**
 * A sticker in a thread row, with no caption. Coming on screen fetches it ([Media.shown]); until
 * it's here, or if it can't be fetched (expired) or drawn, it's a "[Sticker]" line. An animated
 * sticker plays once and stops on its last frame; a tap replays it.
 */
@Composable
internal fun Sticker(m: Msg) {
    LaunchedEffect(m.id, m.needsFullPhoto) { Media.shown(m) }
    val path = m.media.takeIf { it.isNotEmpty() && !isThumbnailFile(it) }
    val art by produceState<StickerArt?>(null, path) {
        value = path?.let { withContext(Dispatchers.IO) { decodeSticker(File(it)) } }
    }
    when (val a = art) {
        is StickerArt.Image -> ImageSticker(a.drawable)
        is StickerArt.Lottie -> LottieSticker(a.composition)
        null -> LightText(text = "[Sticker]", variant = LightTextVariant.Paragraph, modifier = Modifier.padding(top = 1.dp))
    }
}

private fun stickerModifier(aspect: Float) = Modifier.padding(top = 1.dp).fillMaxWidth(STICKER_WIDTH_FRACTION).aspectRatio(aspect)

@Composable
private fun ImageSticker(d: Drawable) {
    val animated = d as? AnimatedImageDrawable
    AndroidView(
        factory = { ctx -> ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
        update = { view ->
            if (view.drawable !== d) {
                view.setImageDrawable(d)
                animated?.start()
            }
        },
        modifier =
            stickerModifier(d.intrinsicWidth.toFloat() / d.intrinsicHeight).then(
                if (animated == null) {
                    Modifier
                } else {
                    Modifier.lightClickable(onClickLabel = "Replay sticker") {
                        animated.stop()
                        animated.start()
                    }
                },
            ),
    )
}

@Composable
private fun LottieSticker(composition: LottieComposition) {
    val animatable = rememberLottieAnimatable()
    val scope = rememberCoroutineScope()

    // Like the animated WebP ones: whatever the system's animation setting, once, ending on the last frame.
    suspend fun play() = animatable.animate(composition, iterations = 1, ignoreSystemAnimationsDisabled = true)
    LaunchedEffect(composition) { play() }
    val bounds = composition.bounds
    LottieAnimation(
        composition = composition,
        progress = { animatable.progress },
        modifier =
            stickerModifier(if (bounds.width() > 0 && bounds.height() > 0) bounds.width().toFloat() / bounds.height() else 1f)
                .lightClickable(onClickLabel = "Replay sticker") { scope.launch { play() } },
    )
}

/** The sticker at [f], or null if it can't be decoded. */
internal fun decodeSticker(f: File): StickerArt? = runCatching { if (isZip(f)) lottie(f) else image(f) }.getOrNull()

/** WhatsApp's Lottie stickers are zips (see walib's DownloadSticker), whatever the file's name. */
private fun isZip(f: File): Boolean {
    val head = ByteArray(4)
    val n = f.inputStream().use { it.read(head) }
    return n == 4 && head.contentEquals(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
}

/**
 * The animation in a Lottie sticker's zip. Read here rather than with Lottie's own zip loader,
 * which takes any entry with ".json" in its name as the animation, and WhatsApp's zips also hold
 * "animation.json.trust_token" and "animation.json.overridden_metadata".
 */
private fun lottie(f: File): StickerArt? =
    ZipFile(f).use { zip ->
        val entry = zip.entries().asSequence().firstOrNull { !it.isDirectory && it.name.endsWith(".json") } ?: return null
        zip.getInputStream(entry).use { input ->
            LottieCompositionFactory.fromJsonInputStreamSync(input, f.path).value?.let { StickerArt.Lottie(it) }
        }
    }

/** A WebP sticker, scaled down; an animated one set to play once. */
private fun image(f: File): StickerArt? {
    val d =
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(f)) { decoder, info, _ ->
            val (w, h) = info.size.width to info.size.height
            val scale = minOf(1f, STICKER_MAX_SIDE.toFloat() / maxOf(w, h))
            decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
        }
    (d as? AnimatedImageDrawable)?.repeatCount = 0
    return if (d.intrinsicWidth > 0 && d.intrinsicHeight > 0) StickerArt.Image(d) else null
}
