@file:OptIn(ExperimentalMaterial3Api::class)

package fr.vintedassistant.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.vintedassistant.mobile.ui.editor.ArticleEditorScreen
import fr.vintedassistant.mobile.ui.screens.ActivityScreen
import fr.vintedassistant.mobile.ui.screens.AssistantScreen
import fr.vintedassistant.mobile.ui.screens.DashboardScreen
import fr.vintedassistant.mobile.ui.screens.RepublishScreen
import fr.vintedassistant.mobile.ui.screens.SalesScreen
import fr.vintedassistant.mobile.ui.screens.SettingsScreen
import fr.vintedassistant.mobile.ui.screens.StockActions
import fr.vintedassistant.mobile.ui.screens.StockScreen
import fr.vintedassistant.mobile.ui.vinted.VintedWebScreen
import fr.vintedassistant.mobile.ui.purchases.PurchasesScreen
import fr.vintedassistant.mobile.ui.purchases.SupplierWebScreen

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Accueil", Icons.Filled.Home),
    STOCK("Stock", Icons.AutoMirrored.Filled.List),
    REPUBLISH("Republier", Icons.Filled.Refresh),
    SALES("Ventes", Icons.Filled.ShoppingCart),
    ASSISTANT("Assistant", Icons.Filled.Face),
}

/** Écrans affichés par-dessus les onglets. */
private enum class Overlay(val title: String) {
    NONE(""), SETTINGS("Réglages"), ACTIVITY("Activité Vinted"), VINTED("Vinted intégré (bêta)"),
    PURCHASES("Achats fournisseur"), SUPPLIER("Nihaojewelry"),
}

/**
 * Navigation simple par état : 5 onglets, la fiche article et des écrans par-dessus.
 * Pas de bibliothèque de navigation : inutile à cette taille, et une dépendance de moins.
 */
@Composable
fun AppRoot(initialTab: Tab = Tab.HOME, tabRequest: Int = 0, sharedLink: String? = null, onSharedLinkHandled: () -> Unit = {}) {
    val vm: MainViewModel = viewModel(factory = MainViewModel.Factory)
    var tabIndex by rememberSaveable { mutableIntStateOf(initialTab.ordinal) }
    // Fiche ouverte : -1 aucune, 0 nouvelle, sinon identifiant. La session rend chaque ouverture unique.
    var editorId by rememberSaveable { mutableLongStateOf(-1L) }
    var editorSession by rememberSaveable { mutableIntStateOf(0) }
    var overlayIndex by rememberSaveable { mutableIntStateOf(Overlay.NONE.ordinal) }
    var assistantArticle by rememberSaveable { mutableStateOf<Long?>(null) }
    var assistantTab by rememberSaveable { mutableIntStateOf(0) }
    var vintedArticle by rememberSaveable { mutableStateOf<Long?>(null) }

    val settings by vm.settings.collectAsStateWithLifecycle()
    val articles by vm.articles.collectAsStateWithLifecycle()
    val sales by vm.sales.collectAsStateWithLifecycle()
    val dashboard by vm.dashboard.collectAsStateWithLifecycle()
    val plan by vm.republishPlan.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val aiStatus by vm.aiStatus.collectAsStateWithLifecycle()
    val notices by vm.notices.collectAsStateWithLifecycle()
    val exportReport by vm.exportReport.collectAsStateWithLifecycle()
    val stock by vm.stock.collectAsStateWithLifecycle()
    val findings by vm.findings.collectAsStateWithLifecycle()
    // Fiche créée par « Remettre en vente » : que faire ensuite ?
    var created by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(tabRequest) {
        if (tabRequest > 0) {
            tabIndex = initialTab.ordinal
            overlayIndex = Overlay.NONE.ordinal
            editorId = -1L
        }
    }
    LifecycleResumeEffect(Unit) {
        vm.refreshClock()
        onPauseOrDispose { }
    }

    fun openEditor(id: Long) {
        editorSession++
        editorId = id
    }

    // Lien partagé depuis l'application Vinted : à quel article l'associer ?
    if (sharedLink != null) {
        AlertDialog(
            onDismissRequest = onSharedLinkHandled,
            title = { Text("Associer ce lien Vinted") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(sharedLink)
                    val stock = articles.orEmpty().filter { it.status.inStock }
                    if (stock.isEmpty()) Text("Aucun article en stock.")
                    stock.forEach { a ->
                        TextButton(onClick = { vm.linkArticle(a.id, sharedLink); onSharedLinkHandled() }) { Text(a.title) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onSharedLinkHandled) { Text("Annuler") } },
        )
    }

    created?.let { id ->
        AlertDialog(
            onDismissRequest = { created = null },
            title = { Text("Fiche prête en brouillon") },
            text = {
                Text(
                    "La nouvelle fiche reprend le texte, le prix et les photos, avec le coût d'achat de ta commande. " +
                        "Rien n'est publié tant que tu ne publies pas toi-même dans Vinted.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    created = null
                    vintedArticle = id
                    overlayIndex = Overlay.VINTED.ordinal
                }) { Text("Publier dans Vinted intégré") }
            },
            dismissButton = { TextButton(onClick = { created = null; openEditor(id) }) { Text("Voir la fiche") } },
        )
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

    val overlay = Overlay.entries[overlayIndex]
    fun close() {
        overlayIndex = if (overlay == Overlay.SUPPLIER) Overlay.PURCHASES.ordinal else Overlay.NONE.ordinal
    }
    BackHandler(enabled = overlay != Overlay.NONE && overlay != Overlay.VINTED && overlay != Overlay.SUPPLIER) {
        overlayIndex = if (overlay == Overlay.SUPPLIER) Overlay.PURCHASES.ordinal else Overlay.NONE.ordinal
    }
    val tab = Tab.entries[tabIndex]
    val unread = notices.count { !it.handled }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (overlay != Overlay.NONE) overlay.title else tab.label) },
                navigationIcon = {
                    if (overlay != Overlay.NONE) {
                        IconButton(onClick = { close() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
                    }
                },
                actions = {
                    if (overlay == Overlay.NONE) {
                        IconButton(onClick = { overlayIndex = Overlay.ACTIVITY.ordinal }) {
                            BadgedBox(badge = { if (unread > 0) Badge { Text(unread.coerceAtMost(99).toString()) } }) {
                                Icon(Icons.Filled.Notifications, "Activité Vinted")
                            }
                        }
                        IconButton(onClick = { overlayIndex = Overlay.SETTINGS.ordinal }) { Icon(Icons.Filled.Settings, "Réglages") }
                    }
                },
            )
        },
        bottomBar = {
            if (overlay == Overlay.NONE) {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = t == tab,
                            onClick = {
                                tabIndex = t.ordinal
                                if (t == Tab.ASSISTANT) { assistantArticle = null; assistantTab = 0 }
                                vm.refreshClock()
                            },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (overlay == Overlay.NONE && tab == Tab.STOCK) {
                FloatingActionButton(onClick = { openEditor(0L) }) { Icon(Icons.Filled.Add, "Nouvel article") }
            }
        },
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding)
        when (overlay) {
            Overlay.SETTINGS -> SettingsScreen(
                settings = settings,
                aiStatus = aiStatus,
                modifier = modifier,
                onUpdate = vm::updateSettings,
                onCheckAi = vm::checkAi,
                onDownloadAi = vm::downloadAi,
                onMessage = { vm.show(it); close() },
                exportReport = exportReport,
                onPickExport = vm::readExport,
                onConfirmExport = { vm.confirmExportImport(); close() },
                onDismissExport = vm::dismissExport,
                onOpenActivity = { overlayIndex = Overlay.ACTIVITY.ordinal },
            )
            Overlay.ACTIVITY -> ActivityScreen(
                notices = notices,
                articles = articles,
                modifier = modifier,
                onHandled = vm::markNoticeHandled,
                onAllHandled = vm::markAllNoticesHandled,
                onOpenArticle = { close(); openEditor(it) },
                onOpenSales = { tabIndex = Tab.SALES.ordinal; close() },
                onAssist = { id, subTab ->
                    assistantArticle = id
                    assistantTab = subTab
                    tabIndex = Tab.ASSISTANT.ordinal
                    close()
                },
            )
            Overlay.PURCHASES -> PurchasesScreen(
                articles = articles,
                modifier = modifier,
                onOpenSupplier = { overlayIndex = Overlay.SUPPLIER.ordinal },
                onOpenArticle = { close(); openEditor(it) },
                onMessage = vm::show,
            )
            Overlay.SUPPLIER -> SupplierWebScreen(modifier = modifier, onClose = { overlayIndex = Overlay.PURCHASES.ordinal })
            Overlay.VINTED -> VintedWebScreen(
                articles = articles,
                initialArticleId = vintedArticle,
                accepted = settings.betaVintedAccepted,
                modifier = modifier,
                onAccept = { vm.updateSettings { it.copy(betaVintedAccepted = true) } },
                onPublished = vm::markPublishedOrRepublished,
                onLinkArticle = vm::linkArticle,
                onClose = { close() },
            )
            Overlay.NONE -> when (tab) {
                Tab.HOME -> DashboardScreen(
                    stats = dashboard,
                    contributionRateBp = settings.contributionRateBp,
                    message = message,
                    onDismissMessage = vm::dismissMessage,
                    modifier = modifier,
                    onOpenArticle = ::openEditor,
                    findings = findings,
                    sales = sales.orEmpty(),
                    onOpenSales = { tabIndex = Tab.SALES.ordinal },
                    onOpenRepublish = { tabIndex = Tab.REPUBLISH.ordinal },
                )
                Tab.STOCK -> StockScreen(
                    articles = articles,
                    stock = stock,
                    message = message,
                    onDismissMessage = vm::dismissMessage,
                    modifier = modifier,
                    actions = StockActions(
                        openArticle = ::openEditor,
                        openPurchases = { overlayIndex = Overlay.PURCHASES.ordinal },
                        openVinted = { vintedArticle = null; overlayIndex = Overlay.VINTED.ordinal },
                        relaunch = { templateId, itemId -> vm.relaunch(templateId, itemId) { created = it } },
                        draftFromPurchase = { itemId -> vm.draftFromPurchase(itemId, ::openEditor) },
                        link = { a, lot -> vm.linkToPurchase(a.id, lot.itemId, a.title, lot.landedUnitCents) },
                        markReceived = { orderId -> vm.setOrderReceived(orderId, true) },
                    ),
                )
                Tab.REPUBLISH -> RepublishScreen(
                    plan = plan,
                    stagnant = dashboard?.stagnant.orEmpty(),
                    settings = settings,
                    modifier = modifier,
                    onRepublished = vm::markRepublished,
                    onApplyPrice = vm::applyPrice,
                    onOpenArticle = ::openEditor,
                    onOpenInVinted = { id -> vintedArticle = id; overlayIndex = Overlay.VINTED.ordinal },
                )
                Tab.SALES -> SalesScreen(
                    sales = sales,
                    contributionRateBp = settings.contributionRateBp,
                    modifier = modifier,
                    onStatus = vm::setShippingStatus,
                    onAttachLabel = vm::attachLabel,
                )
                Tab.ASSISTANT -> AssistantScreen(
                    articles = articles,
                    aiEnabled = settings.aiEnabled,
                    priceDropPercent = settings.priceDropPercent,
                    modifier = modifier,
                    initialArticleId = assistantArticle,
                    initialTab = assistantTab,
                )
            }
        }
    }
}
