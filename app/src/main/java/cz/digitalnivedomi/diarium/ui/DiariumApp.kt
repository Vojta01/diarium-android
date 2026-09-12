package cz.digitalnivedomi.diarium.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import cz.digitalnivedomi.diarium.ui.achievements.AchievementsDeps
import cz.digitalnivedomi.diarium.ui.achievements.AchievementsScreen
import cz.digitalnivedomi.diarium.ui.components.GlassDivider
import cz.digitalnivedomi.diarium.ui.components.SubScreenEntry
import cz.digitalnivedomi.diarium.ui.goals.GoalsDeps
import cz.digitalnivedomi.diarium.ui.goals.GoalsScreen
import cz.digitalnivedomi.diarium.ui.scales.ScalesDeps
import cz.digitalnivedomi.diarium.ui.scales.ScalesScreen
import cz.digitalnivedomi.diarium.ui.templates.TemplatesDeps
import cz.digitalnivedomi.diarium.ui.templates.TemplatesScreen
import cz.digitalnivedomi.diarium.ui.reports.ReportsDeps
import cz.digitalnivedomi.diarium.ui.reports.ReportsScreen
import cz.digitalnivedomi.diarium.ui.reports.ReportsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import cz.digitalnivedomi.diarium.auth.AuthStateHolder
import cz.digitalnivedomi.diarium.auth.AuthStatus
import cz.digitalnivedomi.diarium.ui.auth.LoginScreen
import cz.digitalnivedomi.diarium.ui.checkin.CheckInRoute
import cz.digitalnivedomi.diarium.ui.components.DiariumBackground
import cz.digitalnivedomi.diarium.ui.export.ExportDeps
import cz.digitalnivedomi.diarium.ui.export.ExportScreen
import cz.digitalnivedomi.diarium.ui.components.GlassCard
import cz.digitalnivedomi.diarium.ui.components.GlassChip
import cz.digitalnivedomi.diarium.ui.components.ScreenHeader
import cz.digitalnivedomi.diarium.ui.components.VSpace
import cz.digitalnivedomi.diarium.ui.history.HistoryRoute
import cz.digitalnivedomi.diarium.ui.home.DashboardRoute
import cz.digitalnivedomi.diarium.ui.nav.Routes
import cz.digitalnivedomi.diarium.ui.nav.TopLevelDestination
import cz.digitalnivedomi.diarium.ui.settings.NotificationsSettingsDeps
import cz.digitalnivedomi.diarium.ui.settings.NotificationsSettingsScreen
import cz.digitalnivedomi.diarium.ui.stats.StatsRoute
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.IndigoLight
import cz.digitalnivedomi.diarium.ui.theme.Spacing
import cz.digitalnivedomi.diarium.ui.theme.Surface1
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import cz.digitalnivedomi.diarium.ui.theme.Violet

/**
 * App shell and session gate.
 *
 * UNAUTHENTICATED → [LoginScreen] full-screen, no bottom bar (there is nothing
 * to navigate to yet). AUTHENTICATED → the usual scaffold with the four tabs.
 *
 * The gate is driven by [AuthStateHolder], which reads the persisted session, so
 * the flip happens the moment the OAuth deep link is handled — no restart.
 */
@Composable
fun DiariumApp(
    authDeepLink: State<String?>,
    authState: AuthStateHolder,
    onSignIn: () -> Unit = {},
    onAuthDeepLink: (String) -> Boolean = { false },
) {
    val authStatus by authState.status.collectAsState()
    var signInInProgress by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }

    // Deep link delivered by MainActivity (cold start or onNewIntent). The
    // callback parses + persists the session and flips AuthStateHolder.
    LaunchedEffect(authDeepLink.value) {
        val link = authDeepLink.value ?: return@LaunchedEffect
        signInInProgress = false
        authError = if (onAuthDeepLink(link)) null else "Přihlášení se nepovedlo. Zkus to prosím znovu."
    }

    LaunchedEffect(authStatus) {
        if (authStatus == AuthStatus.AUTHENTICATED) {
            signInInProgress = false
            authError = null
        }
    }

    // Coming back from the Custom Tab without completing sign-in must not leave
    // the button spinning forever.
    LifecycleResumeEffect(Unit) {
        if (authState.status.value == AuthStatus.UNAUTHENTICATED) signInInProgress = false
        onPauseOrDispose { }
    }

    DiariumBackground {
        when (authStatus) {
            AuthStatus.UNAUTHENTICATED -> LoginScreen(
                isSigningIn = signInInProgress,
                errorMessage = authError,
                onSignIn = {
                    authError = null
                    signInInProgress = true
                    onSignIn()
                },
            )

            AuthStatus.AUTHENTICATED -> AuthenticatedScaffold(onSignOut = authState::signOut)
        }
    }
}

/** The signed-in shell: bottom-bar navigation over the four top-level screens. */
@Composable
private fun AuthenticatedScaffold(onSignOut: () -> Unit) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // The day the calendar (or the dashboard's "Dnes" card) asked the check-in to open.
    // The check-in screen consumes it, so a later plain tap on "Dnes" starts on today
    // again instead of jumping back to a day picked in the calendar.
    var requestedDate by rememberSaveable { mutableStateOf<String?>(null) }

    /** Switches tabs without stacking a second copy of the destination. */
    fun switchTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            BottomBar(
                currentRoute = currentRoute,
                onSelect = { destination -> switchTab(destination.route) },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Both insets are applied here so every tab starts below the status
                // bar (the Dashboard title used to collide with it) while the bottom
                // bar keeps its reserved space. Side insets are left untouched and no
                // screen adds its own top padding, so this is a single correct inset.
                .padding(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding(),
                ),
        ) {
            NavHost(
                navController = navController,
                // The overview is the app's front door: the numbers first, the form
                // one tap away — the same order the web app uses.
                startDestination = Routes.HOME,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(Routes.HOME) {
                    DashboardRoute(
                        // Switching tabs (not pushing a second tab) so the bottom bar
                        // stays in the state it would be in had check-in been tapped.
                        // A pushed screen keeps its own back-stack entry for the same destination,
                        // which is why the popUpTo/extras mirror BottomBar's onSelect.
                        onOpenCheckIn = { date ->
                            requestedDate = date
                            switchTab(Routes.CHECK_IN)
                        },
                        // The M5 screens (cíle, škály, šablony, odznaky) have no tab of
                        // their own, so the dashboard's entry card pushes them here and
                        // the back arrow in SubScreenChrome pops them again.
                        onOpen = { route ->
                            navController.navigate(route) { launchSingleTop = true }
                        },
                    )
                }
                composable(Routes.CHECK_IN) {
                    CheckInRoute(
                        requestedDate = requestedDate,
                        onRequestedDateConsumed = { requestedDate = null },
                    )
                }
                composable(Routes.HISTORY) {
                    HistoryRoute(
                        onOpenCheckIn = { date ->
                            requestedDate = date
                            switchTab(Routes.CHECK_IN)
                        },
                    )
                }
                composable(Routes.STATS) { StatsRoute() }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        onSignOut = onSignOut,
                        onOpen = { route ->
                            navController.navigate(route) { launchSingleTop = true }
                        },
                    )
                }
                composable(Routes.GOALS) {
                    val context = LocalContext.current
                    val deps = remember(context) { GoalsDeps.forContext(context) }
                    SubScreenChrome(title = "Cíle", onBack = { navController.popBackStack() }) {
                        GoalsScreen(deps = deps)
                    }
                }
                composable(Routes.SCALES) {
                    val context = LocalContext.current
                    val deps = remember(context) { ScalesDeps.forContext(context) }
                    SubScreenChrome(title = "Škály", onBack = { navController.popBackStack() }) {
                        ScalesScreen(deps = deps)
                    }
                }
                composable(Routes.TEMPLATES) {
                    val context = LocalContext.current
                    val deps = remember(context) { TemplatesDeps.forContext(context) }
                    SubScreenChrome(title = "Šablony poznámek", onBack = { navController.popBackStack() }) {
                        TemplatesScreen(deps = deps)
                    }
                }
                composable(Routes.ACHIEVEMENTS) {
                    val context = LocalContext.current
                    val deps = remember(context) { AchievementsDeps.forContext(context) }
                    SubScreenChrome(title = "Odznaky", onBack = { navController.popBackStack() }) {
                        AchievementsScreen(deps = deps)
                    }
                }
                composable(Routes.NOTIFICATIONS) {
                    val context = LocalContext.current
                    // Permission state lives in the system, so it has to be read
                    // again every time the screen resumes — the user taps a row,
                    // grants it in Settings and comes back expecting to see it.
                    var permissionTick by remember { mutableStateOf(0) }
                    LifecycleResumeEffect(Unit) {
                        permissionTick++
                        onPauseOrDispose { }
                    }
                    val deps = remember(context, permissionTick) {
                        NotificationsSettingsDeps.forContext(context)
                    }
                    SubScreenChrome(title = "Nastavení notifikací", onBack = { navController.popBackStack() }) {
                        NotificationsSettingsScreen(deps = deps)
                    }
                }
                composable(Routes.EXPORT) {
                    val context = LocalContext.current
                    val deps = remember(context) { ExportDeps.forContext(context) }
                    SubScreenChrome(title = "Export do CSV", onBack = { navController.popBackStack() }) {
                        ExportScreen(deps = deps)
                    }
                }
                composable(Routes.REPORTS) {
                    val context = LocalContext.current
                    val deps = remember(context) { ReportsDeps.forContext(context) }
                    // The chrome carries the title, which comes from the same object the
                    // screen uses, so the two cannot drift apart.
                    SubScreenChrome(title = ReportsState.TITLE, onBack = { navController.popBackStack() }) {
                        ReportsScreen(deps = deps)
                    }
                }
            }
        }
    }
}

/**
 * Settings: the account action, plus the way into the M5 sub-screens.
 *
 * These four used to be unreachable ("Cíle, připomínky a export dorazí v dalším
 * kroku"). The bottom bar has no room for four more tabs, so they are opened
 * from here and from the dashboard, each with its own back arrow.
 */
@Composable
private fun SettingsScreen(onSignOut: () -> Unit, onOpen: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter)
            .padding(top = Spacing.screenTop, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.section),
    ) {
        ScreenHeader(
            title = "Nastavení",
            subtitle = "Účet, cíle a připomínky na jednom místě.",
        )
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Deník",
                style = MaterialTheme.typography.titleMedium,
            )
            VSpace(4)
            Text(
                text = "Cíle se streakem, vlastní škály, šablony poznámek a odznaky.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            VSpace(6)
            SubScreenEntry(
                emoji = "🎯",
                title = "Cíle",
                hint = "Streak a plnění za období",
                onClick = { onOpen(Routes.GOALS) },
            )
            GlassDivider()
            SubScreenEntry(
                emoji = "📏",
                title = "Škály",
                hint = "Přidat, upravit, rozložení za 30 dní",
                onClick = { onOpen(Routes.SCALES) },
            )
            GlassDivider()
            SubScreenEntry(
                emoji = "📝",
                title = "Šablony poznámek",
                hint = "Text, který vložíš do poznámky",
                onClick = { onOpen(Routes.TEMPLATES) },
            )
            GlassDivider()
            SubScreenEntry(
                emoji = "🏆",
                title = "Odznaky",
                hint = "Co se už odemklo",
                onClick = { onOpen(Routes.ACHIEVEMENTS) },
            )
        }
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Notifikace",
                style = MaterialTheme.typography.titleMedium,
            )
            VSpace(4)
            Text(
                text = "Připomenutí, reporty a sběr času na obrazovce.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            VSpace(6)
            SubScreenEntry(
                emoji = "🔔",
                title = "Notifikace",
                hint = "Časy připomenutí, reporty a čas na obrazovce",
                onClick = { onOpen(Routes.NOTIFICATIONS) },
            )
        }
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Přehledy",
                style = MaterialTheme.typography.titleMedium,
            )
            VSpace(4)
            Text(
                text = "Týdenní a měsíční souhrn deníku, který pro tebe napíše AI.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            VSpace(6)
            SubScreenEntry(
                emoji = "🤖",
                title = ReportsState.TITLE,
                hint = "Co se v deníku objevilo za týden a za měsíc",
                onClick = { onOpen(Routes.REPORTS) },
            )
        }
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Data",
                style = MaterialTheme.typography.titleMedium,
            )
            VSpace(4)
            Text(
                text = "Vlastní kopie deníku v souboru, který si uložíš, kam chceš.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            VSpace(6)
            SubScreenEntry(
                emoji = "📤",
                title = "Export do CSV",
                hint = "Všechny zápisy do souboru, bez nových oprávnění",
                onClick = { onOpen(Routes.EXPORT) },
            )
        }
        GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo) {
            Text(
                text = "Účet",
                style = MaterialTheme.typography.titleMedium,
            )
            VSpace(6)
            Text(
                text = "Odhlášením se z tohoto zařízení smaže uložené přihlášení. Data v účtu zůstávají.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            VSpace(14)
            OutlinedButton(onClick = onSignOut) {
                Text("Odhlásit se")
            }
        }
    }
}

/**
 * Chrome for the M5 sub-screens: a back arrow, the title, then the screen.
 *
 * The screens themselves are handed in already built, so they stay unaware of
 * navigation and can be previewed on their own.
 */
@Composable
private fun SubScreenChrome(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onBack)
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.gutter)
                .padding(top = Spacing.screenTop, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Zpět",
                    tint = TextSecondary,
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
            )
        }
        content()
    }
}

/**
 * Bottom navigation.
 *
 * A Material 3 [NavigationBar] with the selection animated by hand: one float per
 * tab drives the icon pop and the indigo indicator pill, labels stay visible in
 * both states (dimmed regular → indigo semibold), and a fading indigo hairline
 * separates the bar from the content instead of a hard grey rule.
 *
 * Insets stay on the bar itself — [NavigationBar] pads its own row for the
 * gesture/navigation area, and the colour is painted by the column behind it, so
 * the strip is filled edge to edge without the content ever sliding under it.
 */
@Composable
private fun BottomBar(
    currentRoute: String?,
    onSelect: (TopLevelDestination) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface1.copy(alpha = 0.94f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            Indigo.copy(alpha = 0.45f),
                            Violet.copy(alpha = 0.35f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        NavigationBar(
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
        ) {
            TopLevelDestination.entries.forEach { destination ->
                val selected = currentRoute == destination.route
                val selection by animateFloatAsState(
                    targetValue = if (selected) 1f else 0f,
                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                    label = "bottomBarSelection",
                )
                NavigationBarItem(
                    selected = selected,
                    onClick = {
                        // Light tick on tab change; the platform tick needs no
                        // VIBRATE permission.
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSelect(destination)
                    },
                    icon = {
                        Icon(
                            imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                            contentDescription = destination.label,
                            modifier = Modifier.graphicsLayer {
                                val scale = 1f + 0.1f * selection
                                scaleX = scale
                                scaleY = scale
                            },
                        )
                    },
                    label = {
                        Text(
                            text = destination.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        )
                    },
                    alwaysShowLabel = true,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = IndigoLight,
                        indicatorColor = Indigo.copy(alpha = 0.26f),
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary,
                    ),
                )
            }
        }
    }
}
