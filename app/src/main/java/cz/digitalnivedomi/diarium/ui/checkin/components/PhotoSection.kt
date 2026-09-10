package cz.digitalnivedomi.diarium.ui.checkin.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import java.io.File

/**
 * Fotka — camera or gallery. The picked image is uploaded to the public
 * `diary-photos` bucket at `{uid}/{date}.jpg` and only its public URL is stored
 * in `entries.photo_path` (never image bytes in the DB).
 */
@Composable
fun PhotoSection(
    photoPath: String?,
    uploading: Boolean,
    onPhotoSelected: (Uri) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var pendingUri by remember { mutableStateOf<Uri?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) onPhotoSelected(uri) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved -> if (saved) pendingUri?.let(onPhotoSelected) }

    CheckInSection(title = "Fotka", modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SecondaryButton(
                text = "📷 Vyfotit",
                modifier = Modifier.weight(1f),
                testTag = "photo_camera",
            ) {
                val dir = File(context.cacheDir, "checkin_photos").apply { mkdirs() }
                val file = File.createTempFile("checkin_", ".jpg", dir)
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
                pendingUri = uri
                cameraLauncher.launch(uri)
            }
            SecondaryButton(
                text = "🖼️ Z galerie",
                modifier = Modifier.weight(1f),
                testTag = "photo_gallery",
            ) {
                galleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        when {
            uploading -> Text(
                text = "⏳ Nahrávám fotku...",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )

            photoPath != null -> {
                AsyncImage(
                    model = photoPath,
                    contentDescription = "Fotka dne",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .clip(RoundedCornerShape(14.dp)),
                )
                Spacer(Modifier.height(8.dp))
                SecondaryButton(text = "🗑 Odebrat", testTag = "photo_remove") { onRemove() }
            }

            else -> Text(
                text = "Zatím žádná fotka.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
    }
}
