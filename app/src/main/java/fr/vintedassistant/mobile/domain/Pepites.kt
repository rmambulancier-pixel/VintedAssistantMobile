package fr.vintedassistant.mobile.domain

/**
 * Chasseur de pépites : estime ce qu'un bijou vu chez le fournisseur pourrait rapporter, d'après les ventes
 * réelles de l'utilisateur. Repère d'abord un bijou déjà acheté au nom proche (mêmes mots du fournisseur),
 * sinon le prix médian de vente du même type de bijou. Sans repère, aucune estimation n'est inventée.
 */
object Pepites {
    data class Reference(val name: String, val category: String, val averagePriceCents: Long, val sold: Int, val averageDays: Double?)

    enum class Basis { MODEL, TYPE, NONE }

    data class Estimate(
        val costCents: Long,
        val saleCents: Long?,
        val basis: Basis,
        val soldSimilar: Int,
        val averageDays: Double?,
    ) {
        val multiple: Double? get() = saleCents?.takeIf { costCents > 0 }?.let { it.toDouble() / costCents }
        val marginCents: Long? get() = saleCents?.let { it - costCents }
    }

    data class Knowledge(val references: List<Reference>, val typeMedians: Map<String, Pair<Long, Int>>, val shippingPerPieceCents: Long)

    fun learn(stock: StockOverview, sales: List<Sale>, orders: List<PurchaseOrder>): Knowledge {
        val byArticle = sales.filter { it.counted && it.articleId != null && it.salePriceCents > 0 }.groupBy { it.articleId!! }
        val refs = stock.products.mapNotNull { p ->
            val own = p.articles.flatMap { byArticle[it.id].orEmpty() }
            if (own.isEmpty()) return@mapNotNull null
            val delays = own.filter { it.dateKnown }.mapNotNull { s -> s.listedAt?.let { (s.soldAt - it).coerceAtLeast(0).toDouble() / DAY_MS } }
            Reference(p.name, JewelryCategory.guess(p.name) ?: "", own.sumOf { it.salePriceCents } / own.size, own.size, delays.takeIf { it.isNotEmpty() }?.average())
        }
        val medians = sales.filter { it.counted && it.salePriceCents > 0 }
            .groupBy { JewelryCategory.of(it.category, it.title) }
            .filterKeys { it != "Sans catégorie" }
            .mapValues { (_, list) -> median(list.map { it.salePriceCents }) to list.size }
        val received = orders.filter { it.received }
        val pieces = received.sumOf { o -> o.items.sumOf { it.delivered } }
        val shipping = if (pieces > 0) received.sumOf { it.shippingCents + it.feesCents } / pieces else 0L
        return Knowledge(refs, medians, shipping)
    }

    fun estimate(name: String, priceCents: Long, k: Knowledge): Estimate {
        val cost = priceCents + k.shippingPerPieceCents
        val category = JewelryCategory.guess(name)
        val close = k.references
            .filter { category == null || it.category.isEmpty() || it.category == category }
            .map { it to StockLedger.similarity(it.name, name) }
            .filter { it.second >= 0.5 }
        if (close.isNotEmpty()) {
            val sold = close.sumOf { it.first.sold }
            val price = close.sumOf { it.first.averagePriceCents * it.first.sold } / sold
            val days = close.mapNotNull { it.first.averageDays }.takeIf { it.isNotEmpty() }?.average()
            return Estimate(cost, price, Basis.MODEL, sold, days)
        }
        val type = category?.let { k.typeMedians[it] }
        if (type != null && type.second >= 3) return Estimate(cost, type.first, Basis.TYPE, type.second, null)
        return Estimate(cost, null, Basis.NONE, 0, null)
    }

    /** Texte court de la pastille posée sur le bijou. */
    fun badge(e: Estimate): String {
        val m = e.multiple ?: return "?"
        val x = if (m >= 10) "×" + m.toInt() else "×" + String.format(java.util.Locale.FRANCE, "%.1f", m)
        return x + if (e.basis == Basis.TYPE) " ~" else ""
    }

    /** Mots à chercher chez le fournisseur : les types et motifs de ce qui s'est le mieux vendu. */
    fun keywords(k: Knowledge, max: Int = 6): List<String> {
        val words = HashMap<String, Long>()
        k.references.forEach { r ->
            StockLedger.normalize(r.name).split(' ').filter { it.length >= 5 && it !in NOISE }.distinct().take(6)
                .forEach { w -> words[w] = (words[w] ?: 0) + r.averagePriceCents * r.sold }
        }
        return words.entries.sortedByDescending { it.value }.take(max).map { it.key }
    }

    private val NOISE = setOf(
        "femme", "homme", "unisexe", "acier", "inoxydable", "plaque", "carats", "alliage", "metal", "cuivre", "style", "avec",
        "motif", "incrustation", "incruste", "epaisseur", "piece", "couleur", "taille", "unique", "modele", "veritable", "revetement",
        "impression", "design", "elegant", "elegante", "brillante", "luxueux",
    )

    private fun median(values: List<Long>): Long {
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }
}

/** Point du matin : ce qui attend l'utilisateur aujourd'hui, une ligne par sujet. Vide s'il n'y a rien à faire. */
object MorningBrief {
    data class Input(
        val urgentParcels: Int, val lateParcels: Int, val toShip: Int,
        val toRepublish: Int, val priceDrops: Int, val notListed: Int,
        val restock: Int, val favorites: Int, val arrivingOrders: Int,
    )

    /** Vrai si la date d'arrivée annoncée par le fournisseur (« Tues, Dec 01 ») est atteinte ou dépassée. */
    fun etaReached(eta: String, today: java.time.LocalDate): Boolean =
        StockBoard.etaDate(eta, today)?.let { !it.isAfter(today) } ?: false

    fun lines(i: Input): List<String> = buildList {
        if (i.lateParcels > 0) add("${i.lateParcels} colis en retard sur le délai Vinted : à déposer en priorité.")
        else if (i.urgentParcels > 0) add("${i.urgentParcels} colis à déposer aujourd'hui au plus tard.")
        else if (i.toShip > 0) add("${i.toShip} colis à expédier.")
        if (i.arrivingOrders > 0) add("Réassort attendu aujourd'hui : à l'arrivée du colis, marque la commande reçue, puis mise en vente express.")
        if (i.notListed > 0) add("${i.notListed} pièce(s) en stock jamais mise(s) en vente.")
        if (i.toRepublish > 0) add("${i.toRepublish} annonce(s) à republier.")
        if (i.priceDrops > 0) add("${i.priceDrops} baisse(s) de prix prête(s) à valider.")
        if (i.favorites > 0) add("${i.favorites} favori(s) récent(s) à relancer avec une offre.")
        if (i.restock > 0) add("${i.restock} bijou(x) rentable(s) à racheter.")
    }
}

/** Remarque ajoutée à une vente enregistrée d'office : dernière pièce d'un bon modèle, ou vente très rapide. */
object SaleInsight {
    fun of(articleId: Long, stock: StockOverview, sales: List<Sale>): String? {
        val p = stock.products.firstOrNull { pr -> pr.articles.any { it.id == articleId } } ?: return null
        val ids = p.articles.map { it.id }.toSet()
        val own = sales.filter { it.counted && it.articleId in ids }
        val remaining = p.notListed + p.inTransit + p.drafts + p.forSale
        val last = own.firstOrNull { it.articleId == articleId }
        val days = last?.listedAt?.let { (last.soldAt - it).coerceAtLeast(0).toDouble() / DAY_MS }
        return when {
            remaining == 0 && own.size >= 2 -> "C'était la dernière pièce (${own.size} vendues) : à racheter."
            days != null && days <= 2.0 && remaining > 0 -> "Partie en moins de 2 jours : tu peux monter le prix des ${remaining} restante(s)."
            else -> null
        }
    }
}

/** Bilan de la semaine écoulée (7 derniers jours) et export du mois pour les déclarations. */
object Reports {
    data class Week(val sales: Int, val revenueCents: Long, val netCents: Long, val best: String?, val previousRevenueCents: Long)

    fun week(sales: List<Sale>, rateBp: Int, now: Long): Week {
        val dated = sales.filter { it.counted && it.dateKnown }
        val cur = dated.filter { it.soldAt > now - 7 * DAY_MS && it.soldAt <= now }
        val prev = dated.filter { it.soldAt > now - 14 * DAY_MS && it.soldAt <= now - 7 * DAY_MS }
        val best = cur.groupBy { it.title }.maxByOrNull { (_, l) -> l.sumOf { Finance.netCents(it, rateBp) } }?.key
        return Week(cur.size, cur.sumOf { it.salePriceCents }, cur.sumOf { Finance.netCents(it, rateBp) }, best, prev.sumOf { it.salePriceCents })
    }

    fun weekText(w: Week): String = buildString {
        if (w.sales == 0) { append("Aucune vente cette semaine."); return@buildString }
        append("${w.sales} vente(s), ${Money.formatEuros(w.revenueCents)} de chiffre d'affaires, ${Money.formatEuros(w.netCents)} de bénéfice net.")
        if (w.previousRevenueCents > 0) {
            val diff = (w.revenueCents - w.previousRevenueCents) * 100 / w.previousRevenueCents
            append(if (diff >= 0) " +$diff % par rapport à la semaine d'avant." else " $diff % par rapport à la semaine d'avant.")
        }
        w.best?.let { append(" Meilleur bijou : ${it.take(50)}.") }
    }

    /** CSV (séparateur point-virgule, lisible par Excel en français) des ventes du mois de [now]. */
    fun monthCsv(sales: List<Sale>, rateBp: Int, now: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val start = today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.withDayOfMonth(1).plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val rows = sales.filter { it.counted && it.dateKnown && it.soldAt in start until end }.sortedBy { it.soldAt }
        fun n(c: Long) = (if (c < 0) "-" else "") + (Math.abs(c) / 100) + "," + (Math.abs(c) % 100).toString().padStart(2, '0')
        return buildString {
            appendLine("Date;Article;Prix de vente;Coût d'achat;Frais;Cotisations;Bénéfice net")
            rows.forEach { s ->
                val d = java.time.Instant.ofEpochMilli(s.soldAt).atZone(zone).toLocalDate()
                appendLine(listOf(d.toString(), "\"" + s.title.replace("\"", "'") + "\"", n(s.salePriceCents), n(s.costCents), n(s.feesCents),
                    n(Finance.contributionsCents(s.salePriceCents, rateBp)), n(Finance.netCents(s, rateBp))).joinToString(";"))
            }
            appendLine("Total;;${n(rows.sumOf { it.salePriceCents })};${n(rows.sumOf { it.costCents })};${n(rows.sumOf { it.feesCents })};" +
                "${n(rows.sumOf { Finance.contributionsCents(it.salePriceCents, rateBp) })};${n(rows.sumOf { Finance.netCents(it, rateBp) })}")
        }
    }
}
