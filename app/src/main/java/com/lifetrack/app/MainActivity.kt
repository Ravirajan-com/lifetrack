package com.lifetrack.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CurrencyRupee
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lifetrack.app.ui.expense.ExpenseScreen
import com.lifetrack.app.ui.goals.GoalsScreen
import com.lifetrack.app.ui.gym.GymScreen
import com.lifetrack.app.ui.settings.SettingsScreen
import com.lifetrack.app.ui.theme.Ink
import com.lifetrack.app.ui.theme.LifeTrackTheme

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val perms = mutableListOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(perms.toTypedArray())

        setContent {
            LifeTrackTheme {
                val nav = rememberNavController()
                val backStack by nav.currentBackStackEntryAsState()
                val current = backStack?.destination?.route

                data class TabSpec(
                    val route: String, val label: String,
                    val icon: androidx.compose.ui.graphics.vector.ImageVector,
                    val accent: androidx.compose.ui.graphics.Color
                )

                val tabs = listOf(
                    TabSpec("expense", "Money", Icons.Filled.CurrencyRupee, Ink.mint),
                    TabSpec("gym", "Gym", Icons.Filled.FitnessCenter, Ink.ember),
                    TabSpec("goals", "Goals", Icons.Filled.CheckCircle, Ink.violet),
                    TabSpec("settings", "Settings", Icons.Filled.Settings, Ink.textDim),
                )

                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            tabs.forEach { t ->
                                NavigationBarItem(
                                    selected = current == t.route,
                                    onClick = {
                                        nav.navigate(t.route) {
                                            popUpTo(nav.graph.startDestinationId) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { Icon(t.icon, contentDescription = t.label) },
                                    label = { Text(t.label) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = t.accent,
                                        selectedTextColor = t.accent,
                                        indicatorColor = t.accent.copy(alpha = 0.14f),
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }
                    }
                ) { padding ->
                    NavHost(
                        navController = nav,
                        startDestination = "expense",
                        modifier = Modifier.padding(padding)
                    ) {
                        composable("expense") { ExpenseScreen() }
                        composable("gym") { GymScreen() }
                        composable("goals") { GoalsScreen() }
                        composable("settings") { SettingsScreen() }
                    }
                }
            }
        }
    }
}
