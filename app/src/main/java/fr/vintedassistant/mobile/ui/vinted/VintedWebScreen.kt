@file:OptIn(ExperimentalMaterial3Api::class)

package fr.vintedassistant.mobile.ui.vinted

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import fr.vintedassistant.mobile.domain.Article
import fr.vintedassistant.mobile.data.importer.ImportedListing
import fr.vintedassistant.mobile.data.importer.PageImport
import androidx.compose.runtime.rememberCoroutineScope
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import fr.vintedassistant.mobile.domain.ListingTemplates
import fr.vintedassistant.mobile.domain.Money
import fr.vintedassistant.mobile.domain.VintedLinks
import fr.vintedassistant.mobile.ui.container
import fr.vintedassistant.mobile.ui.copyToClipboard

private const val HOME = "https://www.vinted.fr/"
private const val SELL = "https://www.vinted.fr/items/new"
private val ALLOWED_HOSTS = Regex("""(^|\.)(vinted\.[a-z.]{2,6}|accounts\.google\.com|appleid\.apple\.com|facebook\.com)$""")

/** État de la page partagé avec le WebView (photos prêtes, sélecteur de fichiers en attente). */
private class WebSession {
    var pendingPhotos: List<Uri> = emptyList()
    var pageLoaded: CompletableDeferred<Unit>? = null
    var fileCallback: ValueCallback<Array<Uri>>? = null
    var webView: WebView? = null
}

/**
 * BÊTA « Vinted intégré » : vinted.fr dans l'application, connexion faite par l'utilisateur lui-même.
 * L'application ne voit pas le mot de passe et ne lit pas les cookies. Aucune action n'est faite seule :
 * chaque remplissage part d'un appui de l'utilisateur, et c'est lui qui appuie sur « Ajouter » dans Vinted.
 * Fonctionne uniquement application ouverte, téléphone déverrouillé.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VintedWebScreen(
    articles: List<Article>?,
    initialArticleId: Long?,
    accepted: Boolean,
    modifier: Modifier,
    onAccept: () -> Unit,
    onPublished: (Article) -> Unit,
    onLinkArticle: (Long, String) -> Unit,
    onClose: () -> Unit,
    queue: List<Long> = emptyList(),
    origins: Map<Long, fr.vintedassistant.mobile.ui.PieceOrigin> = emptyMap(),
) {
    val context = LocalContext.current
    val session = remember { WebSession() }
    var selectedId by rememberSaveable { mutableStateOf(initialArticleId) }
    var menu by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var currentUrl by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var diagnostic by remember { mutableStateOf<String?>(null) }
    var photosReady by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    var importJob by remember { mutableStateOf<Job?>(null) }
    var importProgress by remember { mutableStateOf<String?>(null) }

    val candidates = articles.orEmpty().filter { it.status.inStock }
    val article = candidates.firstOrNull { it.id == selectedId }

    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        session.fileCallback?.onReceiveValue(if (result.resultCode == Activity.RESULT_OK) uris else null)
        session.fileCallback = null
    }

    BackHandler {
        val web = session.webView
        if (web != null && web.canGoBack()) web.goBack() else onClose()
    }
    DisposableEffect(Unit) {
        onDispose {
            session.fileCallback?.onReceiveValue(null)
            session.webView?.destroy()
            session.webView = null
        }
    }

    if (!accepted) {
        BetaWarning(modifier, onAccept, onClose)
        return
    }

    diagnostic?.let { text ->
        AlertDialog(
            onDismissRequest = { diagnostic = null },
            title = { Text("Diagnostic de la page") },
            text = { Text(text.take(3000), Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = { copyToClipboard(context, "Diagnostic", text); diagnostic = null }) { Text("Copier") }
            },
            dismissButton = { TextButton(onClick = { diagnostic = null }) { Text("Fermer") } },
        )
    }

    /** Remplit le formulaire « Vendre » avec la fiche [a] (photos, texte, catégorie, détails, prix). */
    suspend fun fillListing(a: Article, rotatePhotos: Boolean = false) {
        val draft = ListingTemplates.draft(a)
        val description = a.description.ifBlank { draft.description }
        val tags = a.keywords.ifBlank { draft.keywords }
        val full = if (tags.isBlank() || description.contains(tags)) description else "$description\n\n$tags"
        val web = session.webView ?: return

        // « Remplir » ne marche que sur le formulaire « Vendre » : on l'ouvre et on attend qu'il soit chargé.
        if (!currentUrl.contains("/items/new")) {
            status = "Ouverture du formulaire « Vendre »…"
            web.loadUrl(SELL)
            delay(1500)
        }
        var ready = false
        for (i in 0 until 40) {
            if (web.evalJs(VintedPageScripts.formReady) == "1") { ready = true; break }
            delay(300)
        }
        if (!ready) {
            status = "Le formulaire « Vendre » n'est pas encore chargé (connexion lente ou page de connexion). Réessaie « Remplir » dans un instant."
            return
        }
        web.evaluateJavascript(VintedPageScripts.dialogFix, null)
        // Photos : le sélecteur de Vinted reçoit directement celles de la fiche.
        if (a.photos.isNotEmpty()) {
            val store = context.container().photoStore
            // Republication : la photo principale change d'une semaine à l'autre (jamais deux annonces identiques).
            val photos = if (rotatePhotos && a.photos.size > 1) {
                val k = ((System.currentTimeMillis() / (7L * 24 * 3600 * 1000)) % a.photos.size).toInt().coerceAtLeast(1)
                a.photos.drop(k) + a.photos.take(k)
            } else a.photos
            session.pendingPhotos = photos.map { store.shareUri(it) }
            photosReady = photos.size
            web.evalJs(VintedPageScripts.openPhotoPicker)
            delay(1500)
        }
        val filled = VintedPageScripts.fillSummary(web.evalJs(VintedPageScripts.fill(a.title, full, "")))
        status = "$filled Catégorie en cours…"
        val c = CategoryGuess.of(a)
        web.evalJs(VintedPageScripts.categoryStart(c.query, c.leaves, c.gender, c.avoid))
        var result: org.json.JSONObject? = null
        for (i in 0 until 100) {
            delay(300)
            val raw = web.evalJs(VintedPageScripts.categoryResult)
            val json = raw?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
            if (json != null && json.optBoolean("done")) { result = json; break }
        }
        status = filled + " " + CategoryGuess.summary(result)
        if (result == null || !result.optBoolean("ok")) {
            if (result != null) diagnostic = result.toString(2)
            return
        }
        // Détails apparus avec la catégorie : pour qui, marque, état, couleur, matériau.
        delay(800)
        web.evalJs(VintedPageScripts.detailsStart(DetailsGuess.fields(a)))
        var details: org.json.JSONObject? = null
        for (i in 0 until 300) {
            delay(300)
            val json = web.evalJs(VintedPageScripts.detailsResult)?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
            if (json != null && json.optBoolean("done")) { details = json; break }
        }
        // Prix en dernier : point décimal d'abord (« 9.50 »), virgule si Vinted ne le comprend pas.
        val cents = a.priceCents
        val dot = (cents / 100).toString() + (if (cents % 100 == 0L) "" else "." + (cents % 100).toString().padStart(2, '0'))
        var priceOk = false
        for (v in listOf(dot, dot.replace('.', ','))) {
            web.evalJs(VintedPageScripts.setPrice(v))
            delay(600)
            val shown = web.evalJs(VintedPageScripts.readPrice).orEmpty()
            if (shown.any { it.isDigit() } && !shown.contains("NaN", ignoreCase = true) && !shown.contains("ERREUR")) { priceOk = true; break }
        }
        web.evalJs(VintedPageScripts.packageSmall)
        status = DetailsGuess.summary(details) + (if (priceOk) "" else " Prix à saisir à la main.")
        if (details == null || DetailsGuess.hasGaps(details)) details?.let { diagnostic = it.toString(2) }
    }

    // --- Republication en série : l'application remplit, l'utilisateur touche « Ajouter » puis « Supprimer ». ---
    var queueIndex by rememberSaveable { mutableStateOf(if (queue.isEmpty()) -1 else 0) }
    var phase by rememberSaveable { mutableStateOf(SeriesPhase.IDLE) }
    var oldUrl by rememberSaveable { mutableStateOf("") }
    var sawOld by rememberSaveable { mutableStateOf(false) }
    var doneCount by rememberSaveable { mutableStateOf(0) }
    val inSeries = queueIndex in queue.indices

    fun startCurrent() {
        val id = queue.getOrNull(queueIndex) ?: return
        val a = articles.orEmpty().firstOrNull { it.id == id } ?: return
        selectedId = id
        photosReady = 0
        session.pendingPhotos = emptyList()
        oldUrl = a.vintedUrl.substringBefore('?')
        sawOld = false
        phase = SeriesPhase.FILLING
        scope.launch {
            // Photo principale décalée seulement pour une republication ; une première mise en vente garde l'ordre choisi.
            fillListing(a, rotatePhotos = a.firstPublishedAt != null)
            phase = SeriesPhase.WAIT_PUBLISH
            status = "Annonce ${queueIndex + 1} sur ${queue.size} : " + (status ?: "") +
                (if (a.firstPublishedAt == null) " Vérifie, puis touche « Ajouter » dans Vinted." else " Touche « Ajouter » dans Vinted : l'application ouvrira ensuite l'ancienne annonce pour que tu la supprimes.")
        }
    }

    fun next() {
        doneCount++
        queueIndex++
        if (queueIndex in queue.indices) startCurrent() else {
            phase = SeriesPhase.IDLE
            status = "Republication terminée : $doneCount annonce(s). Pense à vérifier ton dressing."
        }
    }

    fun onNewListing(url: String) {
        val a = article ?: return
        onPublished(a)
        onLinkArticle(a.id, url)
        val old = oldUrl
        if (old.isBlank() || VintedLinks.itemId(old) == null) {
            status = if (a.firstPublishedAt == null) "Annonce publiée et reliée à sa fiche. Suivante…"
                else "Nouvelle annonce enregistrée. Pas d'ancienne annonce connue : supprime-la toi-même si elle existe."
            next()
        } else {
            phase = SeriesPhase.WAIT_DELETE
            sawOld = false
            status = "Nouvelle annonce enregistrée. Supprime maintenant l'ancienne : menu de l'annonce, puis « Supprimer »."
            session.webView?.loadUrl(old)
        }
    }

    LaunchedEffect(articles != null) {
        if (articles != null && inSeries && phase == SeriesPhase.IDLE && doneCount == 0) startCurrent()
    }
    LaunchedEffect(currentUrl, phase) {
        when (phase) {
            SeriesPhase.WAIT_PUBLISH -> {
                val id = VintedLinks.itemId(currentUrl)
                if (id != null && !currentUrl.contains("/edit") && id != VintedLinks.itemId(oldUrl)) onNewListing(currentUrl.substringBefore('?'))
                else if (currentUrl.contains("/member/")) {
                    // Après « Ajouter », Vinted peut revenir au dressing au lieu d'ouvrir la nouvelle annonce :
                    // on y cherche l'annonce la plus récente au même titre, inconnue de l'application.
                    val web = session.webView
                    val a = article
                    if (web != null && a != null) {
                        val known = articles.orEmpty().mapNotNull { VintedLinks.itemId(it.vintedUrl) }.toSet()
                        val wanted = fr.vintedassistant.mobile.domain.StockLedger.normalize(a.title)
                        var found: String? = null
                        for (i in 0 until 6) {
                            delay(1500)
                            if (phase != SeriesPhase.WAIT_PUBLISH) break
                            found = readGrid(web)
                                .filter { it.itemId !in known && fr.vintedassistant.mobile.domain.StockLedger.normalize(it.title) == wanted }
                                .maxByOrNull { it.itemId }?.url
                            if (found != null) break
                        }
                        if (phase == SeriesPhase.WAIT_PUBLISH) {
                            if (found != null) onNewListing(found)
                            else status = "Nouvelle annonce non repérée dans le dressing. Ouvre-la, puis touche « Elle est en ligne »."
                        }
                    }
                }
            }
            SeriesPhase.WAIT_DELETE -> {
                val oldId = VintedLinks.itemId(oldUrl)
                if (oldId != null && VintedLinks.itemId(currentUrl) == oldId) sawOld = true
                else if (sawOld && currentUrl.isNotBlank() && !currentUrl.contains("/edit")) {
                    status = "Ancienne annonce quittée. Annonce suivante…"
                    next()
                }
            }
            else -> Unit
        }
    }

    Column(modifier) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (inSeries) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Série ${queueIndex + 1}/${queue.size} : " + when (phase) {
                            SeriesPhase.FILLING -> "remplissage…"
                            SeriesPhase.WAIT_PUBLISH -> "touche « Ajouter »"
                            SeriesPhase.WAIT_DELETE -> "supprime l'ancienne"
                            SeriesPhase.IDLE -> "prêt"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    when (phase) {
                        SeriesPhase.WAIT_PUBLISH -> TextButton(onClick = {
                            val id = VintedLinks.itemId(currentUrl)
                            if (id != null && id != VintedLinks.itemId(oldUrl)) onNewListing(currentUrl.substringBefore('?'))
                            else status = "Ouvre la nouvelle annonce dans Vinted (ton dressing), puis touche à nouveau « Elle est en ligne »."
                        }) { Text("Elle est en ligne") }
                        SeriesPhase.WAIT_DELETE -> TextButton(onClick = { next() }) { Text("Suivante") }
                        else -> Unit
                    }
                    TextButton(onClick = { queueIndex = queue.size; phase = SeriesPhase.IDLE; status = "Série arrêtée." }) { Text("Arrêter") }
                }
                if (phase == SeriesPhase.WAIT_DELETE && oldUrl.isNotBlank()) {
                    // Secours : si « Supprimer » ne répond pas ici, l'ancienne annonce s'ouvre dans l'appli Vinted.
                    OutlinedButton(
                        onClick = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(oldUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                status = "Supprime l'ancienne annonce dans l'appli Vinted, reviens ici, puis touche « Suivante »."
                            } catch (e: Exception) { status = "Impossible d'ouvrir l'appli Vinted." }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("« Supprimer » ne répond pas ? Ouvrir dans l'appli Vinted") }
                }
            }
            Box {
                OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        article?.let { fr.vintedassistant.mobile.ui.PhotoThumb(it.photos.firstOrNull() ?: origins[it.id]?.supplierImage, size = 40.dp) }
                        Text(article?.let { "${it.title} • ${Money.formatEuros(it.priceCents)}" + (origins[it.id]?.variant?.takeIf { v -> v.isNotBlank() }?.let { v -> " • $v" } ?: "") }
                            ?: "Choisir l'article à publier")
                    }
                }
                if (menu) {
                    fr.vintedassistant.mobile.ui.ArticlePickerDialog(candidates, origins, onDismiss = { menu = false }) { a ->
                        selectedId = a.id; menu = false; photosReady = 0; session.pendingPhotos = emptyList()
                    }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    enabled = importJob == null,
                    onClick = {
                        val web = session.webView ?: return@Button
                        importJob = scope.launch {
                            try {
                                status = runImport(context, web, session, currentUrl) { importProgress = it }
                            } catch (e: CancellationException) {
                                status = "Import arrêté. Ce qui a déjà été importé est conservé."
                                throw e
                            } catch (e: Exception) {
                                status = "Import interrompu par une erreur. Ce qui a déjà été importé est conservé."
                            } finally {
                                importProgress = null
                                importJob = null
                            }
                        }
                    },
                ) { Text(if (currentUrl.contains("/items/")) "Importer cette annonce" else "Importer tout mon dressing") }
                OutlinedButton(onClick = { session.webView?.loadUrl(SELL) }) { Text("Vendre") }
                Button(
                    enabled = article != null,
                    onClick = {
                        val a = article ?: return@Button
                        scope.launch { fillListing(a) }
                    },
                ) { Text("Remplir") }
                OutlinedButton(
                    enabled = article != null && article.photos.isNotEmpty(),
                    onClick = {
                        val a = article ?: return@OutlinedButton
                        val store = context.container().photoStore
                        session.pendingPhotos = a.photos.map { store.shareUri(it) }
                        photosReady = a.photos.size
                        status = "${a.photos.size} photo(s) prête(s) : appuie sur « Ajouter des photos » dans Vinted."
                    },
                ) { Text(if (photosReady > 0) "Photos prêtes ($photosReady)" else "Préparer les photos") }
                OutlinedButton(enabled = article != null, onClick = {
                    article?.let(onPublished)
                    status = "Enregistré comme publié ici."
                }) { Text("C'est publié") }
                OutlinedButton(
                    enabled = article != null && VintedLinks.isVintedUrl(currentUrl) && currentUrl.contains("/items/"),
                    onClick = {
                        val a = article ?: return@OutlinedButton
                        onLinkArticle(a.id, currentUrl.substringBefore('?'))
                        status = "Lien de l'annonce associé à la fiche."
                    },
                ) { Text("Lier cette page") }
                OutlinedButton(onClick = {
                    // L'étape de la série en tête : on sait où elle s'est arrêtée, pas seulement sur quelle page.
                    val series = if (inSeries) "SÉRIE ${queueIndex + 1}/${queue.size}, étape $phase, ancienne annonce : " +
                        "${VintedLinks.itemId(oldUrl) ?: "aucune"}, ancienne vue : $sawOld\nMESSAGE : ${status ?: ""}\n" else "HORS SÉRIE\nMESSAGE : ${status ?: ""}\n"
                    session.webView?.evaluateJavascript(VintedPageScripts.diagnostic) { r ->
                        diagnostic = series + (VintedPageScripts.decode(r) ?: "Diagnostic impossible sur cette page.")
                    }
                }) { Text("Diagnostic") }
                OutlinedButton(onClick = {
                    // Copie de la page pour diagnostic : sans scripts ni styles, adresse, e-mails et téléphones retirés.
                    session.webView?.evaluateJavascript(fr.vintedassistant.mobile.ui.purchases.SupplierPageScripts.pageCopy) { r ->
                        val html = VintedPageScripts.decode(r)
                        if (html.isNullOrBlank()) { status = "Copie de la page impossible."; return@evaluateJavascript }
                        scope.launch {
                            try {
                                val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    val dir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
                                    java.io.File(dir, "page-vinted.html").apply { writeText(html) }
                                }
                                context.startActivity(context.container().dataExport.shareIntent(file, "text/html"))
                            } catch (e: Exception) {
                                status = "Copie de la page impossible."
                            }
                        }
                    }
                }) { Text("Envoyer la page") }
                TextButton(onClick = {
                    CookieManager.getInstance().removeAllCookies(null)
                    WebStorage.getInstance().deleteAllData()
                    session.webView?.loadUrl(HOME)
                    status = "Session Vinted effacée de l'application."
                }) { Text("Se déconnecter") }
            }
            importProgress?.let { p ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(p, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { importJob?.cancel() }) { Text("Arrêter") }
                    }
                }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val uri = request.url
                            val host = uri.host?.lowercase().orEmpty()
                            if (uri.scheme == "https" && ALLOWED_HOSTS.containsMatchIn(host)) return false
                            // Tout le reste (liens externes, autres schémas) s'ouvre hors de l'application.
                            try {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (e: Exception) {
                                Toast.makeText(ctx, "Lien non ouvert", Toast.LENGTH_SHORT).show()
                            }
                            return true
                        }

                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                            loading = true
                            currentUrl = url.orEmpty()
                        }

                        // Vinted change de page sans recharger (navigation interne) : on suit l'adresse quand même.
                        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                            currentUrl = url.orEmpty()
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            view.evaluateJavascript(VintedPageScripts.dialogFix, null)
                            loading = false
                            currentUrl = url.orEmpty()
                            session.pageLoaded?.complete(Unit)
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        // Confirmations de la page (« Supprimer cette annonce ? ») : affichées en fenêtre Android.
                        // Sans cela, une confirmation qui ne peut pas s'afficher est refusée sans rien dire.
                        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: android.webkit.JsResult): Boolean {
                            return try {
                                android.app.AlertDialog.Builder(ctx).setMessage(message.orEmpty())
                                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                                    .setNegativeButton("Annuler") { _, _ -> result.cancel() }
                                    .setOnCancelListener { result.cancel() }.show()
                                true
                            } catch (e: Exception) { false }
                        }

                        override fun onJsAlert(view: WebView, url: String?, message: String?, result: android.webkit.JsResult): Boolean {
                            return try {
                                android.app.AlertDialog.Builder(ctx).setMessage(message.orEmpty())
                                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                                    .setOnCancelListener { result.confirm() }.show()
                                true
                            } catch (e: Exception) { false }
                        }

                        override fun onShowFileChooser(
                            webView: WebView,
                            filePathCallback: ValueCallback<Array<Uri>>,
                            fileChooserParams: FileChooserParams,
                        ): Boolean {
                            session.fileCallback?.onReceiveValue(null)
                            val ready = session.pendingPhotos
                            if (ready.isNotEmpty()) {
                                // Photos de la fiche fournies directement, sans passer par la galerie.
                                filePathCallback.onReceiveValue(ready.toTypedArray())
                                session.pendingPhotos = emptyList()
                                photosReady = 0
                                status = "${ready.size} photo(s) envoyée(s) au formulaire."
                                return true
                            }
                            session.fileCallback = filePathCallback
                            return try {
                                chooser.launch(fileChooserParams.createIntent())
                                true
                            } catch (e: Exception) {
                                session.fileCallback = null
                                false
                            }
                        }
                    }
                    session.webView = this
                    val start = article?.vintedUrl?.takeIf { VintedLinks.isVintedUrl(it) }
                        ?: if (article?.status == fr.vintedassistant.mobile.domain.ArticleStatus.DRAFT) SELL else HOME
                    loadUrl(start)
                }
            },
        )
    }
}

@Composable
private fun BetaWarning(modifier: Modifier, onAccept: () -> Unit, onClose: () -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Module bêta : Vinted intégré", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Ce module ouvre vinted.fr dans l'application et peut, à ta demande, remplir le formulaire " +
                        "de vente et y joindre les photos de la fiche. C'est le principe des extensions comme Bleam.",
                )
                Text(
                    "Risque : les conditions d'utilisation de Vinted (section 6) interdisent les outils externes " +
                        "non autorisés. Vinted peut restreindre ou suspendre un compte. Tu l'utilises sous ta responsabilité.",
                )
                Text(
                    "Garde-fous : tu te connectes toi-même, l'application ne voit ni ton mot de passe ni tes cookies, " +
                        "rien n'est publié ni envoyé sans ton appui dans Vinted, aucune protection n'est contournée " +
                        "(si Vinted affiche une vérification, c'est toi qui la fais), pas de messages en masse. " +
                        "Fonctionne application ouverte et téléphone déverrouillé uniquement.",
                )
            }
        }
        Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) { Text("J'ai compris, activer la bêta") }
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Ne pas activer") }
    }
}


// ---------------------------------------------------------------------------------------------
// Import des annonces depuis les pages Vinted ouvertes par l'utilisateur.
// Lecture seule : défilement, filtre « Actifs » / « Vendus », ouverture de chaque annonce.
// Aucune modification sur Vinted. Pauses entre les pages pour ne pas surcharger le site.
// ---------------------------------------------------------------------------------------------

private const val MAX_DETAILED = 100

private suspend fun WebView.evalJs(script: String): String? = suspendCancellableCoroutine { cont ->
    evaluateJavascript(script) { raw -> if (cont.isActive) cont.resume(VintedPageScripts.decode(raw)) }
}

private suspend fun loadAndWait(web: WebView, session: WebSession, url: String): Boolean {
    val done = CompletableDeferred<Unit>()
    session.pageLoaded = done
    web.loadUrl(url)
    val ok = withTimeoutOrNull(25_000) { done.await() } != null
    session.pageLoaded = null
    return ok
}

/** Fait défiler le dressing jusqu'à ce que plus aucune annonce ne se charge. */
private suspend fun scrollToEnd(web: WebView) {
    var last = -1
    var stable = 0
    repeat(40) {
        val count = web.evalJs(VintedPageScripts.scrollAndCount)?.toIntOrNull() ?: 0
        if (count == last) stable++ else stable = 0
        last = count
        if (stable >= 2) return
        delay(1_500)
    }
}

private suspend fun readGrid(web: WebView): List<ImportedListing> =
    web.evalJs(VintedPageScripts.grid)?.let { runCatching { PageImport.parseGrid(it) }.getOrNull() }.orEmpty()

private suspend fun runImport(
    context: Context,
    web: WebView,
    session: WebSession,
    currentUrl: String,
    progress: (String) -> Unit,
): String {
    val repository = context.container().articleRepository

    // Page d'une seule annonce.
    if (currentUrl.contains("/items/")) {
        progress("Lecture de l'annonce…")
        val listing = web.evalJs(VintedPageScripts.item)?.let { runCatching { PageImport.parseItem(it) }.getOrNull() }
            ?: return "Annonce non reconnue sur cette page. Lance le diagnostic et transmets-le."
        val result = repository.upsertImported(listing, sold = false)
        val photos = repository.attachRemotePhotos(result.articleId, listing.imageUrls)
        return (if (result.created) "Annonce importée" else "Fiche mise à jour") + " : ${listing.title}" +
            (if (photos > 0) ", $photos photo(s)." else ".")
    }

    if (!currentUrl.contains("/member/")) {
        return "Ouvre d'abord ton profil Vinted (ton dressing, là où s'affichent tes annonces), puis relance l'import."
    }
    val profileUrl = currentUrl

    // 1. Annonces en vente.
    progress("Chargement des annonces en vente…")
    val activeFilter = web.evalJs(VintedPageScripts.clickFilter("actifs")) == "true"
    if (activeFilter) delay(2_500)
    scrollToEnd(web)
    val active = readGrid(web)

    // 2. Annonces vendues.
    progress("Chargement des annonces vendues…")
    val soldFilter = web.evalJs(VintedPageScripts.clickFilter("vendus")) == "true"
    val sold = if (soldFilter) {
        delay(2_500)
        scrollToEnd(web)
        val activeIds = if (activeFilter) active.map { it.itemId }.toSet() else emptySet()
        readGrid(web).filter { it.itemId !in activeIds }
    } else {
        emptyList()
    }
    val soldIds = sold.map { it.itemId }.toSet()
    val all = active.filter { it.itemId !in soldIds }.map { it to false } + sold.map { it to true }
    if (all.isEmpty()) {
        loadAndWait(web, session, profileUrl)
        return "Aucune annonce trouvée sur cette page. Lance le diagnostic sur ton dressing et transmets-le."
    }

    // 3. Fiches de base, tout de suite.
    var created = 0
    all.forEach { (listing, isSold) -> if (repository.upsertImported(listing, isSold).created) created++ }

    // 4. Détail de chaque annonce (description, marque, taille, état, photos).
    var detailed = 0
    var photos = 0
    val toDetail = all.take(MAX_DETAILED)
    toDetail.forEachIndexed { index, (listing, isSold) ->
        progress("Fiche ${index + 1} / ${toDetail.size} : ${listing.title}")
        if (!loadAndWait(web, session, listing.url)) return@forEachIndexed
        delay(1_500)
        val item = web.evalJs(VintedPageScripts.item)?.let { runCatching { PageImport.parseItem(it) }.getOrNull() }
        if (item != null && item.itemId == listing.itemId) {
            val merged = item.copy(
                favorites = item.favorites ?: listing.favorites,
                views = item.views ?: listing.views,
                priceCents = item.priceCents ?: listing.priceCents,
            )
            val result = repository.upsertImported(merged, isSold)
            photos += repository.attachRemotePhotos(result.articleId, merged.imageUrls.ifEmpty { listing.imageUrls })
            detailed++
        } else {
            photos += repository.upsertImported(listing, isSold).let { repository.attachRemotePhotos(it.articleId, listing.imageUrls, max = 1) }
        }
        delay(2_000)
    }
    loadAndWait(web, session, profileUrl)

    return buildString {
        append("${active.size - active.count { it.itemId in soldIds }} en vente et ${sold.size} vendue(s) trouvées ; ")
        append("$created nouvelle(s) fiche(s), $detailed complétée(s), $photos photo(s) téléchargée(s).")
        if (!soldFilter) append(" Filtre « Vendus » introuvable : annonces vendues non importées.")
        if (all.size > MAX_DETAILED) append(" Détail limité aux $MAX_DETAILED premières : relance pour la suite.")
        append(" Complète le coût d'achat de chaque fiche pour avoir tes marges.")
    }
}

private enum class SeriesPhase { IDLE, FILLING, WAIT_PUBLISH, WAIT_DELETE }
