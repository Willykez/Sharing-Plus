package com.willyshare.willykez.ui

import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.willyshare.willykez.data.FileItemEntity
import com.willyshare.willykez.data.TransferEntity
import com.willyshare.willykez.net.LocalFileNode
import com.willyshare.willykez.ui.theme.SleekCard
import com.willyshare.willykez.ui.theme.SleekOnSurface
import com.willyshare.willykez.ui.theme.SleekOnSurfaceVariant
import com.willyshare.willykez.ui.theme.SleekOutline
import com.willyshare.willykez.ui.theme.SleekPrimary
import com.willyshare.willykez.ui.theme.SleekSurfaceContainer
import com.willyshare.willykez.util.FileOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One file, in whatever shape a long-press preview needs it - built from a [FileItemEntity]
 * (device picker), a [LocalFileNode] (folder browser), or a [TransferEntity] (history), each
 * of which uses a different category vocabulary and a different notion of "where the bytes
 * are". [category] is always normalized to the singular vocabulary [PulseIcons.forCategory]
 * expects (PHOTO/VIDEO/AUDIO/APP/ARCHIVE/DOC), regardless of which source it came from.
 */
data class PreviewableFile(
    val name: String,
    val sizeBytes: Long,
    val category: String,
    /** What Coil/thumbnail loading should read from - a content://, file://, or plain
     *  filesystem path string. Null when nothing is loadable (e.g. an older history row from
     *  before source tracking existed). */
    val previewSource: String?,
) {
    companion object {
        fun from(file: FileItemEntity): PreviewableFile = PreviewableFile(
            name = file.name,
            sizeBytes = file.sizeBytes,
            category = when (file.category) {
                "Photos" -> "PHOTO"
                "Videos" -> "VIDEO"
                "Documents" -> "DOC"
                "Apps" -> "APP"
                "Audio" -> "AUDIO"
                else -> file.category.uppercase()
            },
            previewSource = file.uri
        )

        fun from(node: LocalFileNode): PreviewableFile = PreviewableFile(
            name = node.name,
            sizeBytes = node.sizeBytes,
            category = categoryFromName(node.name),
            previewSource = node.path
        )

        fun from(transfer: TransferEntity): PreviewableFile = PreviewableFile(
            name = transfer.fileName,
            sizeBytes = transfer.sizeBytes,
            category = transfer.category,
            previewSource = transfer.savedPath ?: transfer.sourceUri
        )

        private fun categoryFromName(name: String): String = when {
            name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) || name.endsWith(".png", true) ||
                name.endsWith(".webp", true) || name.endsWith(".gif", true) -> "PHOTO"
            name.endsWith(".mp4", true) || name.endsWith(".mov", true) || name.endsWith(".mkv", true) ||
                name.endsWith(".3gp", true) || name.endsWith(".webm", true) -> "VIDEO"
            name.endsWith(".mp3", true) || name.endsWith(".m4a", true) || name.endsWith(".wav", true) ||
                name.endsWith(".ogg", true) || name.endsWith(".flac", true) -> "AUDIO"
            name.endsWith(".apk", true) -> "APP"
            name.endsWith(".zip", true) || name.endsWith(".rar", true) || name.endsWith(".7z", true) -> "ARCHIVE"
            else -> "DOC"
        }
    }
}

/** Best-effort [Uri] parse of [PreviewableFile.previewSource] - a plain filesystem path (no
 *  scheme) is treated as a file:// path, since that's how folder-browser and pre-sourceUri
 *  history rows store it. Returns null rather than throwing on a malformed string. */
private fun PreviewableFile.previewUri(): Uri? {
    val src = previewSource ?: return null
    return try {
        if (src.contains("://")) Uri.parse(src) else Uri.fromFile(File(src))
    } catch (_: Exception) {
        null
    }
}

/**
 * The long-press preview - an Instagram-post-style focused view: dark scrim, the file's
 * actual content large and centered, filename/size below it. What fills the content area
 * depends on category:
 *  - PHOTO: the real image, fit to the dialog, tap-to-dismiss same as the scrim.
 *  - VIDEO: an actual frame from the video with a Play button that hands off to the system
 *    player via [FileOpener] - this app doesn't embed a video player, so playback always
 *    leaves the dialog rather than trying to fake it inline.
 *  - AUDIO / APP / DOC / ARCHIVE / unknown: a large icon card (app icon for APK when
 *    resolvable) with an "Open" button doing the same handoff.
 * Dismissible via the scrim, the X button, or the system back gesture.
 */
@Composable
fun FilePreviewDialog(file: PreviewableFile, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uri = file.previewUri()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(Color.Black.copy(alpha = 0.88f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    // Swallow taps on the actual content card so they don't fall through to
                    // the scrim's dismiss-on-tap behind it.
                    .clickable(enabled = false) {},
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (file.category) {
                    "PHOTO" -> PhotoPreviewContent(uri, file.name)
                    "VIDEO" -> VideoPreviewContent(uri, file, context)
                    else -> GenericPreviewContent(file, uri, context)
                }

                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    text = file.name,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(0.85f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${formatBytes(file.sizeBytes)} \u00B7 ${file.category.lowercase().replaceFirstChar { it.uppercase() }}",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 12.sp
                )
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 40.dp, end = 16.dp)
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f))
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close preview", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun PhotoPreviewContent(uri: Uri?, name: String) {
    if (uri == null) {
        MissingPreviewIcon()
        return
    }
    AsyncImage(
        model = uri,
        contentDescription = name,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 480.dp)
            .clip(RoundedCornerShape(18.dp))
    )
}

@Composable
private fun VideoPreviewContent(uri: Uri?, file: PreviewableFile, context: android.content.Context) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 10f)
            .clip(RoundedCornerShape(18.dp))
            .background(SleekSurfaceContainer),
        contentAlignment = Alignment.Center
    ) {
        val thumb by rememberFrameThumbnail(uri)
        val bmp = thumb
        if (bmp != null) {
            Image(bitmap = bmp.asImageBitmap(), contentDescription = file.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().fillMaxHeight())
        } else {
            Icon(PulseIcons.forCategory("VIDEO"), contentDescription = null, tint = SleekOnSurfaceVariant, modifier = Modifier.size(48.dp))
        }
        IconButton(
            onClick = { if (uri != null) FileOpener.open(context, uri.toString(), file.name) },
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.5f))
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Play video", tint = Color.White, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
private fun GenericPreviewContent(file: PreviewableFile, uri: Uri?, context: android.content.Context) {
    Box(
        modifier = Modifier
            .size(140.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(SleekSurfaceContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(PulseIcons.forCategory(file.category), contentDescription = null, tint = SleekOnSurfaceVariant, modifier = Modifier.size(56.dp))
    }
    if (uri != null) {
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = { FileOpener.open(context, uri.toString(), file.name) },
            shape = RoundedCornerShape(999.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SleekPrimary)
        ) {
            Text("Open with\u2026", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

@Composable
private fun MissingPreviewIcon() {
    Box(
        modifier = Modifier
            .size(140.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(SleekSurfaceContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(PulseIcons.GenericFile, contentDescription = null, tint = SleekOnSurfaceVariant, modifier = Modifier.size(48.dp))
    }
}

/** A real frame from the video, via the system's own thumbnail extractor - works for both a
 *  MediaStore content:// Uri (Q+ uses contentResolver.loadThumbnail) and a plain file:// path
 *  outside MediaStore, e.g. a just-received file sitting in the app's own save folder (Q+
 *  uses ThumbnailUtils.createVideoThumbnail(File,...); pre-Q falls back to the deprecated
 *  MediaStore.Video.Thumbnails API for a content Uri, or silently shows the icon fallback for
 *  a bare file path since there's no equivalent pre-Q file-based API worth the complexity). */
@Composable
private fun rememberFrameThumbnail(uri: Uri?): androidx.compose.runtime.State<Bitmap?> {
    val context = LocalContext.current
    return produceState<Bitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            if (uri == null) return@withContext null
            try {
                when {
                    uri.scheme == "content" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                        context.contentResolver.loadThumbnail(uri, Size(384, 384), null)
                    uri.scheme == "content" -> {
                        @Suppress("DEPRECATION")
                        val id = uri.lastPathSegment?.toLongOrNull()
                        if (id != null) {
                            @Suppress("DEPRECATION")
                            MediaStore.Video.Thumbnails.getThumbnail(context.contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
                        } else null
                    }
                    (uri.scheme == "file" || uri.scheme == null) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                        val path = uri.path ?: return@withContext null
                        val f = File(path)
                        if (f.exists()) ThumbnailUtils.createVideoThumbnail(f, Size(384, 384), null) else null
                    }
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Compact thumbnail for a [TransferEntity] history row - mirrors SelectFilesScreen's
 * FileThumbnail (photo/video get a real preview, everything else gets the category icon) but
 * works from whichever of [TransferEntity.savedPath] (received) or [TransferEntity.sourceUri]
 * (sent) is actually populated, falling back to the plain icon when neither resolves to
 * something loadable - e.g. a history row recorded before thumbnails existed, or a sent file
 * whose share-intent content:// Uri permission has since expired.
 */
@Composable
fun TransferThumbnail(transfer: TransferEntity, modifier: Modifier = Modifier, iconSize: androidx.compose.ui.unit.Dp = 22.dp) {
    val preview = PreviewableFile.from(transfer)
    val uri = preview.previewUri()
    when (preview.category) {
        "PHOTO" -> {
            if (uri != null) {
                AsyncImage(
                    model = uri,
                    contentDescription = transfer.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = modifier
                )
            } else {
                CategoryIconBox(preview.category, iconSize, modifier)
            }
        }
        "VIDEO" -> {
            Box(modifier = modifier, contentAlignment = Alignment.Center) {
                val thumb by rememberFrameThumbnail(uri)
                val bmp = thumb
                if (bmp != null) {
                    Image(bitmap = bmp.asImageBitmap(), contentDescription = transfer.fileName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().fillMaxHeight())
                    Box(
                        modifier = Modifier.size(16.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
                    }
                } else {
                    Icon(PulseIcons.forCategory("VIDEO"), contentDescription = null, tint = SleekOnSurfaceVariant, modifier = Modifier.size(iconSize))
                }
            }
        }
        else -> CategoryIconBox(preview.category, iconSize, modifier)
    }
}

@Composable
private fun CategoryIconBox(category: String, iconSize: androidx.compose.ui.unit.Dp, modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Icon(PulseIcons.forCategory(category), contentDescription = null, tint = SleekOnSurfaceVariant, modifier = Modifier.size(iconSize))
    }
}
