package cz.digitalnivedomi.diarium.backup

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import cz.digitalnivedomi.diarium.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRulesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `legacy rules exclude only credentials and keep default backup scope`() {
        val legacy = readRules(R.xml.backup_rules).getValue("legacy")

        assertTrue("no allow-list should narrow existing backup behavior", legacy.includes.isEmpty())
        assertEquals(credentialExclusions, legacy.excludes)
        assertTrue("drafts, outbox, profile and diary cache stay eligible for backup", legacy.excludes.intersect(journalDataPaths).isEmpty())
    }

    @Test
    fun `cloud backup and device transfer share targeted credential exclusions`() {
        val rules = readRules(R.xml.data_extraction_rules)
        val cloud = rules.getValue("cloud-backup")
        val transfer = rules.getValue("device-transfer")

        assertEquals(cloud, transfer)
        assertTrue("no allow-list should narrow existing transfer behavior", cloud.includes.isEmpty())
        assertEquals(credentialExclusions, cloud.excludes)
        assertTrue(cloud.excludes.intersect(journalDataPaths).isEmpty())
    }

    @Test
    fun `manifest keeps backup enabled and references both rule resources`() {
        val appInfo = context.applicationInfo

        assertTrue(appInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0)
        assertEquals(R.xml.backup_rules, appInfo.resourceField("fullBackupContent"))
        assertEquals(R.xml.data_extraction_rules, appInfo.resourceField("dataExtractionRulesRes"))
    }

    private fun ApplicationInfo.resourceField(fieldName: String): Int =
        javaClass.getField(fieldName).getInt(this)

    private fun readRules(resourceId: Int): Map<String, Rules> {
        val parser = context.resources.getXml(resourceId)
        val result = mutableMapOf<String, Rules>()
        var section: String? = null
        try {
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                when (parser.name) {
                    "full-backup-content" -> {
                        section = "legacy"
                        result.putIfAbsent("legacy", Rules(mutableSetOf(), mutableSetOf()))
                    }
                    "cloud-backup", "device-transfer" -> {
                        val name = parser.name
                        section = name
                        result.putIfAbsent(name, Rules(mutableSetOf(), mutableSetOf()))
                    }
                    "include", "exclude" -> {
                        val current = section ?: error("Backup rule outside a backup section")
                        val domain = parser.getAttributeValue(null, "domain")
                        val path = parser.getAttributeValue(null, "path")
                        val value = "$domain:$path"
                        val rules = result.getOrPut(current) { Rules(mutableSetOf(), mutableSetOf()) }
                        if (parser.name == "include") rules.includes.add(value) else rules.excludes.add(value)
                    }
                }
            }
        } finally {
            parser.close()
        }
        return result
    }

    private data class Rules(val includes: MutableSet<String>, val excludes: MutableSet<String>)

    private companion object {
        val credentialExclusions = setOf(
            "sharedpref:diarium_ai_secrets.xml",
            "sharedpref:diarium_session.xml",
            "sharedpref:diarium_oauth_attempt.xml",
            "sharedpref:diarium_fcm.xml",
            "sharedpref:com.google.android.gms.appid.xml",
        )
        val journalDataPaths = setOf(
            "sharedpref:diarium_pending_saves.xml",
            "sharedpref:diarium_photo_candidates.xml",
            "file:datastore/diarium_drafts.preferences_pb",
            "file:datastore/diarium_profile.preferences_pb",
            "file:datastore/diarium_goals.preferences_pb",
            "file:entries_cache.json",
        )
    }
}
