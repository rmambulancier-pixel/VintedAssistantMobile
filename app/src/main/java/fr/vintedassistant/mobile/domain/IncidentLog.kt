package fr.vintedassistant.mobile.domain

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Journal de Vinted intégré : chaque remplissage (réussi ou non), chaque accroc de la republication en série,
 * chaque erreur de page et chaque signalement de l'utilisateur. Sert à deux choses :
 * 1. transmettre les incidents en un fichier, avec l'état exact de la page ;
 * 2. décider, chiffres à l'appui, quand Vinted intégré peut quitter la bêta.
 *
 * Rien de personnel : ni pseudo d'acheteur, ni message, ni adresse. Les pages de messagerie ne sont pas relevées.
 */
object IncidentLog {
    enum class Kind(val label: String) {
        FILL("Remplissage"),
        PUBLISH("Nouvelle annonce"),
        DELETE("Suppression de l'ancienne"),
        PAGE("Page Vinted"),
        REPORT("Signalé par toi"),
    }

    data class Entry(
        val at: Long,
        val kind: Kind,
        val ok: Boolean,
        val version: String,
        /** Chemin de la page, sans paramètres (« /items/new »). */
        val page: String = "",
        /** Ce qui s'est passé, en une phrase lisible. */
        val message: String = "",
        /** Détails techniques en JSON (résultats des scripts, diagnostic de la page). */
        val data: String = "",
        /** Texte libre de l'utilisateur pour un signalement. */
        val note: String = "",
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("at", at).put("kind", kind.name).put("ok", ok).put("version", version)
            .put("page", page).put("message", message).put("note", note)
            .put("data", data.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() ?: it } ?: JSONObject.NULL)

        companion object {
            fun fromJson(o: JSONObject): Entry? {
                val kind = runCatching { Kind.valueOf(o.optString("kind")) }.getOrNull() ?: return null
                val data = o.opt("data")
                return Entry(
                    at = o.optLong("at"), kind = kind, ok = o.optBoolean("ok"), version = o.optString("version"),
                    page = o.optString("page"), message = o.optString("message"), note = o.optString("note"),
                    data = when (data) { null, JSONObject.NULL -> ""; else -> data.toString() },
                )
            }
        }
    }

    /** Motifs proposés pour un signalement : un toucher, puis un mot si besoin. */
    val REASONS = listOf(
        "Un champ est mal rempli",
        "La catégorie est fausse",
        "Photos manquantes ou dans le désordre",
        "Le prix n'est pas pris",
        "Un bouton ne répond pas",
        "La page reste bloquée",
        "La série s'est arrêtée",
        "Autre",
    )

    /** Seuils de sortie de bêta : 20 remplissages d'affilée sans faute, puis 7 jours sans aucun incident. */
    const val STREAK_GOAL = 20
    const val QUIET_DAYS = 7
    private const val DAY_MS = 24L * 3600 * 1000

    data class Readiness(
        val fills: Int,
        val cleanFills: Int,
        /** Remplissages sans faute depuis le dernier incident de remplissage ou signalement. */
        val streak: Int,
        val incidents7d: Int,
        val reports7d: Int,
        val lastIncidentAt: Long?,
        /** Champ → (réussites, essais), sur tous les remplissages. */
        val byField: Map<String, Pair<Int, Int>>,
        val byKind: Map<Kind, Int>,
    ) {
        val ready: Boolean get() = streak >= STREAK_GOAL && incidents7d == 0
        val cleanRate: Int? get() = if (fills == 0) null else cleanFills * 100 / fills

        fun verdict(): String = when {
            fills == 0 -> "Aucun remplissage enregistré pour l'instant."
            ready -> "Prêt pour la sortie de bêta : $streak remplissages d'affilée sans faute et aucun incident depuis $QUIET_DAYS jours."
            streak < STREAK_GOAL -> "Encore bêta : ${STREAK_GOAL - streak} remplissage(s) sans faute à enchaîner (série actuelle : $streak/$STREAK_GOAL)."
            else -> "Encore bêta : la série est atteinte, mais $incidents7d incident(s) ces $QUIET_DAYS derniers jours."
        }
    }

    fun readiness(entries: List<Entry>, now: Long): Readiness {
        val sorted = entries.sortedBy { it.at }
        val fills = sorted.filter { it.kind == Kind.FILL }
        var streak = 0
        for (e in sorted) {
            when {
                e.kind == Kind.FILL && e.ok -> streak++
                e.kind == Kind.FILL || e.kind == Kind.REPORT -> streak = 0
            }
        }
        val recent = sorted.filter { now - it.at < QUIET_DAYS * DAY_MS && !it.ok }
        val byField = LinkedHashMap<String, Pair<Int, Int>>()
        fills.forEach { f -> fieldsOf(f).forEach { (name, ok) ->
            val (s, t) = byField[name] ?: (0 to 0)
            byField[name] = (s + if (ok) 1 else 0) to (t + 1)
        } }
        return Readiness(
            fills = fills.size,
            cleanFills = fills.count { it.ok },
            streak = streak,
            incidents7d = recent.size,
            reports7d = recent.count { it.kind == Kind.REPORT },
            lastIncidentAt = sorted.lastOrNull { !it.ok }?.at,
            byField = byField,
            byKind = sorted.filter { !it.ok }.groupingBy { it.kind }.eachCount(),
        )
    }

    /**
     * Champs d'un remplissage et leur réussite, lus dans ses données : « Photos », « Titre », « Catégorie »,
     * les détails (Marque, État, Couleur…) et « Prix ». Un champ absent du formulaire n'est pas compté.
     */
    fun fieldsOf(e: Entry): List<Pair<String, Boolean>> {
        val o = runCatching { JSONObject(e.data) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Pair<String, Boolean>>()
        if (o.has("photosExpected") && o.optInt("photosExpected") > 0) out += "Photos" to o.optBoolean("photosOk")
        o.optJSONObject("text")?.let { t ->
            out += "Titre" to t.optBoolean("title")
            out += "Description" to t.optBoolean("description")
        }
        if (o.has("category")) out += "Catégorie" to (o.optJSONObject("category")?.optBoolean("ok") == true)
        o.optJSONObject("details")?.optJSONArray("fields")?.let { arr ->
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i) ?: continue
                val st = f.optString("status")
                if (st == "absent") continue
                out += f.optString("field") to (st == "rempli" || st == "déjà rempli")
            }
        }
        if (o.has("priceOk")) out += "Prix" to o.optBoolean("priceOk")
        return out
    }

    /** Lignes d'une page de messagerie : jamais relevées (pseudos, contenu des échanges). */
    fun privatePage(path: String): Boolean = Regex("inbox|conversation|messages?", RegexOption.IGNORE_CASE).containsMatchIn(path)

    /** Chemin d'une adresse, sans domaine ni paramètres. */
    fun pathOf(url: String): String = url.substringBefore('?').substringBefore('#')
        .replace(Regex("^https?://[^/]+"), "").ifBlank { "/" }

    private val STAMP = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.FRANCE)
    fun stamp(at: Long): String = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(STAMP)

    /** Résumé lisible joint au journal : verdict, réussite par champ, incidents récents. */
    fun summary(entries: List<Entry>, now: Long, version: String): String {
        val r = readiness(entries, now)
        return buildString {
            appendLine("Vinted intégré, journal des incidents ($version), ${stamp(now)}")
            appendLine("Aucun pseudo, aucun message, aucune adresse dans ce fichier.")
            appendLine()
            appendLine(r.verdict())
            appendLine("Remplissages : ${r.fills}, sans faute : ${r.cleanFills}" + (r.cleanRate?.let { " ($it %)" } ?: ""))
            appendLine("Incidents ces $QUIET_DAYS jours : ${r.incidents7d}, dont ${r.reports7d} signalé(s) par toi")
            if (r.byField.isNotEmpty()) {
                appendLine()
                appendLine("RÉUSSITE PAR CHAMP")
                r.byField.entries.sortedBy { (_, v) -> v.first.toDouble() / v.second }.forEach { (name, v) ->
                    appendLine("- $name : ${v.first}/${v.second} (${v.first * 100 / v.second} %)")
                }
            }
            if (r.byKind.isNotEmpty()) {
                appendLine()
                appendLine("INCIDENTS PAR TYPE")
                r.byKind.forEach { (k, n) -> appendLine("- ${k.label} : $n") }
            }
            val last = entries.filter { !it.ok }.sortedByDescending { it.at }.take(30)
            if (last.isNotEmpty()) {
                appendLine()
                appendLine("DERNIERS INCIDENTS (détail complet dans incidents.json)")
                last.forEach { e ->
                    appendLine("- ${stamp(e.at)} • ${e.kind.label} • ${e.version} • ${e.page}")
                    if (e.message.isNotBlank()) appendLine("  ${e.message}")
                    if (e.note.isNotBlank()) appendLine("  Note : ${e.note}")
                }
            }
        }
    }
}
