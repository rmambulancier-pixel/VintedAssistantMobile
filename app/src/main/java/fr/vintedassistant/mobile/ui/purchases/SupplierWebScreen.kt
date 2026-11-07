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
          const out={url:location.href,ref:'',date:'',items:[],shipping:'',fees:'',total:'',eta:'',count:''};
          // Référence Nihaojewelry : « NHFR610132392734 » (pas le colis « …B01 », pas les Sku « NH57549748 »).
          const nh=body.match(/\bNH[A-Z]{0,4}\d{9,}\b/);
          const ref=nh?[nh[0],nh[0]]:body.match(/(?:n[°o]\.?\s*de\s*commande|order\s*(?:no\.?|number|#|id))\s*[:#]?\s*([A-Z0-9][A-Z0-9-]{5,})/i);
          if(ref)out.ref=ref[1];
          // Date : ligne qui suit « Date de commande », sinon premier format de date reconnu.
          const dateRe=/\d{1,2}\s+[a-zéûôè]{3,9}\.?\s+\d{4}(?:,?\s+\d{1,2}:\d{2})?|[a-z]{3,9}\.?\s+\d{1,2},?\s+\d{4}(?:,?\s+\d{1,2}:\d{2}(?::\d{2})?(?:\s*[ap]m)?)?|\d{4}[-\/.]\d{1,2}[-\/.]\d{1,2}(?:\s+\d{1,2}:\d{2})?|\d{1,2}[-\/.]\d{1,2}[-\/.]\d{4}/i;
          for(let i=0;i<lines.length;i++){if(/date\s+de\s+commande|order\s+date|order\s+time|placed\s+on|date/i.test(lines[i])){const m=(lines[i]+' '+(lines[i+1]||'')).match(dateRe);if(m){out.date=m[0];break;}}}
          if(!out.date){const d=body.match(dateRe);if(d)out.date=d[0];}
          // Livraison annoncée (« Prévu pour être livré le » puis la période) : commande pas encore reçue.
          for(let i=0;i<lines.length;i++){if(/pr[ée]vu\s+pour\s+[êe]tre\s+livr|livraison\s+pr[ée]vue|estimated\s+(?:delivery|arrival)|expected\s+(?:delivery|to\s+be\s+delivered)|delivered\s+(?:on|by|between)/i.test(lines[i])){out.eta=(lines[i+1]||'').slice(0,60);break;}}
          function after(re){for(let i=0;i<lines.length;i++){if(re.test(lines[i])){const m=(lines[i]+' '+(lines[i+1]||'')).match(money);if(m)return m[0];}}return '';}
          out.shipping=after(/frais de port|frais d'exp|livraison|exp[ée]dition|shipping|freight/i);
          out.fees=after(/frais de service|frais de traitement|service fee|handling|taxe|tax|d[ée]douanement|customs|douane/i);
          for(let i=lines.length-1;i>=0;i--){
            if(/total/i.test(lines[i])&&!/sous|sub|article|item|produit|poids|weight|masse|quantit/i.test(lines[i])){
              const m=(lines[i]+' '+(lines[i+1]||'')).match(money);if(m){out.total=m[0];break;}
            }
          }
          // Nombre de pièces annoncé par le site (« La quantité de produit 24 ») : contrôle de la lecture.
          for(let i=0;i<lines.length;i++){if(/quantit[ée]\s+de\s+produit|product\s+quantity|total\s+items/i.test(lines[i])){const m=(lines[i]+' '+(lines[i+1]||'')).match(/(\d{1,4})/);if(m){out.count=m[1];break;}}}
          const seen=new Set();
          // Photo du produit : la plus grande image à peu près carrée ; les bandeaux larges (« SHIP ») et les icônes sont écartés.
          function bestImg(card){
            const urls=i=>[i.currentSrc,i.src,i.getAttribute('data-src'),i.getAttribute('data-original'),i.getAttribute('data-lazy-src'),
              (i.getAttribute('srcset')||'').split(' ')[0]].filter(u=>u&&/^https:/.test(u));
            const bad=/icon|cart|panier|badge|logo|flag|sprite|ship|\.svg|\.gif/i;
            const area=i=>{const r=i.getBoundingClientRect();return r.width*r.height;};
            const ok=Array.from(card.querySelectorAll('img')).filter(i=>{
              const r=i.getBoundingClientRect();
              if(r.width&&r.height&&(r.width<50||r.height<50||r.width/r.height>1.6||r.height/r.width>1.6))return false;
              const u=urls(i);
              return u.length>0&&!u.some(x=>bad.test(x))&&!bad.test((i.className||'')+' '+(i.alt||''));
            });
            return ok.sort((x,y)=>area(y)-area(x))[0]||null;
          }
          function readCard(card,img,nameBlock){
            const text=(card.innerText||'').trim();
            if(!money.test(text)||text.length>600||seen.has(card))return;
            seen.add(card);
            // Prix payé = le plus bas de la carte (le prix barré d'origine est plus haut ; « Essai gratuit » = 0).
            const all=text.match(new RegExp(money.source,'g'))||[];
            const val=s=>parseFloat(s.replace(/[^\d,.]/g,'').replace(',','.'));
            const pm=[all.reduce((a,b)=>val(b)<val(a)?b:a)];
            // Nom : première ligne de texte du produit (le texte alternatif de l'image est souvent générique).
            const parts=((nameBlock||card).innerText||'').split('\n').map(s=>s.trim());
            const titled=card.querySelector('a[title],[title]');
            let name=parts.filter(s=>s.length>8&&!money.test(s)&&!/^sku|quantit|qty|qt[ée]|masse|poids|weight|canceled|cancelled|^x\s?\d|d[ée]baller|unpack|ajouter au panier|add to cart/i.test(s))[0]
              ||(img&&img.alt||'').trim()||(titled&&titled.getAttribute('title')||'').trim();
            name=name.replace(/^(essai\s+gratuit|free\s+trial|gratuit|nouveau|new|hot|promo)\s*/i,'').replace(/\s*(\.\.\.|…)$/,'');
            // Variante (« 4mm Argent|18 pouces ») tirée de la ligne Sku, pour distinguer les déclinaisons.
            const sku=text.match(/sku\s*:\s*([A-Z0-9]+)\s*\|?\s*([^\n]*(?:\n(?!quantit|qt|masse|poids)[^\n]*)?)/i);
            const stop=/\s*(?:order\s*qty|canceled\s*qty|cancelled\s*qty|qt[ée]|quantit[ée]|qty|weight|total\s*weight|masse|poids|€|\d+(?:[.,]\d+)?\s*€).*$/is;
            const variant=sku&&sku[2]?sku[2].replace(/\s*\n\s*/g,' ').replace(stop,'').replace(/\|/g,' | ').replace(/\s*\|\s*$/,'').replace(/\s+/g,' ').trim():'';
            if(!name)name='Produit '+(sku?sku[1]:'');
            if(variant)name+=' ('+variant+')';
            const q=text.match(/(?:qt[ée]|quantit[ée]|qty)\s*:?\s*(\d{1,4})/i)||text.match(/[x×]\s?(\d{1,4})\b/i)||text.match(/(\d{1,4})\s*(?:pcs|pi[èe]ces?)/i);
            const link=card.querySelector('a[href]');
            // Image : les pages chargent les photos au défilement (data-src) ; on ignore les images « data: ».
            const cands=img?[img.currentSrc,img.src,img.getAttribute('data-src'),img.getAttribute('data-original'),img.getAttribute('data-lazy-src'),
              (img.getAttribute('srcset')||'').split(' ')[0]].filter(u=>u&&/^https:/.test(u)):[];
            out.items.push({name:name.slice(0,200),price:pm[0].replace(/[  ]/g,' '),qty:q?q[1]:'1',image:cands[0]||'',url:link?link.href:'',sku:sku?sku[1]:'',
              free:/essai\s+gratuit|free\s+trial/i.test(text)});
          }
          // 1. Lecture d'origine, éprouvée sur la vraie page : chaque photo produit visible mène à sa ligne.
          Array.from(document.querySelectorAll('img')).filter(vis).forEach(img=>{
            const r=img.getBoundingClientRect();if(r.width<40||r.width>420)return;
            if(/icon|cart|panier|badge|logo|flag|sprite|ship/i.test((img.currentSrc||img.src||'')+' '+(img.className||'')+' '+(img.alt||'')))return;
            let card=img;
            for(let i=0;i<6&&card.parentElement;i++){card=card.parentElement;if(money.test(card.innerText||''))break;}
            readCard(card,img,card);
          });
          // 2. Rattrapage : produits dont la photo n'était pas chargée, repérés par leur ligne « Sku: ».
          const known=new Set(out.items.map(i=>i.sku).filter(Boolean));
          const skuOf=e=>{const m=(e.innerText||'').match(/sku\s*:\s*([A-Z0-9]+)/i);return m?m[1]:'';};
          const skuCount=e=>((e.innerText||'').match(/sku\s*:/gi)||[]).length;
          const blocks=Array.from(document.querySelectorAll('div,li,tr,td,section,article'))
            .filter(e=>skuCount(e)===1&&(e.innerText||'').length<=600&&money.test(e.innerText||''));
          blocks.filter(e=>!blocks.some(o=>o!==e&&e.contains(o))).forEach(b=>{
            if(known.has(skuOf(b)))return;
            let card=b;
            for(let i=0;i<4&&!card.querySelector('img')&&card.parentElement&&skuCount(card.parentElement)===1;i++)card=card.parentElement;
            known.add(skuOf(b));
            readCard(card,bestImg(card),b);
          });
          return JSON.stringify(out);
        })();
    """.trimIndent()

    /**
     * Copie de la page affichée pour diagnostic : structure et textes des produits, sans scripts ni styles.
     * Adresse, e-mails et numéros de téléphone sont retirés.
     */
    val pageCopy: String = """
        (function(){
          const root=document.body.cloneNode(true);
          root.querySelectorAll('script,style,noscript,iframe,svg,link,meta').forEach(e=>e.remove());
          // Adresse : le bloc qui suit le libellé est vidé (ou tout le bloc s'il ne contient que l'adresse).
          const label=/^(adresse de livraison|adresse|shipping address|delivery address|address)\s*:?$/i;
          Array.from(root.querySelectorAll('*')).filter(e=>label.test((e.textContent||'').trim())).forEach(e=>{
            const box=e.parentElement;
            const txt=box?(box.textContent||''):'';
            if(box&&box!==root&&txt.length<400&&!/sku|order|commande|total|livraison pr|m[ée]thode|method/i.test(txt.replace(e.textContent,''))){box.textContent='[adresse retirée]';return;}
            let n=e.nextElementSibling;for(let k=0;k<4&&n;k++){n.textContent='[adresse retirée]';n=n.nextElementSibling;if(n&&/livraison|shipping|method|m[ée]thode|date|order/i.test(n.textContent||''))break;}
          });
          root.querySelectorAll('*').forEach(e=>{
            Array.from(e.attributes).forEach(a=>{
              if(!/^(class|src|data-src|data-original|srcset|href|alt|title|style)$/.test(a.name))e.removeAttribute(a.name);
              else if(/^data:/.test(a.value))e.setAttribute(a.name,'data:…');
            });
          });
          let html=root.innerHTML;
          html=html.replace(/[\w.+-]+@[\w-]+\.[\w.]+/g,'[e-mail]').replace(/(?:\+33|\b0)[1-9](?:[\s.-]?\d{2}){4}\b/g,'[numéro]');
          return location.href.split('?')[0]+'\n'+html.slice(0,1500000);
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
    val web = session.web ?: return null
    // Défilement progressif : la page ne charge les photos des produits qu'à l'écran.
    repeat(12) { i ->
        web.js("(function(){window.scrollTo(0, document.body.scrollHeight*${i + 1}/12);return '';})();")
        delay(250)
    }
    web.js("(function(){window.scrollTo(0,0);return '';})();")
    delay(400)
    val json = web.js(SupplierPageScripts.order) ?: return null
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
                        (if (r.created) "Commande importée : ${r.pieces} pièce(s). Retrouve-la dans Achats."
                        else "Commande mise à jour : ${r.pieces} pièce(s), rattachements conservés.") +
                            (r.expectedPieces?.takeIf { it != r.pieces }?.let { " Attention : le site en annonce $it. Fais défiler toute la commande puis réimporte-la." } ?: "")
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
                        var incomplete = 0
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
                            if (r != null && r.expectedPieces != null && r.expectedPieces != r.pieces) incomplete++
                            delay(1_500)
                        }
                        open(session, listUrl)
                        "$created commande(s) importée(s), $known mise(s) à jour" + (if (failed > 0) ", $failed non reconnue(s)." else ".") +
                            (if (incomplete > 0) " $incomplete commande(s) lue(s) incomplètement : ouvre-les et réimporte-les une par une." else "")
                    }
                }) { Text("Importer toutes mes commandes") }
                OutlinedButton(onClick = {
                    scope.launch { diagnostic = session.web?.js(VintedPageScripts.diagnostic) ?: "Diagnostic impossible." }
                }) { Text("Diagnostic") }
                OutlinedButton(onClick = {
                    scope.launch {
                        val html = session.web?.js(SupplierPageScripts.pageCopy)
                        if (html.isNullOrBlank()) { status = "Copie de la page impossible."; return@launch }
                        try {
                            val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                val dir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
                                java.io.File(dir, "page-nihaojewelry.html").apply { writeText(html) }
                            }
                            context.startActivity(context.container().dataExport.shareIntent(file, "text/html"))
                        } catch (e: Exception) {
                            status = "Copie de la page impossible."
                        }
                    }
                }) { Text("Envoyer la page") }
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
