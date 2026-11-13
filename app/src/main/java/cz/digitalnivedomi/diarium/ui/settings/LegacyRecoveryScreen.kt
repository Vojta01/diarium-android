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
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text as MaterialText
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
import cz.digitalnivedomi.diarium.core.data.LegacyAdoptionAvailability
import cz.digitalnivedomi.diarium.core.data.LegacyAdoptionResult
import cz.digitalnivedomi.diarium.core.data.LegacyProfileField
import cz.digitalnivedomi.diarium.core.data.LegacyRecoveryAttestation
import cz.digitalnivedomi.diarium.core.data.LegacyRecoveryAuthorizationResult
import cz.digitalnivedomi.diarium.core.data.LegacyRecoveryContents
import cz.digitalnivedomi.diarium.core.data.LegacyRecoveryCopyResult
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
    var busy by remember { mutableStateOf(false) }
    var adoptionAvailability by remember { mutableStateOf<LegacyAdoptionAvailability?>(null) }
    var adoptionConfirm by remember { mutableStateOf(false) }
    var adoptionMessage by remember { mutableStateOf<String?>(null) }
    var adoptionBusy by remember { mutableStateOf(false) }

    fun clearSelection() {
        selectedId = null
        attestation = null
        contents = null
        ownershipConfirmed = false
        privacyConfirmed = false
        operationMessage = null
        copied = false
    }

    fun refresh() {
        scope.launch {
            busy = true
            clearSelection()
            scanResult = deps.coordinator.scan()
            adoptionAvailability = deps.adoption.availability()
            busy = false
        }
    }

    fun adopt() {
        scope.launch {
            adoptionBusy = true
            adoptionMessage = when (val result = deps.adoption.adopt()) {
                is LegacyAdoptionResult.Adopted -> {
                    val parts = buildList {
                        if (result.profileFields > 0) add("profil")
                        if (result.keyAdopted) add("AI klíč")
                    }
                    "Převzato: ${parts.joinToString(" a ")}. Původní data zůstala v telefonu."
                }
                LegacyAdoptionResult.NothingToAdopt ->
                    "Nic k převzetí. Účet už hodnoty má, nebo stará data nejsou dostupná."
                LegacyAdoptionResult.OwnerUnavailable ->
                    "Převzetí je uzamčené. Není k dispozici ověřený účet."
                LegacyAdoptionResult.OwnerChanged ->
                    "Účet se během převzetí změnil. Nic nebylo převzato."
                LegacyAdoptionResult.ReadFailed ->
                    "Místní úložiště se nepodařilo přečíst. Nic nebylo změněno."
            }
            adoptionAvailability = deps.adoption.availability()
            adoptionBusy = false
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
            text = "Staré místní koncepty a čekající uložení lze obnovit jen ručně. Původní data zůstávají v tomto telefonu; kopii lze samostatně uložit přes bezpečné v2 porovnání (CAS). Tato obrazovka původní data nemaže.",
        )

        LegacyAdoptionCard(
            availability = adoptionAvailability,
            message = adoptionMessage,
            busy = adoptionBusy,
            onAdopt = { adoptionConfirm = true },
        )

        if (adoptionConfirm) {
            AlertDialog(
                onDismissRequest = { adoptionConfirm = false },
                title = { Text("Převzít stará data?") },
                text = {
                    Text(
                        "Převzetí zkopíruje jméno, rok narození a DeepSeek API klíč z beta5 do tohoto účtu. " +
                            "Vyplní se jen prázdné položky. Původní data zůstanou v telefonu. " +
                            "Souhlas s AI se nepřevádí.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        adoptionConfirm = false
                        adopt()
                    }) { Text("Převzít") }
                },
                dismissButton = {
                    TextButton(onClick = { adoptionConfirm = false }) { Text("Zrušit") }
                },
            )
        }

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
                UnsupportedLegacyData(result.inventory.profileFieldsPresent)
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
                                "Původní ownerless záznam zůstává zachován v tomto telefonu. Nejdřív zkontroluj místní kopii. Kopii můžeš samostatně uložit přes bezpečné v2 porovnání (CAS) v deníku; původní záznam se nikdy automaticky nemaže.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                            )
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
private fun UnsupportedLegacyData(fields: Set<LegacyProfileField>) {
    val remaining = fields.filter { it == LegacyProfileField.AI_ENABLED || it == LegacyProfileField.ONBOARDING_DONE }.toSet()
    if (remaining.isEmpty()) return
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text("Nepřenášená data", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        VSpace(Spacing.tight)
        val names = remaining.sortedBy { it.ordinal }.joinToString { field ->
            when (field) {
                LegacyProfileField.AI_ENABLED -> "souhlas s AI"
                LegacyProfileField.ONBOARDING_DONE -> "průvodce"
                else -> ""
            }
        }
        Text(
            "Staré položky ($names) se nepřevádějí. Souhlas s AI musíš zapnout znovu v tomto účtu. Původní hodnoty zůstávají beze změny.",
            color = TextSecondary,
        )
    }
}

@Composable
private fun LegacyAdoptionCard(
    availability: LegacyAdoptionAvailability?,
    message: String?,
    busy: Boolean,
    onAdopt: () -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text("Profil a AI klíč z beta5", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        VSpace(Spacing.tight)
        when {
            availability == null -> Text("Zjišťuji stará data…", color = TextSecondary)
            !availability.hasAnything ->
                Text("Žádná stará profilová data ani AI klíč nebyly nalezeny.", color = TextSecondary)
            else -> {
                Text("V telefonu zůstaly tyto hodnoty z beta5:", color = TextSecondary)
                VSpace(Spacing.tight)
                availability.name?.let { Text("• Jméno: nalezeno", color = TextPrimary) }
                availability.birthYear?.let { Text("• Rok narození: nalezen", color = TextPrimary) }
                if (availability.keyPresent) {
                    Text("• DeepSeek API klíč: nalezen (hodnota se nezobrazuje)", color = TextPrimary)
                }
                VSpace(Spacing.tight)
                Text(
                    "Převzetí vyplní jen prázdné položky tohoto účtu. Původní data zůstanou v telefonu.",
                    color = TextSecondary,
                )
                VSpace(Spacing.section)
                OutlinedButton(onClick = onAdopt, enabled = !busy) {
                    Text(if (busy) "Převádím…" else "Převzít do tohoto účtu")
                }
            }
        }
        message?.let {
            VSpace(Spacing.tight)
            Text(it, color = TextSecondary)
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
    LegacyRecoveryCopyResult.OWNER_CHANGED -> "Ověřený účet se změnil nebo není dostupný. Kopie nebyla provedena a původní záznam zůstal zachován."
    LegacyRecoveryCopyResult.COPY_MAY_HAVE_COMPLETED_FOR_OLD_ACCOUNT ->
        "Účet se změnil během kopírování. Kopie mohla být uložena jako místní koncept původního účtu. " +
            "Přihlas se znovu k tomuto účtu a ověř ji; původní záznam zůstává zachován. Nic nebylo odesláno."
    LegacyRecoveryCopyResult.PREVIEW_REQUIRED -> "Nejdřív potvrď vlastnictví a zobraz místní náhled."
    LegacyRecoveryCopyResult.RECORD_NOT_RECOVERABLE -> "Tento záznam už nelze bezpečně obnovit. Původní data zůstala zachována."
    LegacyRecoveryCopyResult.FAILED -> "Kopii se nepodařilo dokončit. Původní záznam zůstává zachován; můžeš bezpečně zkusit znovu."
}
