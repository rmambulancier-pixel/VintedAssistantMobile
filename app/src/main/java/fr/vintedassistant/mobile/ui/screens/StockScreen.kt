@file:OptIn(ExperimentalMaterial3Api::class)

package fr.vintedassistant.mobile.ui.screens

import androidx.compose.material3.HorizontalDivider
import fr.vintedassistant.mobile.domain.Sale
import fr.vintedassistant.mobile.domain.StockBoard
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.vintedassistant.mobile.domain.Article
import fr.vintedassistant.mobile.domain.ArticleStatus
import fr.vintedassistant.mobile.domain.Money
import fr.vintedassistant.mobile.domain.StockLedger
import fr.vintedassistant.mobile.domain.StockLot
import fr.vintedassistant.mobile.domain.StockOverview
import fr.vintedassistant.mobile.domain.StockProduct
import fr.vintedassistant.mobile.ui.MessageCard
import fr.vintedassistant.mobile.ui.PhotoThumb
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Vues du stock. « Pas encore en vente » compte des pièces achetées ; les autres, des fiches. */
private enum class StockView(val label: String) {
    NOT_LISTED("Pas encore en vente"),
    ONLINE("En ligne"),
    DRAFT("Brouillons"),
    RESERVED("Réservés"),
    SOLD("Vendus"),
    NO_PURCHASE("Sans achat"),
    ALL("Tous"),
}

private fun StockView.accepts(a: Article, stock: StockOverview?): Boolean = when (this) {
    StockView.NOT_LISTED -> false
    StockView.ONLINE -> a.status == ArticleStatus.ONLINE
    StockView.DRAFT -> a.status == ArticleStatus.DRAFT
    StockView.RESERVED -> a.status == ArticleStatus.RESERVED
    StockView.SOLD -> a.status == ArticleStatus.SOLD
    StockView.NO_PURCHASE -> stock?.unlinked?.any { it.id == a.id } ?: (a.purchaseItemId == null)
    StockView.ALL -> true
}

/** Actions de l'écran Stock, regroupées pour garder un appel lisible. */
class StockActions(
    val openArticle: (Long) -> Unit,
    val openPurchases: () -> Unit,
    val openVinted: () -> Unit,
    /** Copie de la fiche [templateId], rattachée au lot [itemId] s'il est donné. */
    val relaunch: (templateId: Long, itemId: Long?) -> Unit,
    val draftFromPurchase: (itemId: Long) -> Unit,
    val link: (article: Article, lot: StockLot) -> Unit,
    /** La commande [orderId] est arrivée : ses pièces deviennent vendables. */
    val markReceived: (orderId: Long) -> Unit,
    /** Mise en vente express de toutes les pièces reçues sans fiche. */
    val expressAll: () -> Unit = {},
    /** Inventaire : compter ce qu'on a en main et corriger les écarts. */
    val inventory: () -> Unit = {},
)

@Composable
fun StockScreen(
    articles: List<Article>?,
    stock: StockOverview?,
    message: String?,
    onDismissMessage: () -> Unit,
    modifier: Modifier,
    actions: StockActions,
    sales: List<Sale> = emptyList(),
) {
    var view by rememberSaveable { mutableStateOf(StockView.ONLINE) }
    var query by rememberSaveable { mutableStateOf("") }
    var linkProduct by remember { mutableStateOf<StockProduct?>(null) }
    var linkArticle by remember { mutableStateOf<Article?>(null) }
    var copyFor by remember { mutableStateOf<StockProduct?>(null) }
    var blindRelaunch by remember { mutableStateOf<Article?>(null) }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.FRANCE) }

    linkProduct?.let { product ->
        ArticlePickerDialog(
            product = product,
            candidates = stock?.unlinked.orEmpty(),
            onDismiss = { linkProduct = null },
            onPick = { a -> linkProduct = null; product.nextLot?.let { actions.link(a, it) } },
        )
    }
    copyFor?.let { product ->
        TemplatePickerDialog(
            product = product,
            articles = articles.orEmpty(),
            stock = stock,
            onDismiss = { copyFor = null },
            onPick = { a -> copyFor = null; product.nextLot?.let { actions.relaunch(a.id, it.itemId) } },
        )
    }
    linkArticle?.let { article ->
        LotPickerDialog(
            article = article,
            products = stock?.products.orEmpty(),
            dateFormat = dateFormat,
            onDismiss = { linkArticle = null },
            onPick = { lot -> linkArticle = null; actions.link(article, lot) },
        )
    }
    blindRelaunch?.let { a ->
        AlertDialog(
            onDismissRequest = { blindRelaunch = null },
            title = { Text("Aucun achat rattaché") },
            text = {
                Text(
                    "Cette fiche n'est rattachée à aucun achat : impossible de vérifier qu'il te reste ce bijou. " +
                        "Le mieux est de la rattacher d'abord à son achat (bouton « Rattacher un achat »). " +
                        "Tu peux aussi créer la copie quand même : elle gardera le coût d'achat de l'ancienne fiche.",
                )
            },
            confirmButton = { TextButton(onClick = { blindRelaunch = null; actions.relaunch(a.id, null) }) { Text("Copier quand même") } },
            dismissButton = { TextButton(onClick = { blindRelaunch = null }) { Text("Annuler") } },
        )
    }

    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        message?.let { msg -> item { Column(Modifier.padding(top = 8.dp)) { MessageCard(msg, onDismissMessage) } } }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.openVinted, modifier = Modifier.weight(1f)) { Text("Vinted intégré") }
                OutlinedButton(onClick = actions.openPurchases, modifier = Modifier.weight(1f)) { Text("Achats fournisseur") }
            }
        }
        if (articles == null || stock == null) {
            item { CircularProgressIndicator() }
            return@LazyColumn
        }
        item { StockSummary(stock, articles, sales) }
        item {
            OutlinedButton(onClick = actions.inventory, modifier = Modifier.fillMaxWidth()) { Text("Faire l'inventaire (compter mes bijoux)") }
        }
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("Rechercher (nom, marque, catégorie)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(StockView.entries) { v ->
                    val count = if (v == StockView.NOT_LISTED) stock.notListed else articles.count { v.accepts(it, stock) }
                    FilterChip(selected = view == v, onClick = { view = v }, label = { Text("${v.label} ($count)") })
                }
            }
        }
        val q = StockLedger.normalize(query)
        fun matches(vararg texts: String) = q.isEmpty() || texts.any { StockLedger.normalize(it).contains(q) }

        if (view == StockView.NOT_LISTED) {
            if (stock.notListed > 0) {
                item {
                    Button(onClick = actions.expressAll, modifier = Modifier.fillMaxWidth()) {
                        Text("Tout mettre en vente (${stock.products.count { it.notListed > 0 }} bijou(x))")
                    }
                    Text(
                        "Une fiche par bijou : texte et prix repris d'une annonce du même modèle s'il y en a une, " +
                            "sinon rédigés pour toi. Tu ajoutes les photos du vrai bijou, puis tout part dans Vinted intégré.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            val shown = stock.products
                .filter { (it.notListed > 0 || it.inTransit > 0) && matches(it.name, it.template?.title.orEmpty()) }
                .sortedBy { if (it.notListed > 0) 0 else 1 }
            item {
                Text(
                    "Pièces reçues qui n'ont encore aucune fiche : ${stock.notListed}, valeur d'achat ${Money.formatEuros(stock.notListedValueCents)}." +
                        if (stock.inTransit > 0) " Puis les pièces en réassort : ${stock.inTransit}, " +
                            "${Money.formatEuros(stock.inTransitValueCents)}, à mettre en vente à réception." else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (shown.isEmpty()) {
                item {
                    Text(
                        if (stock.products.isEmpty()) "Aucun achat enregistré. Importe tes commandes depuis « Achats fournisseur »."
                        else "Tout ce que tu as acheté a déjà une fiche.",
                        Modifier.padding(vertical = 16.dp),
                    )
                }
            }
            items(shown, key = { it.key }) { product ->
                ProductCard(
                    product = product,
                    dateFormat = dateFormat,
                    onRelaunch = { t -> actions.relaunch(t.id, product.nextLot?.itemId) },
                    onDraft = { product.nextLot?.let { actions.draftFromPurchase(it.itemId) } },
                    onLink = { linkProduct = product },
                    onCopyFrom = { copyFor = product },
                    onOpen = actions.openArticle,
                    onReceived = actions.markReceived,
                )
            }
        } else {
            val shown = articles.filter { view.accepts(it, stock) && matches(it.title, it.brand, it.category, it.size) }
            item {
                Text(
                    "${shown.size} fiche(s) • coût ${Money.formatEuros(shown.sumOf { it.costCents })} • " +
                        "prix affichés ${Money.formatEuros(shown.sumOf { it.priceCents })}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (view == StockView.NO_PURCHASE && shown.isNotEmpty()) {
                item {
                    Text(
                        "Ces fiches ne sont rattachées à aucun achat : leur coût est saisi à la main ou inconnu. " +
                            "Rattache-les pour que le stock et les marges soient exacts.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (shown.isEmpty()) {
                item {
                    Text(
                        if (articles.isEmpty()) "Aucune fiche. Appuie sur + pour créer la première." else "Aucune fiche dans cette vue.",
                        Modifier.padding(vertical = 16.dp),
                    )
                }
            }
            items(shown, key = { it.id }) { article ->
                val product = stock.productOf(article)
                ArticleRow(article, onClick = { actions.openArticle(article.id) }, footer = {
                    StockFooter(
                        article = article,
                        product = product,
                        dateFormat = dateFormat,
                        onRelaunch = {
                            val lot = product?.nextLot
                            if (product == null) blindRelaunch = article else if (lot != null) actions.relaunch(article.id, lot.itemId)
                        },
                        onLink = { linkArticle = article },
                    )
                })
            }
        }
        item { Text(" ", Modifier.padding(bottom = 72.dp)) }
    }
}

/** Bilan pièce par pièce, en blocs qui ne se recouvrent pas : chez moi, en réassort, en route, terminées. */
@Composable
private fun StockSummary(stock: StockOverview, articles: List<Article>, sales: List<Sale>) {
    val today = remember { java.time.LocalDate.now() }
    val board = remember(stock, sales) { StockBoard.build(stock, sales, today) }
    val orderDate = remember { DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.FRANCE) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (stock.bought == 0 && articles.isEmpty()) {
                Text("Mon stock", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Aucun achat enregistré : importe tes commandes fournisseur pour suivre aussi les bijoux pas encore mis en vente.",
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Column
            }
            SummaryBlock(
                title = "Chez moi",
                total = board.inHand,
                detail = "valeur d'achat ${Money.formatEuros(board.inHandCostCents)}",
                accent = MaterialTheme.colorScheme.primary,
            ) {
                SummaryLine("À mettre en vente", board.toList, highlight = true, hint = "reçues, sans annonce")
                SummaryLine("En préparation", board.drafts, hint = "brouillons")
                SummaryLine("En vente sur Vinted", board.online, hint = if (board.online > 0) "affiché ${Money.formatEuros(board.onSaleValueCents)}" else null)
                if (board.reserved > 0) SummaryLine("Réservées", board.reserved)
                SummaryLine("Vendues, colis à préparer", board.toShip, highlight = board.toShip > 0, warn = true)
            }
            HorizontalDivider()
            SummaryBlock(
                title = "En réassort",
                total = board.restocking,
                detail = if (board.restocking > 0) "commandées, pas encore arrivées • ${Money.formatEuros(board.restockCostCents)}" else "aucune commande en attente",
                accent = MaterialTheme.colorScheme.tertiary,
            ) {
                board.orders.forEach { o ->
                    Text(
                        "• ${o.pieces} pièce(s), commande du ${orderDate.format(java.util.Date(o.orderedAt))} : ${StockBoard.etaLabel(o, today)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (o.eta != null && o.eta.isBefore(today)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
            SummaryBlock(
                title = "Vendues",
                total = board.sold,
                detail = null,
                accent = MaterialTheme.colorScheme.secondary,
            ) {
                SummaryLine("Colis à préparer", board.toShip, hint = "encore chez moi")
                SummaryLine("En route vers l'acheteur", board.shipping, hint = "expédiées")
                SummaryLine("Terminées", board.done, hint = "livrées")
            }
            if (board.neverReceived > 0 || board.withdrawn > 0) {
                HorizontalDivider()
                if (board.neverReceived > 0) SummaryLine("Manquantes", board.neverReceived, hint = "jamais reçues ou perdues, hors stock")
                if (board.withdrawn > 0) SummaryLine("Retirées de Vinted", board.withdrawn)
            }
            val outside = stock.unlinked
            if (outside.isNotEmpty()) {
                Text(
                    "Dont ${outside.size} fiche(s) sans achat rattaché : coût d'achat à vérifier (vue « Sans achat »).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SummaryBlock(title: String, total: Int, detail: String?, accent: androidx.compose.ui.graphics.Color, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = accent, fontWeight = FontWeight.Bold)
            detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(total.toString(), style = MaterialTheme.typography.headlineSmall, color = accent, fontWeight = FontWeight.Bold)
    }
    content()
}

@Composable
private fun SummaryLine(label: String, value: Int, highlight: Boolean = false, hint: String? = null, warn: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            hint?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
        }
        Text(
            value.toString(),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (highlight && value > 0) FontWeight.Bold else FontWeight.Normal,
            color = when {
                warn && value > 0 -> MaterialTheme.colorScheme.error
                highlight && value > 0 -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

/** Bijou acheté qui a des pièces sans fiche : lots, prix d'achat et actions. */
@Composable
private fun ProductCard(
    product: StockProduct,
    dateFormat: DateFormat,
    onRelaunch: (Article) -> Unit,
    onDraft: () -> Unit,
    onLink: () -> Unit,
    onCopyFrom: () -> Unit,
    onOpen: (Long) -> Unit,
    onReceived: (Long) -> Unit,
) {
    val template = product.template
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PhotoThumb(template?.photos?.firstOrNull() ?: product.imageFile)
                Column(Modifier.weight(1f)) {
                    Text(template?.title ?: product.name, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                    if (template != null) Text("Fournisseur : ${product.name}", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    if (product.notListed > 0) Text(
                        "${product.notListed} pièce(s) reçue(s), pas encore en vente",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    if (product.inTransit > 0) Text(
                        "${product.inTransit} pièce(s) en réassort",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    val parts = buildList {
                        add("achetées ${product.bought}")
                        if (product.drafts > 0) add("brouillon ${product.drafts}")
                        if (product.forSale > 0) add("en ligne ${product.forSale}")
                        if (product.sold > 0) add("vendues ${product.sold}")
                    }
                    Text(parts.joinToString(" • ").replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall)
                }
            }
            product.lots.forEach { lot ->
                val date = dateFormat.format(Date(lot.orderedAt))
                if (lot.received) {
                    Text(
                        "Commande du $date : ${lot.remaining} libre(s) sur ${lot.quantity}, revient ${Money.formatEuros(lot.landedUnitCents)} pièce",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (lot.remaining > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                    )
                } else {
                    Text(
                        "Commande du $date : ${lot.quantity} en réassort" +
                            (if (lot.deliveryEta.isNotBlank()) " (prévue ${lot.deliveryEta})" else "") +
                            ", revient ${Money.formatEuros(lot.landedUnitCents)} pièce",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = { onReceived(lot.orderId) }) { Text("J'ai reçu cette commande") }
                }
            }
            if (product.notListed == 0) {
                Text(
                    "Mise en vente possible une fois la commande reçue.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                return@Column
            }
            val next = product.nextLot
            if (product.costVaries && next != null) {
                Text(
                    "Prix d'achat différent selon la commande : la prochaine fiche prendra ${Money.formatEuros(next.landedUnitCents)} " +
                        "(commande la plus ancienne d'abord).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (product.overLinked) {
                Text(
                    "Attention : ${product.articles.size} fiches pour ${product.bought} pièce(s) achetée(s). Vérifie les rattachements dans Achats.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (template != null) {
                Text(
                    "Remettre en vente crée un brouillon avec le texte, le prix (${Money.formatEuros(template.priceCents)}) et les photos de « ${template.title.take(50)} ».",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { onRelaunch(template) }) { Text("Remettre en vente") }
                    TextButton(onClick = { onOpen(template.id) }) { Text("Voir l'ancienne") }
                }
                Row {
                    TextButton(onClick = onDraft) { Text("Fiche vierge") }
                    TextButton(onClick = onLink) { Text("Rattacher une fiche") }
                }
                TextButton(onClick = onCopyFrom) { Text("Partir d'une fiche existante") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onCopyFrom) { Text("Partir d'une fiche existante") }
                }
                Row {
                    TextButton(onClick = onDraft) { Text("Fiche vierge") }
                    TextButton(onClick = onLink) { Text("Rattacher une fiche") }
                }
            }
        }
    }
}

/** Sous une fiche : d'où elle vient et, pour un bijou vendu encore en stock, la remise en vente. */
@Composable
private fun ColumnScope.StockFooter(
    article: Article,
    product: StockProduct?,
    dateFormat: DateFormat,
    onRelaunch: () -> Unit,
    onLink: () -> Unit,
) {
    if (product == null) {
        Text("Aucun achat rattaché", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        Row {
            TextButton(onClick = onLink) { Text("Rattacher un achat") }
            if (article.status == ArticleStatus.SOLD) TextButton(onClick = onRelaunch) { Text("Remettre en vente") }
        }
        return
    }
    // D'où vient la fiche : commande et coût, modifiable si le rattachement est faux.
    product.lots.firstOrNull { it.itemId == article.purchaseItemId }?.let { lot ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Achat : commande du ${dateFormat.format(Date(lot.orderedAt))}, ${Money.formatEuros(lot.landedUnitCents)}",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
            )
            TextButton(onClick = onLink) { Text("Changer d'achat") }
        }
    }
    if (article.status != ArticleStatus.SOLD) return
    if (product.notListed == 0 && product.inTransit > 0) {
        Text(
            "${product.inTransit} en réassort : mise en vente possible à l'arrivée.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        return
    }
    if (product.notListed > 0) {
        Text(
            "Encore ${product.notListed} en stock, pas encore en vente",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        Button(onClick = onRelaunch) { Text("Remettre en vente") }
    } else {
        val other = product.forSale + product.drafts
        Text(
            if (other > 0) "Plus de pièce libre : les autres ont déjà une fiche ($other)." else "Plus aucune pièce en stock pour ce bijou.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/** Choix de la fiche existante à rattacher à un bijou acheté (fiches sans achat, les plus ressemblantes d'abord). */
@Composable
private fun ArticlePickerDialog(product: StockProduct, candidates: List<Article>, onDismiss: () -> Unit, onPick: (Article) -> Unit) {
    val sorted = remember(product, candidates) {
        candidates.sortedByDescending { StockLedger.similarity(it.title, product.name) }
    }
    PickerDialog(
        title = "Quelle fiche vient de cet achat ?",
        header = "${product.name}\nRevient ${product.nextLot?.let { Money.formatEuros(it.landedUnitCents) } ?: "?"} pièce",
        headerImage = product.imageFile,
        empty = "Aucune fiche sans achat. Toutes tes fiches sont déjà rattachées.",
        entries = sorted.map { a -> Triple(a.photos.firstOrNull(), a.title, "${a.status.label} • coût ${if (a.costCents == 0L) "non saisi" else Money.formatEuros(a.costCents)}") },
        onDismiss = onDismiss,
        onPick = { i -> onPick(sorted[i]) },
    )
}

/**
 * Choix de l'achat d'où vient une fiche : une ligne par commande qui a une pièce libre, pour choisir
 * précisément le bon lot (même bijou acheté deux fois). Les plus ressemblants d'abord.
 */
@Composable
private fun LotPickerDialog(
    article: Article,
    products: List<StockProduct>,
    dateFormat: DateFormat,
    onDismiss: () -> Unit,
    onPick: (StockLot) -> Unit,
) {
    val entries = remember(article, products) {
        products.flatMap { p -> p.lots.filter { it.received && it.remaining > 0 && it.itemId != article.purchaseItemId }.map { p to it } }
            .sortedWith(compareByDescending<Pair<StockProduct, StockLot>> { StockLedger.similarity(article.title, it.first.name) }.thenBy { it.second.orderedAt })
    }
    PickerDialog(
        title = "De quel achat vient cette fiche ?",
        header = article.title,
        headerImage = article.photos.firstOrNull(),
        empty = "Aucune pièce libre dans tes achats reçus. Importe la commande correspondante dans « Achats fournisseur ».",
        entries = entries.map { (p, lot) ->
            Triple(
                p.imageFile,
                variantFirst(p.name),
                "Commande du ${dateFormat.format(Date(lot.orderedAt))} • ${lot.remaining} libre(s) • revient ${Money.formatEuros(lot.landedUnitCents)}",
            )
        },
        onDismiss = onDismiss,
        onPick = { i -> onPick(entries[i].second) },
    )
}

@Composable
private fun PickerDialog(
    title: String,
    header: String,
    headerImage: String?,
    empty: String,
    entries: List<Triple<String?, String, String>>,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PhotoThumb(headerImage, size = 48.dp)
                    Text(header, style = MaterialTheme.typography.bodySmall, maxLines = 4)
                }
                if (entries.isEmpty()) Text(empty, style = MaterialTheme.typography.bodySmall)
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    entries.forEachIndexed { i, (image, name, detail) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(i) }.padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PhotoThumb(image, size = 48.dp)
                            Column(Modifier.weight(1f)) {
                                Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                Text(detail, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
fun ArticleRow(
    article: Article,
    trailing: (@Composable () -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    onClick: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PhotoThumb(article.photos.firstOrNull())
            Column(Modifier.weight(1f)) {
                Text(article.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                val details = listOf(article.brand, article.size, article.condition).filter { it.isNotBlank() }.joinToString(" • ")
                if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                Text(
                    "${Money.formatEuros(article.priceCents)} • marge ${Money.formatEuros(article.marginCents)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (article.marginCents >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                val stats = listOfNotNull(
                    article.favoritesCount?.let { "$it favori(s)" },
                    article.viewsCount?.let { "$it vue(s)" },
                ).joinToString(" • ")
                Text(
                    article.status.label + if (stats.isNotEmpty()) " • $stats" else "",
                    style = MaterialTheme.typography.labelSmall,
                )
                footer?.invoke(this)
            }
            trailing?.invoke()
        }
    }
}

/** « Collier … (Or) » devient « [Or] Collier … » : la variante reste visible quand le nom est coupé. */
private fun variantFirst(name: String): String {
    val m = Regex("""^(.*?)\s*\(([^()]*(?:\([^()]*\))?[^()]*)\)\s*$""").find(name) ?: return name
    val variant = m.groupValues[2].trim()
    return if (variant.isEmpty()) name else "[$variant] ${m.groupValues[1].trim()}"
}

/** Nom fournisseur sans la variante finale : « Bracelet Cœur … (Argent + bleu) » → « Bracelet Cœur … ». */
private fun baseName(name: String): String =
    StockLedger.normalize(name.replace(Regex("""\s*\(([^()]*(?:\([^()]*\))?[^()]*)\)\s*$"""), ""))

/**
 * Fiche modèle pour une nouvelle annonce : d'abord les fiches du même modèle fournisseur (toutes couleurs,
 * même vendues), puis les plus ressemblantes. La copie reprend texte, prix et photos ; on adapte ensuite la couleur.
 */
@Composable
private fun TemplatePickerDialog(
    product: StockProduct,
    articles: List<Article>,
    stock: StockOverview?,
    onDismiss: () -> Unit,
    onPick: (Article) -> Unit,
) {
    val sorted = remember(product, articles, stock) {
        val base = baseName(product.name)
        val family = stock?.products.orEmpty().filter { baseName(it.name) == base }.flatMap { it.articles }.map { it.id }.toSet()
        articles.filter { it.status != ArticleStatus.WITHDRAWN }
            .sortedWith(compareByDescending<Article> { it.id in family }.thenByDescending { StockLedger.similarity(it.title, product.name) })
    }
    PickerDialog(
        title = "Partir de quelle fiche ?",
        header = "${product.name}\nLa copie reprend texte, prix et photos ; change ensuite la couleur si besoin.",
        headerImage = product.imageFile,
        empty = "Aucune fiche à copier.",
        entries = sorted.map { a ->
            Triple(a.photos.firstOrNull(), a.title, listOf(a.status.label, a.color.ifBlank { null }, Money.formatEuros(a.priceCents)).filterNotNull().joinToString(" • "))
        },
        onDismiss = onDismiss,
        onPick = { i -> onPick(sorted[i]) },
    )
}
