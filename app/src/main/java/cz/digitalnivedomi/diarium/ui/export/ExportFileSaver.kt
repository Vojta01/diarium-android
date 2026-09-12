package cz.digitalnivedomi.diarium.ui.export

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Writes the CSV to the file the user picked in the system "create document" dialog.
 *
 * This is the whole reason the export needs no permission: the Storage Access
 * Framework hands back a `content://` URI that the app may write to *because the
 * user chose it*, so there is nothing to declare in the manifest — no
 * `WRITE_EXTERNAL_STORAGE` and no `MANAGE_DOCUMENTS`.
 *
 * Runs on [Dispatchers.IO] (a diary export can be a few megabytes) and reports
 * failures as a value: the screen shows a Czech sentence, it never crashes. The
 * bytes are UTF-8 without a BOM, matching the web download.
 */
suspend fun writeCsvToUri(context: Context, uri: Uri, csv: String): Result<Int> =
    withContext(Dispatchers.IO) {
        try {
            val bytes = csv.toByteArray(Charsets.UTF_8)
            val stream = context.contentResolver.openOutputStream(uri, "wt")
                ?: return@withContext Result.failure(
                    IllegalStateException("Vybraný soubor se nepodařilo otevřít."),
                )
            stream.use { it.write(bytes); it.flush() }
            Result.success(bytes.size)
        } catch (e: SecurityException) {
            Result.failure(IllegalStateException("Zápis do souboru byl zamítnut."))
        } catch (_: IOException) {
            Result.failure(IllegalStateException("Zápis do souboru se nezdařil."))
        } catch (e: IllegalArgumentException) {
            Result.failure(IllegalStateException("Vybraný soubor nelze použít."))
        }
    }
