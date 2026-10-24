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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** How wide a sticker is drawn, as a share of the message column: present, but not dominant. */
private const val STICKER_WIDTH_FRACTION = 1f / 3

/** Decoded no larger than this (px): stickers are 512 px, drawn at about a third of the screen. */
private const val STICKER_MAX_SIDE = 320

/**
 * A sticker in a thread row, with no caption. Coming on screen fetches it ([Media.shown]); until
 * it's here, or if it can't be fetched (expired) or drawn, it's a "[Sticker]" line. An animated
 * sticker plays once and stops on its last frame; a tap replays it.
 */
@Composable
internal fun Sticker(m: Msg) {
    LaunchedEffect(m.id, m.needsFullPhoto) { Media.shown(m) }
    val path = m.media.takeIf { it.endsWith(".webp") }
    val drawable by produceState<Drawable?>(null, path) {
        value = path?.let { withContext(Dispatchers.IO) { decodeSticker(File(it)) } }
    }
    val d = drawable
    if (d == null || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) {
        LightText(text = "[Sticker]", variant = LightTextVariant.Paragraph, modifier = Modifier.padding(top = 1.dp))
        return
    }
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
            Modifier
                .padding(top = 1.dp)
                .fillMaxWidth(STICKER_WIDTH_FRACTION)
                .aspectRatio(d.intrinsicWidth.toFloat() / d.intrinsicHeight)
                .then(
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

/** The sticker at [f], scaled down; animated ones set to play once. Null if it can't be decoded (e.g. Lottie). */
private fun decodeSticker(f: File): Drawable? =
    runCatching {
        ImageDecoder
            .decodeDrawable(ImageDecoder.createSource(f)) { decoder, info, _ ->
                val (w, h) = info.size.width to info.size.height
                val scale = minOf(1f, STICKER_MAX_SIDE.toFloat() / maxOf(w, h))
                decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            }.also { (it as? AnimatedImageDrawable)?.repeatCount = 0 }
    }.getOrNull()
