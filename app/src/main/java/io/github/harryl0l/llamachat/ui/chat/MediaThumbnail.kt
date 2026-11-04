package io.github.harryl0l.llamachat.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.harryl0l.llamachat.Attachment
import io.github.harryl0l.llamachat.AttachmentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MediaThumbnail(attachment: Attachment, shape: Shape, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, attachment.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (attachment.uri.scheme == "file") {
                    // media saved with a chat (JPEG in app storage)
                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                    BitmapFactory.decodeFile(attachment.uri.path, opts)
                } else {
                    context.contentResolver.loadThumbnail(attachment.uri, Size(384, 384), null)
                }
            }.getOrNull()
        }
    }
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Rounded.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (attachment.kind == AttachmentKind.Video) {
            Box(
                Modifier
                    .size(size / 3)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                    .padding(2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PlayArrow, "Video", tint = MaterialTheme.colorScheme.inverseOnSurface)
            }
        }
    }
}

