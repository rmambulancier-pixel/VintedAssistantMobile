package fr.vintedassistant.mobile.ui.purchases

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import fr.vintedassistant.mobile.data.PurchaseRepository
import fr.vintedassistant.mobile.data.importer.SupplierOrderParser
import fr.vintedassistant.mobile.domain.Money
import fr.vintedassistant.mobile.ui.container
import fr.vintedassistant.mobile.ui.copyToClipboard
import fr.vintedassistant.mobile.ui.vinted.VintedPageScripts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

const val SUPPLIER_NIHAO = "Nihaojewelry"
private const val HOME = "https://www.nihaojewelry.com/"
private val ALLOWED = Regex("""(^|\.)(nihaojewelry\.com|accounts\.google\.com|appleid\.apple\.com|facebook\.com|paypal\.com)$""")

/** Scripts de lecture des pages de commande Nihaojewelry. Lecture seule, à la demande. */
object SupplierPageScripts {
    /** Page d'une commande : référence, date, produits (photo, nom, prix, quantité), port, frais, total. */
    val order: String = """
        (function(){
          function vis(e){const r=e.getBoundingClientRect();return r.width>0&&r.height>0;}
          const money=/(?:€\s?\d{1,5}(?:[.,]\d{1,2})?|\d{1,5}(?:[.,]\d{1,2})?\s?€)/;
          const body=document.body.innerText||'';
          const lines=body.split('\n').map(s=>s.trim()).filter(Boolean);
          const out={url:location.href,ref:'',date:'',items:[],shipping:'',fees:'',total:''};
          // Référence Nihaojewelry : « NHFR610132392734 » (pas le colis « …B01 », pas les Sku « NH57549748 »).
          const nh=body.match(/\bNH[A-Z]{0,4}\d{9,}\b/);
          const ref=nh?[nh[0],nh[0]]:body.match(/(?:n[°o]\.?\s*de\s*commande|order\s*(?:no\.?|number|#|id))\s*[:#]?\s*([A-Z0-9][A-Z0-9-]{5,})/i);
          if(ref)out.ref=ref[1];
          // Date : ligne qui suit « Date de commande », sinon premier format de date reconnu.
          const dateRe=/\d{1,2}\s+[a-zéûôè]{3,9}\.?\s+\d{4}(?:,?\s+\d{1,2}:\d{2})?|\d{4}[-\/.]\d{1,2}[-\/.]\d{1,2}(?:\s+\d{1,2}:\d{2})?|\d{1,2}[-\/.]\d{1,2}[-\/.]\d{4}/i;
          for(let i=0;i<lines.length;i++){if(/date de commande|order date|date/i.test(lines[i])){const m=(lines[i]+' '+(lines[i+1]||'')).match(dateRe);if(m){out.date=m[0];break;}}}
          if(!out.date){const d=body.match(dateRe);if(d)out.date=d[0];}
          function after(re){for(let i=0;i<lines.length;i++){if(re.test(lines[i])){const m=(lines[i]+' '+(lines[i+1]||'')).match(money);if(m)return m[0];}}return '';}
          out.shipping=after(/frais de port|frais d'exp|livraison|exp[ée]dition|shipping|freight/i);
          out.fees=after(/frais de service|frais de traitement|service fee|handling|taxe|tax|d[ée]douanement|customs|douane/i);
          for(let i=lines.length-1;i>=0;i--){
            if(/total/i.test(lines[i])&&!/sous|sub|article|item|produit|poids|weight|masse|quantit/i.test(lines[i])){
              const m=(lines[i]+' '+(lines[i+1]||'')).match(money);if(m){out.total=m[0];break;}
            }
          }
          const seen=new Set();
          Array.from(document.querySelectorAll('img')).filter(vis).forEach(img=>{
            const r=img.getBoundingClientRect();if(r.width<40||r.width>420)return;
            let card=img;
            for(let i=0;i<6&&card.parentElement;i++){card=card.parentElement;if(money.test(card.innerText||''))break;}
            const text=(card.innerText||'').trim();const pm=text.match(money);if(!pm||seen.has(card))return;
            if(text.length>600)return; // bloc trop large : pas une ligne de produit
            seen.add(card);
            const parts=text.split('\n').map(s=>s.trim());
            // Nom : première ligne de texte du produit (le texte alternatif de l'image est souvent générique).
            let name=parts.filter(s=>s.length>8&&!money.test(s)&&!/^sku|quantit|qty|qt[ée]|masse|poids|weight|^x\s?\d/i.test(s))[0]||(img.alt||'').trim();
            name=name.replace(/\s*(\.\.\.|…)$/,'');
            // Variante (« 4mm Argent|18 pouces ») tirée de la ligne Sku, pour distinguer les déclinaisons.
            const sku=text.match(/sku\s*:\s*([A-Z0-9]+)\s*\|?\s*([^\n]*(?:\n(?!quantit|qt|masse|poids)[^\n]*)?)/i);
            if(sku&&sku[2]){const v=sku[2].replace(/\s*\n\s*/g,' ').replace(/\|/g,' | ').trim();if(v)name+=' ('+v+')';}
            const q=text.match(/(?:qt[ée]|quantit[ée]|qty)\s*:?\s*(\d{1,4})/i)||text.match(/[x×]\s?(\d{1,4})\b/i)||text.match(/(\d{1,4})\s*(?:pcs|pi[èe]ces?)/i);
            const link=card.querySelector('a[href]');
            out.items.push({name:name.slice(0,200),price:pm[0],qty:q?q[1]:'1',image:img.currentSrc||img.src||'',url:link?link.href:''});
          });
          return JSON.stringify(out);
        })();
    """.trimIndent()

    /** Liste des commandes : liens vers le détail de chaque commande. */
    val orderLinks: String = """
        (function(){
          const set=new Set();
          Array.from(document.querySelectorAll('a[href]')).forEach(a=>{
            const h=a.href.split('#')[0];
            if(/order/i.test(h)&&(/detail|info|view|id=|\d{6,}/i.test(h))&&h!==location.href.split('#')[0])set.add(h);
          });
          window.scrollTo(0,document.body.scrollHeight);
          return JSON.stringify({links:Array.from(set).slice(0,100)});
        })();
    """.trimIndent()
}

private class Session {
    var web: WebView? = null
    var loaded: CompletableDeferred<Unit>? = null
}

private suspend fun WebView.js(script: String): String? = suspendCancellableCoroutine { c ->
    evaluateJavascript(script) { r -> if (c.isActive) c.resume(VintedPageScripts.decode(r)) }
}

private suspend fun open(session: Session, url: String): Boolean {
    val web = session.web ?: return false
    val done = CompletableDeferred<Unit>()
    session.loaded = done
    web.loadUrl(url)
    val ok = withTimeoutOrNull(25_000) { done.await() } != null
    session.loaded = null
    return ok
}

private suspend fun importCurrent(session: Session, repo: PurchaseRepository): PurchaseRepository.SaveResult? {
    val json = session.web?.js(SupplierPageScripts.order) ?: return null
    val order = runCatching { SupplierOrderParser.parse(json) }.getOrNull() ?: return null
    return repo.saveOrder(SUPPLIER_NIHAO, order)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SupplierWebScreen(modifier: Modifier, onClose: () -> Unit) {
    val context = LocalContext.current
    val repo = context.container().purchaseRepository
    val session = remember { Session() }
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var diagnostic by remember { mutableStateOf<String?>(null) }

    BackHandler { val w = session.web; if (w != null && w.canGoBack()) w.goBack() else onClose() }
    DisposableEffect(Unit) { onDispose { session.web?.destroy(); session.web = null } }

    fun runTask(block: suspend () -> String) {
        job = scope.launch {
            try {
                status = block()
            } catch (e: CancellationException) {
                status = "Import arrêté. Les commandes déjà importées sont conservées."
                throw e
            } catch (e: Exception) {
                status = "Import interrompu par une erreur. Les commandes déjà importées sont conservées."
            } finally {
                progress = null
                job = null
            }
        }
    }

    diagnostic?.let { text ->
        AlertDialog(
            onDismissRequest = { diagnostic = null },
            title = { Text("Diagnostic de la page") },
            text = { Text(text.take(4000), Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { copyToClipboard(context, "Diagnostic", text); diagnostic = null }) { Text("Copier") } },
            dismissButton = { TextButton(onClick = { diagnostic = null }) { Text("Fermer") } },
        )
    }

    Column(modifier) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Connecte-toi à Nihaojewelry, ouvre tes commandes, puis importe. Lecture seule : rien n'est commandé ni modifié.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(enabled = job == null, onClick = {
                    runTask {
                        progress = "Lecture de la commande…"
                        val r = importCurrent(session, repo)
                            ?: return@runTask "Aucune commande reconnue sur cette page. Ouvre le détail d'une commande, ou lance le diagnostic et transmets-le."
                        if (r.created) "Commande importée : ${r.items} produit(s). Retrouve-la dans Achats." else "Commande déjà importée."
                    }
                }) { Text("Importer cette commande") }
                OutlinedButton(enabled = job == null, onClick = {
                    runTask {
                        val listUrl = url
                        progress = "Recherche des commandes…"
                        val raw = session.web?.js(SupplierPageScripts.orderLinks)
                        delay(1_500)
                        val links = runCatching {
                            val arr: JSONArray = JSONObject(raw ?: "{}").optJSONArray("links") ?: JSONArray()
                            (0 until arr.length()).map { arr.getString(it) }
                        }.getOrDefault(emptyList())
                        if (links.isEmpty()) return@runTask "Aucun lien de commande trouvé. Ouvre la liste « Mes commandes », ou lance le diagnostic."
                        var created = 0
                        var known = 0
                        var failed = 0
                        links.forEachIndexed { i, link ->
                            progress = "Commande ${i + 1} / ${links.size}"
                            if (!open(session, link)) { failed++; return@forEachIndexed }
                            delay(2_000)
                            val r = importCurrent(session, repo)
                            when {
                                r == null -> failed++
                                r.created -> created++
                                else -> known++
                            }
                            delay(1_500)
                        }
                        open(session, listUrl)
                        "$created commande(s) importée(s), $known déjà connue(s)" + if (failed > 0) ", $failed non reconnue(s)." else "."
                    }
                }) { Text("Importer toutes mes commandes") }
                OutlinedButton(onClick = {
                    scope.launch { diagnostic = session.web?.js(VintedPageScripts.diagnostic) ?: "Diagnostic impossible." }
                }) { Text("Diagnostic") }
            }
            progress?.let { p ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(Modifier.padding(8.dp)) {
                        Text(p, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { job?.cancel() }) { Text("Arrêter") }
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
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val u = request.url
                            if (u.scheme == "https" && ALLOWED.containsMatchIn(u.host?.lowercase().orEmpty())) return false
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            return true
                        }
                        override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) { loading = true; url = u.orEmpty() }
                        override fun onPageFinished(view: WebView, u: String?) {
                            loading = false
                            url = u.orEmpty()
                            session.loaded?.complete(Unit)
                        }
                    }
                    session.web = this
                    loadUrl(HOME)
                }
            },
        )
    }
}

/** Montant lisible pour les écrans d'achats. */
fun euros(cents: Long) = Money.formatEuros(cents)
