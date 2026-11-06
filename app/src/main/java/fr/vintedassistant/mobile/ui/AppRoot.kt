@file:OptIn(ExperimentalMaterial3Api::class)

package fr.vintedassistant.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.vintedassistant.mobile.ui.editor.ArticleEditorScreen
import fr.vintedassistant.mobile.ui.screens.AssistantScreen
import fr.vintedassistant.mobile.ui.screens.DashboardScreen
import fr.vintedassistant.mobile.ui.screens.RepublishScreen
import fr.vintedassistant.mobile.ui.screens.SalesScreen
import fr.vintedassistant.mobile.ui.screens.SettingsScreen
import fr.vintedassistant.mobile.ui.screens.StockScreen

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Accueil", Icons.Filled.Home),
    STOCK("Stock", Icons.AutoMirrored.Filled.List),
    REPUBLISH("Republier", Icons.Filled.Refresh),
    SALES("Ventes", Icons.Filled.ShoppingCart),
    ASSISTANT("Assistant", Icons.Filled.Face),
}

/**
 * Navigation simple par état : 5 onglets, la fiche article et les réglages par-dessus.
 * Pas de bibliothèque de navigation : inutile à cette taille, et une dépendance de moins.
 */
@Composable
fun AppRoot(initialTab: Tab = Tab.HOME) {
    val vm: MainViewModel = viewModel(factory = MainViewModel.Factory)
    var tabIndex by rememberSaveable { mutableIntStateOf(initialTab.ordinal) }
    // Fiche ouverte : -1 aucune, 0 nouvelle, sinon identifiant. La session rend chaque ouverture unique.
    var editorId by rememberSaveable { mutableLongStateOf(-1L) }
    var editorSession by rememberSaveable { mutableIntStateOf(0) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    val settings by vm.settings.collectAsStateWithLifecycle()
    val articles by vm.articles.collectAsStateWithLifecycle()
    val sales by vm.sales.collectAsStateWithLifecycle()
    val dashboard by vm.dashboard.collectAsStateWithLifecycle()
    val plan by vm.republishPlan.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val aiStatus by vm.aiStatus.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        vm.refreshClock()
        onPauseOrDispose { }
    }

    fun openEditor(id: Long) {
        editorSession++
        editorId = id
    }

    if (editorId >= 0) {
        ArticleEditorScreen(
            articleId = editorId,
            sessionKey = "editor-$editorSession",
            aiEnabled = settings.aiEnabled,
            onClose = { editorId = -1L; vm.refreshClock() },
        )
        return
    }

    BackHandler(enabled = settingsOpen) { settingsOpen = false }
    val tab = Tab.entries[tabIndex]

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (settingsOpen) "Réglages" else tab.label) },
                navigationIcon = {
                    if (settingsOpen) {
                        IconButton(onClick = { settingsOpen = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
                    }
                },
                actions = {
                    if (!settingsOpen) IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Filled.Settings, "Réglages") }
                },
            )
        },
        bottomBar = {
            if (!settingsOpen) {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = t == tab,
                            onClick = { tabIndex = t.ordinal; vm.refreshClock() },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (!settingsOpen && tab == Tab.STOCK) {
                FloatingActionButton(onClick = { openEditor(0L) }) { Icon(Icons.Filled.Add, "Nouvel article") }
            }
        },
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding)
        if (settingsOpen) {
            SettingsScreen(
                settings = settings,
                aiStatus = aiStatus,
                modifier = modifier,
                onUpdate = vm::updateSettings,
                onCheckAi = vm::checkAi,
                onDownloadAi = vm::downloadAi,
                onMessage = { vm.show(it); settingsOpen = false },
            )
        } else when (tab) {
            Tab.HOME -> DashboardScreen(
                stats = dashboard,
                contributionRateBp = settings.contributionRateBp,
                message = message,
                onDismissMessage = vm::dismissMessage,
                modifier = modifier,
                onOpenArticle = ::openEditor,
            )
            Tab.STOCK -> StockScreen(articles, modifier, onOpen = ::openEditor)
            Tab.REPUBLISH -> RepublishScreen(
                plan = plan,
                stagnant = dashboard?.stagnant.orEmpty(),
                settings = settings,
                modifier = modifier,
                onRepublished = vm::markRepublished,
                onApplyPrice = vm::applyPrice,
                onOpenArticle = ::openEditor,
            )
            Tab.SALES -> SalesScreen(
                sales = sales,
                contributionRateBp = settings.contributionRateBp,
                modifier = modifier,
                onStatus = vm::setShippingStatus,
                onAttachLabel = vm::attachLabel,
            )
            Tab.ASSISTANT -> AssistantScreen(articles, settings.aiEnabled, settings.priceDropPercent, modifier)
        }
    }
}
