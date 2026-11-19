package fr.vintedassistant.mobile.domain

/**
 * Rapprochement entre les annonces lues dans le dressing et les fiches de l'application, pour que l'import
 * ne crée jamais de doublon.
 *
 * Cas rencontrés le 19 novembre :
 * - annonce republiée : la fiche pointe encore vers l'ancienne annonce (supprimée), la nouvelle n'était reconnue
 *   que si la fiche n'avait aucun lien → nouvelle fiche, sans achat ;
 * - annonce vendue lue comme en ligne → fiche « En ligne » sans achat, alors que la vente existe déjà.
 *
 * Règle : une annonce va d'abord à la fiche qui porte son lien, sinon à une fiche du même titre dont le lien est
 * vide ou ne figure plus dans le dressing (annonce supprimée ou republiée). Jamais à une fiche liée à une
 * autre annonce encore visible : deux pièces identiques en ligne restent deux fiches.
 */
object ImportReconcile {
    data class Fiche(
        val id: Long,
        val title: String,
        val itemId: Long?,
        val status: ArticleStatus,
        val hasPurchase: Boolean,
        val hasSale: Boolean,
        val costCents: Long,
        val createdAt: Long,
    ) {
        /** Fiche créée par un import (ni achat, ni vente, ni coût) : la seule qu'on s'autorise à fusionner ou retirer. */
        val importedOnly: Boolean get() = !hasPurchase && !hasSale && costCents == 0L && status.inStock
        val key: String get() = StockLedger.normalize(title)
    }

    data class Plan(
        /** (fiche gardée, fiche retirée) : le lien et les chiffres de la seconde passent sur la première. */
        val merges: List<Pair<Long, Long>> = emptyList(),
        /** Fiches en double d'une fiche avec achat, dont l'annonce n'existe plus. */
        val drops: List<Long> = emptyList(),
        /** Fiches importées « en ligne » alors que l'annonce est vendue, sans fiche vendue correspondante. */
        val markSold: List<Long> = emptyList(),
    ) {
        val isEmpty: Boolean get() = merges.isEmpty() && drops.isEmpty() && markSold.isEmpty()
    }

    /** Meilleure fiche parmi des candidates : avec achat, puis en ligne plutôt que brouillon, puis la plus ancienne. */
    private fun best(candidates: List<Fiche>): Fiche? = candidates.sortedWith(
        compareByDescending<Fiche> { it.hasPurchase }
            .thenByDescending { it.status == ArticleStatus.ONLINE || it.status == ArticleStatus.RESERVED }
            .thenBy { it.createdAt }
            .thenBy { it.id },
    ).firstOrNull()

    /**
     * Fusions à faire avant l'import. [active] et [sold] : identifiants lus dans les onglets « Actifs » et
     * « Vendus ». [reliable] : les deux onglets ont bien été lus (sinon, on ne retire rien).
     */
    fun plan(fiches: List<Fiche>, active: Set<Long>, sold: Set<Long>, reliable: Boolean): Plan {
        val scan = active + sold
        val used = HashSet<Long>()
        val merges = mutableListOf<Pair<Long, Long>>()
        val drops = mutableListOf<Long>()
        val markSold = mutableListOf<Long>()
        val byKey = fiches.groupBy { it.key }
        val ordered = fiches.filter { it.importedOnly && it.itemId != null }.sortedByDescending { it.createdAt }
        for (d in ordered) {
            if (d.id in used) continue
            val same = byKey[d.key].orEmpty().filter { it.id != d.id && it.id !in used }
            val dead = { f: Fiche -> f.itemId == null || f.itemId !in scan }
            val itemId = d.itemId!!
            when {
                itemId in sold -> {
                    val k = best(same.filter { it.status == ArticleStatus.SOLD && dead(it) })
                    if (k != null) { merges += k.id to d.id; used += k.id; used += d.id } else { markSold += d.id; used += d.id }
                }
                itemId in active -> {
                    val k = best(same.filter { it.status.inStock && dead(it) && !it.importedOnly })
                    if (k != null) { merges += k.id to d.id; used += k.id; used += d.id }
                }
                reliable -> {
                    // L'annonce n'existe plus nulle part et une vraie fiche du même bijou existe : doublon à retirer.
                    if (same.any { it.hasPurchase || it.hasSale }) { drops += d.id; used += d.id }
                }
            }
        }
        return Plan(merges, drops, markSold)
    }

    /**
     * Fiche à mettre à jour pour une annonce lue. [scan] : identifiants vus dans le dressing (null pour l'import
     * d'une seule annonce : on ne sait pas alors quelles annonces existent encore, seul un lien vide compte).
     * [taken] : fiches déjà attribuées pendant cet import.
     */
    fun match(itemId: Long, title: String, sold: Boolean, fiches: List<Fiche>, scan: Set<Long>?, taken: Set<Long>): Long? {
        fiches.firstOrNull { it.itemId == itemId }?.let { return it.id }
        val key = StockLedger.normalize(title)
        val free = { f: Fiche -> f.itemId == null || (scan != null && f.itemId !in scan) }
        return best(
            fiches.filter {
                it.id !in taken && it.key == key && free(it) &&
                    (if (sold) it.status == ArticleStatus.SOLD else it.status.inStock)
            },
        )?.id
    }
}
