package cz.digitalnivedomi.diarium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import cz.digitalnivedomi.diarium.ui.checkin.CheckInScreen
import cz.digitalnivedomi.diarium.ui.auth.LoginScreen
import cz.digitalnivedomi.diarium.ui.components.DiariumBackground
import cz.digitalnivedomi.diarium.ui.nav.Routes
import cz.digitalnivedomi.diarium.ui.nav.TopLevelDestination
import cz.digitalnivedomi.diarium.ui.placeholder.ComingSoonScreen
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.Surface1
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary

@Composable
fun DiariumApp(authDeepLink: State<String?>) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val onAuthScreen = currentRoute == Routes.AUTH

    DiariumBackground {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                if (!onAuthScreen) {
                    BottomBar(
                        currentRoute = currentRoute,
                        onSelect = { destination ->
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = padding.calculateBottomPadding()),
            ) {
                NavHost(
                    navController = navController,
                    startDestination = Routes.CHECK_IN,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    composable(Routes.AUTH) {
                        LoginScreen(deepLink = authDeepLink)
                    }
                    composable(Routes.CHECK_IN) { CheckInScreen() }
                    composable(Routes.HISTORY) {
                        ComingSoonScreen(
                            title = "Historie",
                            message = "Kalendář a seznam zápisů dorazí v dalším kroku.",
                        )
                    }
                    composable(Routes.STATS) {
                        ComingSoonScreen(
                            title = "Přehledy",
                            message = "Grafy nálady, spánku a screen time dorazí v dalším kroku.",
                        )
                    }
                    composable(Routes.SETTINGS) {
                        ComingSoonScreen(
                            title = "Nastavení",
                            message = "Cíle, připomínky a export dorazí v dalším kroku.",
                        )
                    }
                }
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
