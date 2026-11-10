package cz.digitalnivedomi.diarium.ui.settings

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.core.data.LegacySecretPresence
import cz.digitalnivedomi.diarium.ui.theme.DiariumTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class AiSettingsSectionTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `unknown legacy key state shows a warning instead of claiming absence`() {
        val offline = AiSettingsDeps.offline()
        val deps = AiSettingsDeps(
            keyStatus = { LegacySecretPresence.UNKNOWN },
            saveKey = { false },
            removeKey = {},
            openKeyPage = {},
            legacyValueStatus = { LegacySecretPresence.UNKNOWN },
            profile = offline.profile,
        )

        compose.setContent {
            DiariumTheme {
                AiSettingsSection(deps)
            }
        }

        compose.onNodeWithTag("ai_legacy_key_status_unknown").assertExists()
        compose.onNodeWithTag("ai_key_status_unknown").assertExists()
        compose.onNodeWithTag("ai_key_state").assertTextEquals("Stav klíče nelze ověřit")
        compose.onNodeWithText("Žádný klíč").assertDoesNotExist()
    }
}
