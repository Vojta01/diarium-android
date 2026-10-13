@file:OptIn(ExperimentalFoundationApi::class)

package cz.digitalnivedomi.diarium.ui.reports

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.digitalnivedomi.diarium.core.data.AiReport
import cz.digitalnivedomi.diarium.ui.components.BrandSpinner
import cz.digitalnivedomi.diarium.ui.components.EmptyState
import cz.digitalnivedomi.diarium.ui.components.GlassCard
import cz.digitalnivedomi.diarium.ui.components.ScreenSubtitle
import cz.digitalnivedomi.diarium.ui.components.VSpace
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.components.AccentBar
import cz.digitalnivedomi.diarium.ui.components.accentGlow
import cz.digitalnivedomi.diarium.ui.components.rememberHaptics
import cz.digitalnivedomi.diarium.ui.theme.Dimens
import cz.digitalnivedomi.diarium.ui.theme.Gradients
import cz.digitalnivedomi.diarium.ui.theme.IndigoLight
import cz.digitalnivedomi.diarium.ui.theme.Spacing
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Test tags — the Compose tests address the controls by these, never by Czech copy. */
const val ReportsRetryButtonTag = "reports-retry"
const val ReportsWeeklyButtonTag = "reports-generate-weekly"
const val ReportsMonthlyButtonTag = "reports-generate-monthly"
const val ReportsStatusTag = "reports-status"
const val ReportsAiOffBannerTag = "reports-ai-off"

/** The screen's one warning accent — the same red the export and notification screens use. */
private val WarnColor = Color(0xFFF87171)

/** The paragraph leading: a report is read as prose, so it is looser than a form label. */
private val BodyLineHeight = 22.sp

/** What one card is doing right now — the only thing the card switches on. */
private enum class ReportPhase {
    /** Reading the newest report of this type from Supabase. */
    LOADING,

    /** A report is in memory and rendered below. */
    READY,

    /** The account has no report of this type yet; the card invites the user to mint one. */
    EMPTY,

    /** The read failed (or the screen is offline); the card shows a retry. */
    ERROR,
}

/**
 * "AI Přehledy" — the native twin of the web's weekly/monthly report page.
 *
 * The web reads the newest row per type out of `ai_reports` and renders its Czech prose;
 * RLS scopes those rows to the signed-in user, so the app reads the same table with the
 * user's own JWT and never needs a server key. The report is produced by DeepSeek on the
 * backend, so generating is not a local job: the button asks the backend route to mint
 * one and then the card is refreshed from the table — [ReportsDeps] carries both halves.
 *
 * Both types load together on the first composition and again on every retry, but the two
 * cards then move independently: generating a weekly report must not blank the monthly one.
 *
 * With no repository ([ReportsDeps.offline]) the screen says the reports are unavailable
 * instead of showing the "nothing generated yet" card: a screen that could not reach the
 * server must never look like an empty diary.
 *
 * With the AI master switch off ([ReportsDeps.profile]) the screen still shows the stored
 * reports — reading one is not a generation — but a banner above the cards says why
 * nothing new can be minted and every generate button is disabled.
 *
 * [focusType] is the deep link from the weekly/monthly reflection notification: the
 * matching card is scrolled into view and briefly glows (see the focus effect below).
 * That focus is one-shot, so [onFocusConsumed] lets the parent clear it once it is spent.
 */
@Composable
fun ReportsScreen(
    deps: ReportsDeps,
    focusType: String? = null,
    onFocusConsumed: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()

    // Maps keyed by report type ("weekly"/"monthly"). A snapshot-backed map keeps the two
    // cards independent and lets a coroutine write a single entry without holding a stale
    // copy of the whole state, which a plain `remember { mutableStateOf(map) }` would.
    val reports = remember { mutableStateMapOf<String, AiReport>() }
    val phases = remember { mutableStateMapOf<String, ReportPhase>() }
    val details = remember { mutableStateMapOf<String, String>() }
    val statuses = remember { mutableStateMapOf<String, String>() }
    val generating = remember { mutableStateMapOf<String, Boolean>() }

    var reloadTick by remember { mutableStateOf(0) }

    // The AI master switch (Nastavení → AI). With it off nothing on this screen may
    // reach the AI: the banner above the cards says so and every generate button is
    // disabled. Read once per screen like the settings card reads it; a null profile
    // (offline/test wiring) leaves generating exactly as it was before the switch.
    var aiEnabled by remember { mutableStateOf(true) }
    LaunchedEffect(deps) {
        aiEnabled = withContext(Dispatchers.IO) { deps.profile?.load()?.aiEnabled ?: true }
    }

    // One bring-into-view requester per card, keyed off ReportsState.TYPES, so the
    // notification deep link can scroll exactly the card it names.
    val requesters = remember {
        ReportsState.TYPES.associateWith { BringIntoViewRequester() }
    }
    var highlightType by remember { mutableStateOf<String?>(null) }

    // Consumes the reflection notification's one-shot focus: scroll to the matching card,
    // glow it briefly, then hand the spent focus back to the parent to clear.
    LaunchedEffect(focusType) {
        val type = focusType ?: return@LaunchedEffect
        if (type !in ReportsState.TYPES) { onFocusConsumed(); return@LaunchedEffect }
        delay(250) // let the first layout settle so bringIntoView has a target
        requesters[type]?.bringIntoView()
        highlightType = type
        delay(2000)
        highlightType = null
        onFocusConsumed()
    }

    // The report, its phase and its reason always move together: a card can never end up
    // showing a body it did not load, or an error with nothing to explain it.
    val applyReport: (String, AiReport) -> Unit = { type, report ->
        reports[type] = report
        phases[type] = ReportPhase.READY
        details.remove(type)
    }
    val applyEmpty: (String) -> Unit = { type ->
        reports.remove(type)
        phases[type] = ReportPhase.EMPTY
        details.remove(type)
    }
    val applyLoadFailure: (String, String?) -> Unit = { type, message ->
        phases[type] = ReportPhase.ERROR
        details[type] = ReportsState.loadFailedMessage(message)
    }

    // Runs on the first composition and on every retry (`reloadTick`).
    LaunchedEffect(deps, reloadTick) {
        val repository = deps.repository
        if (repository == null) {
            ReportsState.TYPES.forEach { type ->
                reports.remove(type)
                phases[type] = ReportPhase.ERROR
                details[type] = ReportsState.OFFLINE_MESSAGE
            }
            return@LaunchedEffect
        }
        ReportsState.TYPES.forEach { type ->
            phases[type] = ReportPhase.LOADING
            details.remove(type)
            statuses.remove(type)
        }
        // The two reads are independent, so they run together: the screen waits once for
        // the slower of the two instead of twice in a row.
        coroutineScope {
            ReportsState.TYPES.forEach { type ->
                launch {
                    repository.latest(type)
                        .onSuccess { report ->
                            if (report == null) applyEmpty(type) else applyReport(type, report)
                        }
                        .onFailure { error -> applyLoadFailure(type, error.message) }
                }
            }
        }
    }

    val onGenerate: (String) -> Unit = { type ->
        val repository = deps.repository
        // One run per card: a second tap while the server is still writing is ignored
        // rather than queueing another generation. The master switch is checked here
        // as well as on the button, so no path can reach the AI while it is off.
        if (aiEnabled && repository != null && generating[type] != true) {
            generating[type] = true
            statuses.remove(type)
            scope.launch {
                val previousId = reports[type]?.id
                val result = repository.generate(type, previousId = previousId)
                val minted = result.getOrNull()
                result.onFailure { error ->
                    statuses[type] = ReportsState.generateFailedMessage(error.message)
                }
                if (minted != null) applyReport(type, minted)

                // Refresh either way: after a poll timeout the row may have landed a moment
                // later, and after a success this is what makes the card match the server.
                val refreshed = repository.latest(type)
                refreshed.onSuccess { report ->
                    if (report != null) applyReport(type, report)
                }.onFailure { error ->
                    // The card keeps the report it already shows; only an empty card turns
                    // into an error, so a refresh hiccup cannot hide a report from the user.
                    if (reports[type] == null) applyLoadFailure(type, error.message)
                }

                if (minted == null && refreshed.getOrNull() == null) {
                    // Nothing was produced and nothing is stored: the timeout sentence, not
                    // an empty card pretending no generation was ever asked for.
                    if (statuses[type] == null) statuses[type] = ReportsState.GEN_FAILED_MESSAGE
                    if (reports[type] == null) {
                        phases[type] = ReportPhase.EMPTY
                        details.remove(type)
                    }
                }
                generating[type] = false
            }
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
        ScreenSubtitle(text = ReportsState.SUBTITLE)

        // The master switch off: a banner card above everything, and the generate
        // buttons below disabled. Stored reports stay readable — only minting new
        // ones is unavailable while the AI is off.
        if (!aiEnabled) {
            AiOffBanner()
        }

        ReportsState.TYPES.forEach { type ->
            ReportCard(
                type = type,
                online = deps.online,
                phase = if (deps.online) phases[type] ?: ReportPhase.LOADING else ReportPhase.ERROR,
                report = reports[type],
                detail = if (deps.online) details[type] else ReportsState.OFFLINE_MESSAGE,
                status = statuses[type],
                generating = generating[type] == true,
                aiEnabled = aiEnabled,
                onGenerate = { onGenerate(type) },
                onRetry = { reloadTick++ },
                focusRequester = requesters.getValue(type),
                highlighted = highlightType == type,
            )
        }
    }
}

/**
 * The AI master switch is off (Nastavení → AI): one banner card above the report cards
 * says so, and every generate button on the screen is disabled — nothing tapped here
 * may reach the AI.
 */
@Composable
private fun AiOffBanner() {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ReportsAiOffBannerTag),
        accent = WarnColor,
    ) {
        Text(
            text = ReportsState.AI_OFF_BANNER,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
        )
    }
}

/**
 * One kind of report: the title, the period it covers, the prose, the generate action and
 * — when that kind has nothing yet — the invitation to mint the first one.
 */
@Composable
private fun ReportCard(
    type: String,
    online: Boolean,
    phase: ReportPhase,
    report: AiReport?,
    detail: String?,
    status: String?,
    generating: Boolean,
    /** The AI master switch: with it off the generate button is disabled. */
    aiEnabled: Boolean,
    onGenerate: () -> Unit,
    onRetry: () -> Unit,
    focusRequester: BringIntoViewRequester,
    highlighted: Boolean,
) {
    val haptics = rememberHaptics()
    // The deep-link glow: invisible at rest (alpha 0, so an unfocused card looks exactly
    // as before), it fades in while this card is the focus target and out shortly after.
    val glow by animateFloatAsState(
        targetValue = if (highlighted) 0.45f else 0f,
        animationSpec = tween(durationMillis = 450),
        label = "report-focus-glow",
    )
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(focusRequester)
            .accentGlow(IndigoLight, alpha = glow),
        accent = if (phase == ReportPhase.ERROR) WarnColor else Indigo,
    ) {
        Text(
            text = ReportsState.kindLabel(type),
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )
        report?.let { shown ->
            VSpace(Spacing.tight)
            Text(
                text = ReportsState.periodLabel(shown.periodStart, shown.periodEnd),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            val created = ReportsState.createdLabel(shown.createdAt)
            if (created.isNotEmpty()) {
                Text(
                    text = created,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }
        }
        VSpace(Spacing.block)

        when (phase) {
            ReportPhase.LOADING -> Row(verticalAlignment = Alignment.CenterVertically) {
                BrandSpinner()
                Spacer(Modifier.width(12.dp))
                Text(
                    text = ReportsState.LOADING_LABEL,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.testTag(ReportsStatusTag),
                )
            }

            ReportPhase.ERROR -> {
                Text(
                    text = ReportsState.LOAD_FAILED_TITLE,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
                VSpace(Spacing.tight)
                Text(
                    text = detail ?: ReportsState.loadFailedMessage(null),
                    style = MaterialTheme.typography.bodyMedium,
                    color = WarnColor,
                    modifier = Modifier.testTag(ReportsStatusTag),
                )
            }

            ReportPhase.EMPTY -> EmptyState(
                emoji = "🤖",
                title = ReportsState.EMPTY_TITLE,
                message = ReportsState.EMPTY_MESSAGE,
            )

            ReportPhase.READY -> ReportBody(content = report?.content.orEmpty())
        }

        if (online) {
            VSpace(Spacing.block)
            Button(
                onClick = {
                    haptics.light()
                    onGenerate()
                },
                enabled = !generating && aiEnabled,
                shape = RoundedCornerShape(Dimens.radiusCard),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Indigo,
                    contentColor = Color.White,
                    disabledContainerColor = Indigo.copy(alpha = 0.45f),
                    disabledContentColor = Color.White.copy(alpha = 0.70f),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Dimens.controlHeight)
                    .accentGlow(Indigo, alpha = 0.30f)
                    .testTag(generateTag(type)),
            ) {
                Text(
                    text = if (generating) ReportsState.GENERATING_LABEL
                    else ReportsState.generateLabel(type),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (generating) {
                VSpace(Spacing.tight)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrandSpinner()
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = ReportsState.GENERATING_LABEL,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
            }
            if (phase == ReportPhase.ERROR) {
                VSpace(Spacing.block)
                Button(
                    onClick = onRetry,
                    shape = RoundedCornerShape(Dimens.radiusCard),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WarnColor.copy(alpha = 0.16f),
                        contentColor = WarnColor,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Dimens.controlHeight)
                        .testTag(ReportsRetryButtonTag),
                ) {
                    Text(ReportsState.RETRY, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // The generation note lives outside the body branch, so a failure is visible
        // whether the card shows a fresh report, a stale one or nothing at all.
        if (phase != ReportPhase.LOADING && phase != ReportPhase.ERROR) {
            status?.let { message ->
                VSpace(Spacing.tight)
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = IndigoLight,
                    modifier = Modifier.testTag(ReportsStatusTag),
                )
            }
        }
    }
}

/**
 * The report body: headings, paragraphs and bullets laid out from [ReportsState.blocks],
 * with the `**bold**` runs styled by [ReportsState.inlineBold]. Nothing here knows about
 * markdown — the splitter already did the work, which is what keeps it testable.
 */
@Composable
private fun ReportBody(content: String) {
    val paragraph = MaterialTheme.typography.bodyMedium.copy(lineHeight = BodyLineHeight)

    ReportsState.blocks(content).forEachIndexed { index, block ->
        if (index > 0) VSpace(Spacing.block)
        when (block) {
            is Block.Heading -> Row(verticalAlignment = Alignment.CenterVertically) {
                AccentBar(colors = Gradients.brand)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = inlineText(block.text),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                )
            }

            is Block.Bullet -> Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Text(text = "•  ", style = paragraph, color = TextPrimary)
                Text(
                    text = inlineText(block.text),
                    style = paragraph,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f),
                )
            }

            is Block.Paragraph -> Text(
                text = inlineText(block.text),
                style = paragraph,
                color = TextPrimary,
            )
        }
    }
}

/** One line with its `**bold**` runs turned into bold spans. */
private fun inlineText(text: String) = buildAnnotatedString {
    ReportsState.inlineBold(text).forEach { chunk ->
        if (chunk.bold) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(chunk.text) }
        } else {
            append(chunk.text)
        }
    }
}

/** The button tag for a kind; any type other than monthly is the weekly button. */
private fun generateTag(type: String): String =
    if (type == ReportsState.TYPE_MONTHLY) ReportsMonthlyButtonTag else ReportsWeeklyButtonTag
