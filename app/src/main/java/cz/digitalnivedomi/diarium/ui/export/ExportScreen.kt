package cz.digitalnivedomi.diarium.ui.export

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.digitalnivedomi.diarium.core.export.CsvExport
import cz.digitalnivedomi.diarium.core.export.ExportEntry
import cz.digitalnivedomi.diarium.ui.components.BrandSpinner
import cz.digitalnivedomi.diarium.ui.components.EmptyState
import cz.digitalnivedomi.diarium.ui.components.GlassCard
import cz.digitalnivedomi.diarium.ui.components.GlassChip
import cz.digitalnivedomi.diarium.ui.components.GlassDivider
import cz.digitalnivedomi.diarium.ui.components.SectionHeader
import cz.digitalnivedomi.diarium.ui.components.ShimmerBox
import cz.digitalnivedomi.diarium.ui.components.StatusDot
import cz.digitalnivedomi.diarium.ui.components.accentGlow
import cz.digitalnivedomi.diarium.ui.components.rememberHaptics
import cz.digitalnivedomi.diarium.ui.components.ScreenSubtitle
import cz.digitalnivedomi.diarium.ui.components.VSpace
import cz.digitalnivedomi.diarium.ui.theme.Dimens
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.IndigoLight
import cz.digitalnivedomi.diarium.ui.theme.Spacing
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Test tags — the Compose tests address the controls by these, never by Czech copy. */
const val ExportSaveButtonTag = "export-save"
const val ExportRetryButtonTag = "export-retry"
const val ExportStatusTag = "export-status"

/** The screen's one warning accent — the same red the notification screen uses for a denial. */
private val WarnColor = Color(0xFFF87171)

/**
 * "Export do CSV" — the native twin of the web's `/api/export/csv` download.
 *
 * The difference is where the file comes from: the web endpoint streams the CSV
 * from our server, which reads the rows with the `service_role` key. The app has no
 * server key and no server of its own, so it pages the same ten columns straight
 * out of Supabase with the signed-in user's JWT (RLS scopes them to the account)
 * and builds the identical file with [CsvExport] on the device — the diary never
 * passes through a third party just to be downloaded.
 *
 * Saving uses the Storage Access Framework ([ActivityResultContracts.CreateDocument]):
 * the user picks the location in the system dialog and the app writes into the URI
 * it gets back, so the feature adds **no** permission to the manifest.
 *
 * The screen is driven by [ExportDeps] and renders offline (previews, tests).
 */
@Composable
fun ExportScreen(deps: ExportDeps, today: LocalDate = LocalDate.now()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    var phase by remember { mutableStateOf(ExportPhase.LOADING) }
    var entries by remember { mutableStateOf<List<ExportEntry>>(emptyList()) }
    var detail by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableStateOf(0) }

    // Held between "Uložit CSV…" and the file picker's answer: the picker result can
    // arrive after a recomposition, so the rows travel in state instead of a closure.
    var pending by remember { mutableStateOf<List<ExportEntry>>(emptyList()) }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(CsvExport.MIME_TYPE),
    ) { uri ->
        val text = pending
        if (uri == null) {
            status = ExportState.SAVE_CANCELLED
        } else {
            scope.launch {
                // Built off the main thread: a multi-year diary is a multi-megabyte string.
                val csv = withContext(Dispatchers.Default) { CsvExport.build(text) }
                writeCsvToUri(context, uri, csv)
                    .onSuccess { bytes ->
                        haptics.success()
                        status = ExportState.savedMessage(text.size, bytes)
                    }
                    .onFailure { error -> status = ExportState.saveFailedMessage(error.message) }
            }
        }
    }

    LaunchedEffect(deps, reloadTick) {
        val repository = deps.repository
        if (repository == null) {
            phase = ExportPhase.ERROR
            detail = ExportState.OFFLINE_MESSAGE
            return@LaunchedEffect
        }
        phase = ExportPhase.LOADING
        status = null
        repository.loadAllEntries()
            .onSuccess { rows ->
                entries = rows
                detail = null
                phase = ExportState.phaseFor(rows.size)
            }
            .onFailure { error ->
                entries = emptyList()
                detail = error.message
                phase = ExportPhase.ERROR
            }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter)
            .padding(top = Spacing.screenTop, bottom = Spacing.screenBottom),
        verticalArrangement = Arrangement.spacedBy(Spacing.section),
    ) {
        ScreenSubtitle(text = ExportState.SUBTITLE)

        when (phase) {
            ExportPhase.LOADING -> GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(accent = IndigoLight)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = ExportState.LOADING_LABEL,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                }
                VSpace(Spacing.block)
                repeat(3) { line ->
                    ShimmerBox(
                        modifier = Modifier
                            .fillMaxWidth(if (line == 2) 0.55f else 1f)
                            .height(Dimens.skeletonLine),
                    )
                    if (line < 2) VSpace(Spacing.tight)
                }
            }

            ExportPhase.ERROR -> GlassCard(modifier = Modifier.fillMaxWidth(), accent = WarnColor) {
                Text(
                    text = ExportState.LOAD_FAILED_TITLE,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
                VSpace(Spacing.tight)
                Text(
                    text = ExportState.loadFailedMessage(detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.testTag(ExportStatusTag),
                )
                VSpace(Spacing.block)
                Button(
                    onClick = { reloadTick++ },
                    shape = RoundedCornerShape(Dimens.radiusCard),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WarnColor.copy(alpha = 0.16f),
                        contentColor = WarnColor,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Dimens.controlHeight)
                        .testTag(ExportRetryButtonTag),
                ) {
                    Text(ExportState.RETRY, fontWeight = FontWeight.SemiBold)
                }
            }

            ExportPhase.EMPTY -> GlassCard(modifier = Modifier.fillMaxWidth()) {
                EmptyState(
                    emoji = "📭",
                    title = ExportState.EMPTY_TITLE,
                    message = ExportState.EMPTY_MESSAGE,
                )
                VSpace(Spacing.section)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    OutlinedButton(
                        onClick = { reloadTick++ },
                        modifier = Modifier.testTag(ExportRetryButtonTag),
                    ) {
                        Text(ExportState.LOAD_AGAIN)
                    }
                }
            }

            ExportPhase.READY -> GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Připraveno k uložení",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    GlassChip(
                        text = ExportState.entryCountLabel(entries.size),
                        accent = IndigoLight,
                    )
                }
                VSpace(Spacing.tight)
                Text(
                    text = ExportState.SOURCE_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                VSpace(Spacing.block)
                Text(
                    text = ExportState.SAVER_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                VSpace(Spacing.block)
                Button(
                    onClick = {
                        haptics.light()
                        pending = entries
                        status = null
                        createDocument.launch(ExportState.fileName(today))
                    },
                    shape = RoundedCornerShape(Dimens.radiusCard),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Indigo,
                        contentColor = Color.White,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Dimens.controlHeight)
                        .accentGlow(Indigo, alpha = 0.30f)
                        .testTag(ExportSaveButtonTag),
                ) {
                    Text(
                        text = ExportState.SAVE_ACTION,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                status?.let { message ->
                    VSpace(Spacing.tight)
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = IndigoLight,
                        modifier = Modifier.testTag(ExportStatusTag),
                    )
                }
                GlassDivider()
                OutlinedButton(
                    onClick = { reloadTick++ },
                    modifier = Modifier.testTag(ExportRetryButtonTag),
                ) {
                    Text(ExportState.LOAD_AGAIN)
                }
            }
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            SectionHeader(title = "Co soubor obsahuje")
            VSpace(Spacing.tight)
            Text(
                text = "Jeden řádek na den, od nejstaršího. Hlavička: ${CsvExport.headerLine()}",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            VSpace(Spacing.tight)
            Text(
                text = "Hodnoty odděluje čárka, text s čárkou nebo uvozovkou je v uvozovkách a " +
                    "vícehodnotové sloupce (aktivity, návyky, vděčnost, škály) jsou vložené jako JSON. " +
                    "Kódování UTF-8, konec řádku LF.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            VSpace(Spacing.block)
            Text(
                text = ExportState.PARITY_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
