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

    Column(modifier) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box {
                OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(article?.let { "${it.title} • ${Money.formatEuros(it.priceCents)}" } ?: "Choisir l'article à publier")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    candidates.forEach { a ->
                        DropdownMenuItem(text = { Text(a.title) }, onClick = { selectedId = a.id; menu = false; photosReady = 0; session.pendingPhotos = emptyList() })
                    }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { session.webView?.loadUrl(SELL) }) { Text("Vendre") }
                Button(
                    enabled = article != null,
                    onClick = {
                        val a = article ?: return@Button
                        val draft = ListingTemplates.draft(a)
                        val description = a.description.ifBlank { draft.description }
                        val tags = a.keywords.ifBlank { draft.keywords }
                        val full = if (tags.isBlank() || description.contains(tags)) description else "$description\n\n$tags"
                        session.webView?.evaluateJavascript(VintedPageScripts.fill(a.title, full, Money.formatPlain(a.priceCents))) { r ->
                            status = VintedPageScripts.fillSummary(VintedPageScripts.decode(r))
                        }
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
                    session.webView?.evaluateJavascript(VintedPageScripts.diagnostic) { r ->
                        diagnostic = VintedPageScripts.decode(r) ?: "Diagnostic impossible sur cette page."
                    }
                }) { Text("Diagnostic") }
                TextButton(onClick = {
                    CookieManager.getInstance().removeAllCookies(null)
                    WebStorage.getInstance().deleteAllData()
                    session.webView?.loadUrl(HOME)
                    status = "Session Vinted effacée de l'application."
                }) { Text("Se déconnecter") }
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

                        override fun onPageFinished(view: WebView, url: String?) {
                            loading = false
                            currentUrl = url.orEmpty()
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
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
                    val start = article?.vintedUrl?.takeIf { VintedLinks.isVintedUrl(it) } ?: HOME
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
