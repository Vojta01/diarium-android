package cz.digitalnivedomi.diarium.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text as MaterialText
import androidx.compose.material3.TextButton
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.digitalnivedomi.diarium.core.data.LegacyProfileField
import cz.digitalnivedomi.diarium.core.data.LegacyRecoveryAttestation
import cz.digitalnivedomi.diarium.core.data.LegacyRecoveryAuthorizationResult
import cz.digitalnivedomi.diarium.core.data.LegacyRecoveryContents
import cz.digitalnivedomi.diarium.core.data.LegacyRecoveryCopyResult
import cz.digitalnivedomi.diarium.core.data.LegacyRecoveryFinalizeResult
import cz.digitalnivedomi.diarium.core.data.LegacyRecoveryInventory
import cz.digitalnivedomi.diarium.core.data.LegacyRecoveryRecord
import cz.digitalnivedomi.diarium.core.data.LegacyRecoveryRecordId
import cz.digitalnivedomi.diarium.core.data.LegacyRecoveryRecordStatus
import cz.digitalnivedomi.diarium.core.data.LegacyRecoveryScanResult
import cz.digitalnivedomi.diarium.core.data.LegacyRecoveryType
import cz.digitalnivedomi.diarium.core.data.LegacySecretPresence
import cz.digitalnivedomi.diarium.ui.components.GlassCard
import cz.digitalnivedomi.diarium.ui.components.GlassDivider
import cz.digitalnivedomi.diarium.ui.components.ScreenSubtitle
import cz.digitalnivedomi.diarium.ui.components.VSpace
import cz.digitalnivedomi.diarium.ui.i18n.Text
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.Spacing
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * Local-only, per-record recovery for pre-account-scoped diary drafts and queued saves.
 * No diary content is displayed until the user confirms both ownership and privacy.
 */
@Composable
fun LegacyRecoveryScreen(deps: LegacyRecoveryDeps) {
    val scope = rememberCoroutineScope()
    var scanResult by remember(deps) { mutableStateOf<LegacyRecoveryScanResult?>(null) }
    var selectedId by remember { mutableStateOf<LegacyRecoveryRecordId?>(null) }
    var attestation by remember { mutableStateOf<LegacyRecoveryAttestation?>(null) }
    var contents by remember { mutableStateOf<LegacyRecoveryContents?>(null) }
    var ownershipConfirmed by remember { mutableStateOf(false) }
    var privacyConfirmed by remember { mutableStateOf(false) }
    var operationMessage by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    var showRemoveOriginalDialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun clearSelection() {
        selectedId = null
        attestation = null
        contents = null
        ownershipConfirmed = false
        privacyConfirmed = false
        operationMessage = null
        copied = false
        showRemoveOriginalDialog = false
    }

    fun refresh() {
        scope.launch {
            busy = true
            clearSelection()
            scanResult = deps.coordinator.scan()
            busy = false
        }
    }

    LifecycleResumeEffect(deps) {
        refresh()
        onPauseOrDispose { clearSelection() }
    }

    val ready = scanResult as? LegacyRecoveryScanResult.Ready
    val inventory = ready?.inventory
    val selectedRecord = inventory?.records?.firstOrNull { it.id == selectedId }
    val selectedCanRecover = selectedRecord?.status == LegacyRecoveryRecordStatus.PREVIEWABLE

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter)
            .padding(top = Spacing.screenTop, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.section),
    ) {
        ScreenSubtitle(
            text = "Staré místní koncepty a čekající uložení lze obnovit jen ručně. Nic se nepřiřadí ani neodešle automaticky.",
        )

        when (val result = scanResult) {
            null -> Text("Načítám pouze přehled místních záznamů…", color = TextSecondary)
            LegacyRecoveryScanResult.OwnerUnavailable -> RecoveryWarning(
                "Obnova je uzamčená. Není k dispozici ověřený účet uložený po přihlášení. Připoj se a ověř účet; obnova sama potom pracuje jen místně.",
            )
            LegacyRecoveryScanResult.OwnerChanged -> RecoveryWarning(
                "Účet se během kontroly změnil. Záznamy nebyly obnoveny. Znovu otevři tuto obrazovku.",
            )
            LegacyRecoveryScanResult.ReadFailed -> RecoveryWarning(
                "Místní úložiště se nepodařilo bezpečně přečíst. Nic nebylo změněno.",
            )
            is LegacyRecoveryScanResult.Ready -> {
                val currentVerifiedOwnerId = result.ownerId
                RecoveryWarning(
                    "Ověřený účet: ${result.ownerId}. Starý záznam nemá uloženého vlastníka. Před zobrazením i kopií musíš výslovně potvrdit, že vybraný záznam patří právě tomuto účtu.",
                )
                InventorySummary(result.inventory)
                UnsupportedLegacyData(result.inventory.profileFieldsPresent, result.inventory.legacyAiSecretStatus)
                if (result.inventory.records.isEmpty()) {
                    Text("Obnovitelné staré koncepty ani čekající uložení nebyly nalezeny.", color = TextSecondary)
                } else {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text("Místní záznamy", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        VSpace(Spacing.tight)
                        result.inventory.records.forEachIndexed { index, record ->
                            if (index > 0) GlassDivider()
                            LegacyRecordRow(
                                record = record,
                                selected = record.id == selectedId,
                                onSelect = {
                                    if (!busy) {
                                        selectedId = record.id
                                        attestation = null
                                        contents = null
                                        ownershipConfirmed = false
                                        privacyConfirmed = false
                                        operationMessage = null
                                        copied = false
                                    }
                                },
                            )
                        }
                    }
                }

                if (selectedRecord != null && selectedCanRecover) {
                    GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo) {
                        Text("Potvrzení pro vybraný záznam", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        VSpace(Spacing.tight)
                        ConsentRow(
                            checked = ownershipConfirmed,
                            onCheckedChange = {
                                if (!busy) {
                                    ownershipConfirmed = it
                                    attestation = null
                                    contents = null
                                }
                            },
                            text = "Potvrzuji, že tento vybraný záznam patří ověřenému účtu ${result.ownerId}.",
                        )
                        ConsentRow(
                            checked = privacyConfirmed,
                            onCheckedChange = {
                                if (!busy) {
                                    privacyConfirmed = it
                                    attestation = null
                                    contents = null
                                }
                            },
                            text = "Rozumím, že se obsah deníku zobrazí pouze na tomto zařízení.",
                        )
                        VSpace(Spacing.tight)
                        Text(
                            "Nejdřív se zobrazí místní náhled. Kopie bude uložena jako koncept tohoto účtu; nebude zařazena do fronty ani odeslána na server.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                        VSpace(Spacing.block)
                        OutlinedButton(
                            enabled = ownershipConfirmed && privacyConfirmed && !busy,
                            onClick = {
                                val id = selectedRecord.id
                                scope.launch {
                                    busy = true
                                    operationMessage = null
                                    contents = null
                                    when (val result = deps.coordinator.authorize(
                                        id = id,
                                        assertedOwnerId = currentVerifiedOwnerId,
                                        ownershipConfirmed = ownershipConfirmed,
                                        localPrivacyConfirmed = privacyConfirmed,
                                    )) {
                                        is LegacyRecoveryAuthorizationResult.Granted -> {
                                            attestation = result.attestation
                                            when (val preview = deps.coordinator.preview(result.attestation)) {
                                                is cz.digitalnivedomi.diarium.core.data.LegacyRecoveryPreviewResult.Ready -> {
                                                    contents = preview.contents
                                                }
                                                else -> {
                                                    attestation = null
                                                    val ownerUnavailable =
                                                        preview == cz.digitalnivedomi.diarium.core.data.LegacyRecoveryPreviewResult.OwnerUnavailable ||
                                                            preview == cz.digitalnivedomi.diarium.core.data.LegacyRecoveryPreviewResult.OwnerChanged
                                                    if (ownerUnavailable) {
                                                        clearSelection()
                                                        scanResult = deps.coordinator.scan()
                                                    }
                                                    operationMessage = "Účet nebo zdroj se změnil. Obsah nebyl zobrazen; znovu proveď kontrolu."
                                                }
                                            }
                                        }
                                        LegacyRecoveryAuthorizationResult.ConfirmationRequired ->
                                            operationMessage = "Potvrď vlastnictví i místní soukromí před zobrazením."
                                        LegacyRecoveryAuthorizationResult.OwnerUnavailable,
                                        LegacyRecoveryAuthorizationResult.OwnerChanged -> {
                                            clearSelection()
                                            scanResult = deps.coordinator.scan()
                                            operationMessage = "Ověřený účet již není dostupný. Nic nebylo zkopírováno."
                                        }
                                        LegacyRecoveryAuthorizationResult.RecordNotRecoverable ->
                                            operationMessage = "Zdroj se změnil nebo tento formát nelze bezpečně obnovit. Původní data zůstala zachována."
                                        LegacyRecoveryAuthorizationResult.ReadFailed ->
                                            operationMessage = "Místní záznam se nepodařilo bezpečně načíst. Nic nebylo změněno."
                                    }
                                    busy = false
                                }
                            },
                        ) {
                            Text("Potvrdit a zobrazit náhled místně")
                        }
                    }
                }

                val authorizedContents = contents
                val authorized = attestation
                if (authorizedContents != null && authorized != null) {
                    RecoveredEntryPreview(authorizedContents)
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            enabled = !busy && !copied,
                            onClick = {
                                scope.launch {
                                    busy = true
                                    val outcome = deps.coordinator.copy(authorized)
                                    if (outcome == LegacyRecoveryCopyResult.OWNER_CHANGED ||
                                        outcome == LegacyRecoveryCopyResult.OWNER_UNAVAILABLE ||
                                        outcome == LegacyRecoveryCopyResult.COPY_MAY_HAVE_COMPLETED_FOR_OLD_ACCOUNT
                                    ) {
                                        clearSelection()
                                        scanResult = deps.coordinator.scan()
                                        operationMessage = copyMessage(outcome)
                                    } else {
                                        operationMessage = copyMessage(outcome)
                                        copied = outcome == LegacyRecoveryCopyResult.COPIED ||
                                            outcome == LegacyRecoveryCopyResult.ALREADY_COPIED
                                    }
                                    busy = false
                                }
                            },
                        ) {
                            Text(if (copied) "Kopie je v místním konceptu" else "Kopírovat do místního konceptu účtu")
                        }
                        if (copied) {
                            VSpace(Spacing.tight)
                            Text(
                                "Původní ownerless záznam je zatím zachován. Nejdřív zkontroluj místní kopii. Její další uložení do cloudu vyžaduje samostatnou akci v deníku.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                            )
                            VSpace(Spacing.tight)
                            OutlinedButton(onClick = { showRemoveOriginalDialog = true }, enabled = !busy) {
                                Text("Potvrdit úspěšnou obnovu a odstranit původní kopii")
                            }
                        }
                    }
                }

            }
        }
        operationMessage?.let { RecoveryWarning(it) }
        OutlinedButton(onClick = { refresh() }, enabled = !busy) {
            Text("Znovu zkontrolovat místní záznamy")
        }
    }

    if (showRemoveOriginalDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveOriginalDialog = false },
            title = { Text("Odstranit původní ownerless záznam?") },
            text = {
                Text(
                    "Tím výslovně potvrzuješ, že jsi zkontroloval(a) místní koncept tohoto účtu, obnova proběhla úspěšně a není v konfliktu s jiným místním konceptem. Původní záznam se odstraní jen pokud se od kopie nezměnil a místní cílový koncept stále odpovídá. Žádný serverový požadavek se neodešle.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRemoveOriginalDialog = false
                    val authorization = attestation
                    if (authorization != null) {
                        scope.launch {
                            busy = true
                            val outcome = deps.coordinator.confirmNoConflictAndRemoveOriginal(
                                authorization,
                                confirmedNoConflict = true,
                            )
                            if (outcome == LegacyRecoveryFinalizeResult.ORIGINAL_REMOVED ||
                                outcome == LegacyRecoveryFinalizeResult.OWNER_CHANGED ||
                                outcome == LegacyRecoveryFinalizeResult.OWNER_UNAVAILABLE
                            ) {
                                clearSelection()
                                scanResult = deps.coordinator.scan()
                            }
                            operationMessage = finalizeMessage(outcome)
                            busy = false
                        }
                    }
                }) { Text("Potvrzuji — odstranit původní záznam") }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveOriginalDialog = false }) { Text("Ponechat původní záznam") }
            },
        )
    }
}

@Composable
private fun InventorySummary(inventory: LegacyRecoveryInventory) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text("Přehled bez obsahu deníku", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        VSpace(Spacing.tight)
        Text("Koncepty: ${inventory.draftCount} · čekající uložení: ${inventory.pendingSaveCount}", color = TextSecondary)
        Text("Poškozené: ${inventory.malformedCount} · nepodporované: ${inventory.unsupportedCount}", color = TextSecondary)
    }
}

@Composable
private fun UnsupportedLegacyData(fields: Set<LegacyProfileField>, secret: LegacySecretPresence) {
    if (fields.isEmpty() && secret == LegacySecretPresence.ABSENT) return
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text("Nepřenášená data", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        VSpace(Spacing.tight)
        if (fields.isNotEmpty()) {
            val names = fields.sortedBy { it.ordinal }.joinToString { field ->
                when (field) {
                    LegacyProfileField.NAME -> "jméno"
                    LegacyProfileField.BIRTH_YEAR -> "rok narození"
                    LegacyProfileField.AI_ENABLED -> "souhlas s AI"
                    LegacyProfileField.ONBOARDING_DONE -> "průvodce"
                }
            }
            Text("Staré profilové položky ($names) nejsou podporované. Zůstávají beze změny.", color = TextSecondary)
        }
        when (secret) {
            LegacySecretPresence.PRESENT -> Text(
                "Starý AI klíč byl pouze zjištěn. Jeho hodnota se nečte ani nezobrazuje a klíč se nepřenáší; zůstává beze změny.",
                color = TextSecondary,
            )
            LegacySecretPresence.UNKNOWN -> Text(
                "Stav starého AI klíče nelze bezpečně ověřit. Hodnota se nezobrazuje ani nepřenáší.",
                color = TextSecondary,
            )
            LegacySecretPresence.ABSENT -> Unit
        }
    }
}

@Composable
private fun LegacyRecordRow(record: LegacyRecoveryRecord, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = Spacing.tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "${record.date} · ${record.type.title()}",
                color = TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                when (record.status) {
                    LegacyRecoveryRecordStatus.PREVIEWABLE -> "Podporovaný formát — obsah zatím skrytý"
                    LegacyRecoveryRecordStatus.MALFORMED -> "Poškozený záznam — ponechán beze změny"
                    LegacyRecoveryRecordStatus.UNSUPPORTED -> "Nepodporovaný formát — ponechán beze změny"
                },
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ConsentRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(text, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RecoveredEntryPreview(contents: LegacyRecoveryContents) {
    val entry = contents.entry
    GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo) {
        Text("Místní náhled · ${contents.id.date}", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        VSpace(Spacing.tight)
        Text("Nálada: ${entry.mood} · spánek: ${entry.sleepQuality} · stres: ${entry.stress}", color = TextSecondary)
        if (entry.activities.isNotEmpty()) ContentLine("Aktivity", entry.activities.joinToString(" · "))
        val enabledHabits = entry.habits.filterValues { it }.keys
        if (enabledHabits.isNotEmpty()) ContentLine("Návyky", enabledHabits.joinToString(" · "))
        if (entry.gratitude.any(String::isNotBlank)) ContentLine("Vděčnost", entry.gratitude.filter(String::isNotBlank).joinToString(" · "))
        if (entry.note.isNotBlank()) ContentLine("Poznámka", entry.note)
        if (entry.weather.isNotEmpty()) ContentLine("Počasí", entry.weather.joinToString(" · "))
        if (entry.scaleValues.isNotEmpty()) {
            ContentLine("Škály", entry.scaleValues.entries.joinToString(" · ") { "${it.key}: ${it.value}" })
        }
        if (entry.photoPath != null || entry.photoPathToDelete != null || entry.clearPhoto) {
            Text("Záznam obsahuje odkaz na fotografii; cesta se nezobrazuje ani neotevírá.", color = TextSecondary)
        }
    }
}

@Composable
private fun ContentLine(label: String, value: String) {
    VSpace(Spacing.tight)
    MaterialText("$label: $value", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun RecoveryWarning(text: String) {
    GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo) {
        Text(text, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun LegacyRecoveryType.title(): String = when (this) {
    LegacyRecoveryType.DRAFT -> "místní koncept"
    LegacyRecoveryType.PENDING_SAVE -> "čekající uložení"
}

private fun copyMessage(result: LegacyRecoveryCopyResult): String = when (result) {
    LegacyRecoveryCopyResult.COPIED -> "Kopie je uložena jako místní koncept účtu. Původní záznam zůstal zachován; nic nebylo odesláno."
    LegacyRecoveryCopyResult.ALREADY_COPIED -> "Stejná místní kopie už existuje po předchozím pokusu. Původní záznam zůstal zachován."
    LegacyRecoveryCopyResult.TARGET_CONFLICT -> "Cílový koncept tohoto účtu už existuje. Nebyl přepsán a původní záznam zůstává zachován."
    LegacyRecoveryCopyResult.SOURCE_CHANGED -> "Původní záznam se změnil. Kopie nebyla provedena; zdroj zůstal zachován."
    LegacyRecoveryCopyResult.OWNER_UNAVAILABLE,
    LegacyRecoveryCopyResult.OWNER_CHANGED -> "Ověřený účet se změnil nebo není dostupný. Kopie nebyla provedena."
    LegacyRecoveryCopyResult.COPY_MAY_HAVE_COMPLETED_FOR_OLD_ACCOUNT ->
        "Účet se změnil během kopírování. Kopie mohla být uložena jako místní koncept původního účtu. " +
            "Přihlas se znovu k tomuto účtu a ověř ji; původní záznam zůstává zachován. Nic nebylo odesláno."
    LegacyRecoveryCopyResult.PREVIEW_REQUIRED -> "Nejdřív potvrď vlastnictví a zobraz místní náhled."
    LegacyRecoveryCopyResult.RECORD_NOT_RECOVERABLE -> "Tento záznam už nelze bezpečně obnovit. Původní data zůstala zachována."
    LegacyRecoveryCopyResult.FAILED -> "Kopii se nepodařilo dokončit. Původní záznam zůstává zachován; můžeš bezpečně zkusit znovu."
}

private fun finalizeMessage(result: LegacyRecoveryFinalizeResult): String = when (result) {
    LegacyRecoveryFinalizeResult.ORIGINAL_REMOVED -> "Původní záznam byl odstraněn po potvrzení; cílový koncept zůstává místní."
    LegacyRecoveryFinalizeResult.CONFIRMATION_REQUIRED -> "Pro odstranění zdroje je nutné výslovné potvrzení."
    LegacyRecoveryFinalizeResult.TARGET_CHANGED -> "Cílový koncept už neodpovídá kopii. Původní záznam zůstal zachován."
    LegacyRecoveryFinalizeResult.SOURCE_CHANGED -> "Původní záznam se změnil nebo jej nelze ověřit. Zůstal zachován."
    LegacyRecoveryFinalizeResult.OWNER_UNAVAILABLE,
    LegacyRecoveryFinalizeResult.OWNER_CHANGED -> "Ověřený účet se změnil nebo není dostupný. Původní záznam zůstal zachován."
    LegacyRecoveryFinalizeResult.COPY_REQUIRED -> "Nejdřív vytvoř místní kopii."
    LegacyRecoveryFinalizeResult.RECORD_NOT_RECOVERABLE -> "Tento záznam už nelze bezpečně potvrdit. Původní data zůstala zachována."
    LegacyRecoveryFinalizeResult.FAILED -> "Původní záznam se nepodařilo bezpečně odstranit; zůstal zachován."
}
