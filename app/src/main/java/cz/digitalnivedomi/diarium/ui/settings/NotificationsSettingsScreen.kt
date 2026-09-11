package cz.digitalnivedomi.diarium.ui.settings

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import cz.digitalnivedomi.diarium.R
import cz.digitalnivedomi.diarium.notifications.NotificationPrefs
import cz.digitalnivedomi.diarium.ui.components.GlassCard
import cz.digitalnivedomi.diarium.ui.components.GlassChip
import cz.digitalnivedomi.diarium.ui.components.GlassDivider
import cz.digitalnivedomi.diarium.ui.components.ScreenHeader
import cz.digitalnivedomi.diarium.ui.components.VSpace
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.IndigoLight
import cz.digitalnivedomi.diarium.ui.theme.Spacing
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary

/**
 * "Nastavení notifikací" — the Compose replacement for the old AppCompat
 * `NotificationSettingsActivity` (Activity + XML + manifest entry are gone).
 *
 * Same keys, same defaults and the same Czech labels as that screen, so prefs from
 * the alpha builds keep working; only the surface is new. Every change is written
 * through [NotificationsSettingsDeps.prefsStore], clears the "already reminded"
 * marker (so tuning the time lets the user test the same day) and calls
 * [NotificationsSettingsDeps.onReschedule] to re-arm the alarms.
 *
 * The permission card shows the real system state (notifications / usage access /
 * exact alarms) because a greyed-out switch with no explanation reads as a broken
 * screen. Renders offline with [NotificationsSettingsDeps.offline].
 */
@Composable
fun NotificationsSettingsScreen(deps: NotificationsSettingsDeps) {
    val context = LocalContext.current
    val store = deps.prefsStore
    var prefs by remember { mutableStateOf(store?.load() ?: NotificationPrefs()) }
    var testResult by remember { mutableStateOf<String?>(null) }

    fun apply(updated: NotificationPrefs) {
        prefs = updated
        store?.save(updated)
        store?.setLastReminderDate(null)
        deps.onReschedule(updated)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter)
            .padding(top = Spacing.screenTop, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.section),
    ) {
        ScreenHeader(
            title = "Nastavení notifikací",
            subtitle = "Připomenutí, reporty a čas na obrazovce.",
        )

        // ── Připomenutí ───────────────────────────────────────────────────────
        GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo) {
            SettingsSectionTitle("Denní připomenutí")
            ToggleRow(
                title = "Denní připomenutí",
                subtitle = "„Nezapomeň vyplnit dnešní záznam! 🖊️\"",
                checked = prefs.reminderEnabled,
                onCheckedChange = { apply(prefs.copy(reminderEnabled = it)) },
            )
            GlassDivider()
            TimeRow(
                title = "Čas připomenutí",
                subtitle = "Kdy má pípnout",
                minutes = prefs.reminderTimeMinutes,
                onPick = { apply(prefs.copy(reminderTimeMinutes = it)) },
            )
            GlassDivider()
            LabeledBlock(
                title = "Dny připomenutí",
                subtitle = NotificationSettingsState.daysSummary(prefs.reminderDays),
            ) {
                DayChipRow(
                    days = prefs.reminderDays,
                    onToggle = { day ->
                        apply(prefs.copy(reminderDays = NotificationSettingsState.toggleDay(prefs.reminderDays, day)))
                    },
                )
            }
            GlassDivider()
            ToggleRow(
                title = "Chytré připomenutí",
                subtitle = "Nepřipomínat, když je dnešní záznam vyplněný",
                checked = prefs.smartReminder,
                onCheckedChange = { apply(prefs.copy(smartReminder = it)) },
            )
            GlassDivider()
            ToggleRow(
                title = "Zvuk",
                subtitle = "Kanál: ${NotificationSettingsState.channelLabel(prefs.sound)}",
                checked = prefs.sound,
                onCheckedChange = { apply(prefs.copy(sound = it)) },
            )
        }

        // ── Reporty ───────────────────────────────────────────────────────────
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            SettingsSectionTitle("Reporty")
            ToggleRow(
                title = "Týdenní přehled",
                subtitle = "„📊 Týdenní reflexe\"",
                checked = prefs.weeklyEnabled,
                onCheckedChange = { apply(prefs.copy(weeklyEnabled = it)) },
            )
            GlassDivider()
            LabeledBlock(
                title = "Den týdenního přehledu",
                subtitle = NotificationSettingsState.weeklyDayName(prefs.weeklyDay),
            ) {
                DayChipRow(
                    days = setOf(prefs.weeklyDay),
                    onToggle = { day -> apply(prefs.copy(weeklyDay = day)) },
                )
            }
            GlassDivider()
            TimeRow(
                title = "Čas týdenního přehledu",
                subtitle = null,
                minutes = prefs.weeklyTimeMinutes,
                onPick = { apply(prefs.copy(weeklyTimeMinutes = it)) },
            )
            GlassDivider()
            ToggleRow(
                title = "Měsíční přehled",
                subtitle = "„📊 Měsíční reflexe\" — 1. den v měsíci",
                checked = prefs.monthlyEnabled,
                onCheckedChange = { apply(prefs.copy(monthlyEnabled = it)) },
            )
            GlassDivider()
            TimeRow(
                title = "Čas měsíčního přehledu",
                subtitle = null,
                minutes = prefs.monthlyTimeMinutes,
                onPick = { apply(prefs.copy(monthlyTimeMinutes = it)) },
            )
        }

        // ── Čas na obrazovce ──────────────────────────────────────────────────
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            SettingsSectionTitle("Čas na obrazovce")
            ToggleRow(
                title = "Odesílat čas na obrazovce",
                subtitle = "Večerní snímek dneška a ranní dopočet včerejška",
                checked = prefs.screenSyncEnabled,
                onCheckedChange = { apply(prefs.copy(screenSyncEnabled = it)) },
            )
            GlassDivider()
            TimeRow(
                title = "Večerní odeslání",
                subtitle = "Snímek dneška",
                minutes = prefs.screenSyncEveningMinutes,
                onPick = { apply(prefs.copy(screenSyncEveningMinutes = it)) },
            )
            GlassDivider()
            TimeRow(
                title = "Ranní dopočet",
                subtitle = "Dopočet včerejška",
                minutes = prefs.screenSyncMorningMinutes,
                onPick = { apply(prefs.copy(screenSyncMorningMinutes = it)) },
            )
        }

        // ── Klepnutí a systémová oprávnění ────────────────────────────────────
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            SettingsSectionTitle("Klepnutí a oprávnění")
            InfoRow(
                title = "Klepnutí na notifikaci",
                subtitle = "Vždy otevře Přehled",
                value = "Přehled",
            )
            GlassDivider()
            StatusRow(
                title = "Notifikace v systému",
                chip = NotificationSettingsState.permissionChip(deps.notificationPermission),
                accent = accentFor(deps.notificationPermission),
                hint = NotificationSettingsState.notificationPermissionHint(deps.notificationPermission),
            )
            GlassDivider()
            StatusRow(
                title = "Přístup ke statistikám používání",
                chip = NotificationSettingsState.usageAccessChip(deps.usageAccess),
                accent = accentFor(deps.usageAccess),
                hint = NotificationSettingsState.usageAccessHint(deps.usageAccess),
                actionLabel = "Otevřít nastavení",
                onAction = deps.onOpenUsageAccessSettings,
            )
            GlassDivider()
            StatusRow(
                title = "Přesné alarmy",
                chip = NotificationSettingsState.exactAlarmChip(deps.exactAlarmAllowed),
                accent = if (deps.exactAlarmAllowed) IndigoLight else WarnColor,
                hint = NotificationSettingsState.exactAlarmHint(deps.exactAlarmAllowed),
            )
        }

        // ── Vyzkoušet notifikaci ──────────────────────────────────────────────
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            SettingsSectionTitle("Vyzkoušet")
            Text(
                text = "Pošle testovací připomenutí teď, na kanálu ${NotificationSettingsState.channelLabel(prefs.sound)}.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            VSpace(Spacing.block)
            OutlinedButton(
                onClick = { testResult = postTestNotification(context, prefs.sound) },
            ) {
                Text("Vyzkoušet notifikaci")
            }
            val result = testResult
            if (result != null) {
                VSpace(Spacing.tight)
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        }
    }
}

// ── Small private building blocks (glass only — no flat Material surfaces) ─────

private val WarnColor = Color(0xFFF87171)

private fun accentFor(state: PermissionState): Color = when (state) {
    PermissionState.GRANTED -> IndigoLight
    PermissionState.DENIED -> WarnColor
    PermissionState.UNDECIDED -> Indigo
}

private fun accentFor(state: UsageAccessState): Color = when (state) {
    UsageAccessState.ALLOWED -> IndigoLight
    UsageAccessState.DENIED, UsageAccessState.ERRORED -> WarnColor
    UsageAccessState.UNDECIDED -> Indigo
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = TextSecondary,
        fontWeight = FontWeight.SemiBold,
    )
    VSpace(Spacing.tight)
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowTexts(title = title, subtitle = subtitle, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Indigo.copy(alpha = 0.70f),
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = Color.White.copy(alpha = 0.08f),
                uncheckedBorderColor = TextSecondary.copy(alpha = 0.45f),
            ),
        )
    }
}

@Composable
private fun TimeRow(
    title: String,
    subtitle: String?,
    minutes: Int,
    onPick: (Int) -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                TimePickerDialog(
                    context,
                    { _, hour, minute -> onPick(hour * 60 + minute) },
                    NotificationSettingsState.hourOf(minutes),
                    NotificationSettingsState.minuteOf(minutes),
                    true,
                ).show()
            }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowTexts(title = title, subtitle = subtitle, modifier = Modifier.weight(1f))
        GlassChip(text = NotificationSettingsState.timeLabel(minutes), accent = Indigo)
    }
}

@Composable
private fun LabeledBlock(title: String, subtitle: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        RowTexts(title = title, subtitle = subtitle, modifier = Modifier.fillMaxWidth())
        VSpace(Spacing.block)
        content()
    }
}

@Composable
private fun InfoRow(title: String, subtitle: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowTexts(title = title, subtitle = subtitle, modifier = Modifier.weight(1f))
        GlassChip(text = value, accent = Indigo)
    }
}

@Composable
private fun StatusRow(
    title: String,
    chip: String,
    accent: Color,
    hint: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
            )
            GlassChip(text = chip, accent = accent)
        }
        VSpace(Spacing.tight)
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        if (actionLabel != null && onAction != null) {
            VSpace(Spacing.block)
            OutlinedButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun RowTexts(title: String, subtitle: String?, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
    }
}

@Composable
private fun DayChipRow(days: Set<Int>, onToggle: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (day in 1..7) {
            DayChip(
                label = NotificationSettingsState.weeklyDayLabel(day),
                selected = day in days,
                onClick = { onToggle(day) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DayChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(if (selected) IndigoLight.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.07f))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Color(0xFF0A0A0F) else TextPrimary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
        )
    }
}

// ── Test notification ─────────────────────────────────────────────────────────

private const val CHANNEL_SOUND = "diarium_reminders"
private const val CHANNEL_SILENT = "diarium_reminders_silent"
private const val TEST_NOTIFICATION_ID = 99001

/**
 * Posts one notification on the very channel the real reminder uses, creating the
 * channel when it does not exist yet (idempotent). Returns the Czech status line
 * for the card.
 */
@SuppressLint("MissingPermission")
private fun postTestNotification(context: Context, sound: Boolean): String {
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
        return "Notifikace jsou v systému vypnuté — test se neodeslal."
    }
    val channelId = if (sound) CHANNEL_SOUND else CHANNEL_SILENT
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(channelId) == null) {
                val channel = NotificationChannel(
                    channelId,
                    NotificationSettingsState.channelLabel(sound),
                    if (sound) NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_LOW,
                )
                manager.createNotificationChannel(channel)
            }
        }
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_diarium)
            .setContentTitle("Diarium")
            .setContentText("Nezapomeň vyplnit dnešní záznam! 🖊️")
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(TEST_NOTIFICATION_ID, notification)
        "Testovací notifikace odeslána na kanál ${NotificationSettingsState.channelLabel(sound)}."
    } catch (e: Exception) {
        "Test se nepovedl: ${e.message ?: e::class.java.simpleName}"
    }
}
