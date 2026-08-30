package cz.digitalnivedomi.diarium.notifications

import android.Manifest
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cz.digitalnivedomi.diarium.R

/**
 * Native notification settings screen (opened from the web UI via
 * `AndroidBridge.openNotificationSettings()`).
 */
class NotificationSettingsActivity : AppCompatActivity() {

    private lateinit var store: NotificationPrefsStore

    private lateinit var reminderEnabled: SwitchCompat
    private lateinit var reminderTime: TextView
    private lateinit var reminderDaysRow: LinearLayout
    private lateinit var smartReminder: SwitchCompat
    private lateinit var weeklyEnabled: SwitchCompat
    private lateinit var weeklyDay: TextView
    private lateinit var weeklyTime: TextView
    private lateinit var monthlyEnabled: SwitchCompat
    private lateinit var monthlyTime: TextView
    private lateinit var openOnTap: SwitchCompat
    private lateinit var soundSwitch: SwitchCompat
    private lateinit var systemStatus: TextView

    private val dayNames = listOf("Po", "Út", "St", "Čt", "Pá", "So", "Ne")

    private var prefs = NotificationPrefs()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification_settings)
        store = NotificationPrefsStore(this)
        prefs = store.load()

        reminderEnabled = findViewById(R.id.reminderEnabled)
        reminderTime = findViewById(R.id.reminderTime)
        reminderDaysRow = findViewById(R.id.reminderDaysRow)
        smartReminder = findViewById(R.id.smartReminder)
        weeklyEnabled = findViewById(R.id.weeklyEnabled)
        weeklyDay = findViewById(R.id.weeklyDay)
        weeklyTime = findViewById(R.id.weeklyTime)
        monthlyEnabled = findViewById(R.id.monthlyEnabled)
        monthlyTime = findViewById(R.id.monthlyTime)
        openOnTap = findViewById(R.id.openOnTap)
        soundSwitch = findViewById(R.id.soundSwitch)
        systemStatus = findViewById(R.id.systemStatus)

        renderPrefs()

        reminderEnabled.setOnCheckedChangeListener { _, checked ->
            prefs = prefs.copy(reminderEnabled = checked); persist()
        }
        reminderTime.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                prefs = prefs.copy(reminderTimeMinutes = h * 60 + m); persist(); renderPrefs()
            }, prefs.reminderTimeMinutes / 60, prefs.reminderTimeMinutes % 60, true).show()
        }
        smartReminder.setOnCheckedChangeListener { _, checked ->
            prefs = prefs.copy(smartReminder = checked); persist()
        }
        weeklyEnabled.setOnCheckedChangeListener { _, checked ->
            prefs = prefs.copy(weeklyEnabled = checked); persist()
        }
        weeklyDay.setOnClickListener { showDayPicker(false) }
        weeklyTime.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                prefs = prefs.copy(weeklyTimeMinutes = h * 60 + m); persist(); renderPrefs()
            }, prefs.weeklyTimeMinutes / 60, prefs.weeklyTimeMinutes % 60, true).show()
        }
        monthlyEnabled.setOnCheckedChangeListener { _, checked ->
            prefs = prefs.copy(monthlyEnabled = checked); persist()
        }
        monthlyTime.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                prefs = prefs.copy(monthlyTimeMinutes = h * 60 + m); persist(); renderPrefs()
            }, prefs.monthlyTimeMinutes / 60, prefs.monthlyTimeMinutes % 60, true).show()
        }
        openOnTap.setOnCheckedChangeListener { _, checked ->
            prefs = prefs.copy(openAppOnTap = checked); persist()
        }
        soundSwitch.setOnCheckedChangeListener { _, checked ->
            prefs = prefs.copy(sound = checked); persist()
        }

        checkSystemNotificationPermission()
    }

    private fun renderPrefs() {
        reminderEnabled.isChecked = prefs.reminderEnabled
        reminderTime.text = fmtTime(prefs.reminderTimeMinutes)
        smartReminder.isChecked = prefs.smartReminder
        weeklyEnabled.isChecked = prefs.weeklyEnabled
        weeklyDay.text = dayNames[prefs.weeklyDay - 1]
        weeklyTime.text = fmtTime(prefs.weeklyTimeMinutes)
        monthlyEnabled.isChecked = prefs.monthlyEnabled
        monthlyTime.text = fmtTime(prefs.monthlyTimeMinutes)
        openOnTap.isChecked = prefs.openAppOnTap
        soundSwitch.isChecked = prefs.sound
        renderDayChips()
    }

    private fun renderDayChips() {
        reminderDaysRow.removeAllViews()
        for (i in 1..7) {
            val selected = i in prefs.reminderDays
            val chip = TextView(this).apply {
                text = dayNames[i - 1]
                textSize = 13f
                setPadding(36, 22, 36, 22) // dp-ish, fine for chips
                setTextColor(if (selected) 0xFF0a0a0f.toInt() else 0xFFe5e7eb.toInt())
                setBackgroundColor(if (selected) 0xFF818cf8.toInt() else 0xFF1f2937.toInt())
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 28 }
            chip.layoutParams = lp
            chip.setOnClickListener {
                val updated = if (i in prefs.reminderDays) prefs.reminderDays - i else prefs.reminderDays + i
                prefs = prefs.copy(reminderDays = updated)
                persist()
                renderDayChips()
            }
            reminderDaysRow.addView(chip)
        }
    }

    private fun showDayPicker(weekly: Boolean = true) {
        val names = listOf("Pondělí", "Úterý", "Středa", "Čtvrtek", "Pátek", "Sobota", "Neděle")
        AlertDialog.Builder(this)
            .setTitle("Den oznámení")
            .setItems(names.toTypedArray()) { _, which ->
                val day = which + 1
                prefs = prefs.copy(weeklyDay = day)
                persist()
                renderPrefs()
            }
            .show()
    }

    private fun persist() {
        store.save(prefs)
        // A settings change re-arms alarms AND clears today's "already
        // reminded" marker, so tuning the time lets the user test again
        // the same day (the marker is what suppressed the 20:00 reminder
        // after the earlier afternoon test).
        store.setLastReminderDate(null)
        NotificationScheduler.rescheduleAll(this)
    }

    private fun fmtTime(minutes: Int): String {
        return String.format("%02d:%02d", minutes / 60, minutes % 60)
    }

    private fun checkSystemNotificationPermission() {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(this).areNotificationsEnabled()
        }
        systemStatus.visibility = if (granted) android.view.View.GONE else android.view.View.VISIBLE
    }
}