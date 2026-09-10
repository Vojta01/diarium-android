package cz.digitalnivedomi.diarium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Color
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
import cz.digitalnivedomi.diarium.ui.components.GlassCard
import cz.digitalnivedomi.diarium.ui.components.GlassChip
import cz.digitalnivedomi.diarium.ui.components.VSpace
import cz.digitalnivedomi.diarium.ui.history.HistoryRoute
import cz.digitalnivedomi.diarium.ui.home.DashboardRoute
import cz.digitalnivedomi.diarium.ui.nav.Routes
import cz.digitalnivedomi.diarium.ui.nav.TopLevelDestination
import cz.digitalnivedomi.diarium.ui.stats.StatsRoute
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.Surface1
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary

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
                .padding(bottom = padding.calculateBottomPadding()),
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
                composable(Routes.SETTINGS) { SettingsScreen(onSignOut = onSignOut) }
            }
        }
    }
}

/** Settings placeholder plus the only account action that exists so far: logout. */
@Composable
private fun SettingsScreen(onSignOut: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Nastavení",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            GlassChip(text = "Připravujeme")
            VSpace(12)
            Text(
                text = "Cíle, připomínky a export dorazí v dalším kroku.",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
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

@Composable
private fun BottomBar(
    currentRoute: String?,
    onSelect: (TopLevelDestination) -> Unit,
) {
    NavigationBar(
        containerColor = Surface1.copy(alpha = 0.92f),
        tonalElevation = 0.dp,
        modifier = Modifier.background(Surface1.copy(alpha = 0.92f)),
    ) {
        TopLevelDestination.entries.forEach { destination ->
            val selected = currentRoute == destination.route
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(destination) },
                icon = {
                    Icon(
                        imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                        contentDescription = destination.label,
                    )
                },
                label = { Text(destination.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color.White,
                    selectedTextColor = Indigo,
                    indicatorColor = Indigo.copy(alpha = 0.22f),
                    unselectedIconColor = TextSecondary,
                    unselectedTextColor = TextSecondary,
                ),
            )
        }
    }
}
