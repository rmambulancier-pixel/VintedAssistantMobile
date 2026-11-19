package fr.vintedassistant.mobile.domain

import fr.vintedassistant.mobile.domain.IncidentLog.Entry
import fr.vintedassistant.mobile.domain.IncidentLog.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncidentLogTest {
    private val day = 24L * 3600 * 1000
    private val now = 100 * day

    private fun fill(at: Long, ok: Boolean, data: String = "") = Entry(at, Kind.FILL, ok, "1.6.0", "/items/new", data = data)

    @Test fun `vingt remplissages sans faute et sept jours calmes donnent le feu vert`() {
        val entries = (1..20).map { fill(now - 8 * day + it, ok = true) }
        val r = IncidentLog.readiness(entries, now)
        assertEquals(20, r.streak)
        assertEquals(0, r.incidents7d)
        assertTrue(r.ready)
    }

    @Test fun `un echec ou un signalement remet la serie a zero`() {
        val entries = (1..15).map { fill(now - 10 * day + it, ok = true) } +
            fill(now - 9 * day, ok = false) +
            (1..5).map { fill(now - 9 * day + it, ok = true) } +
            Entry(now - 9 * day + 10, Kind.REPORT, false, "1.6.0", note = "État faux") +
            (1..3).map { fill(now - 9 * day + 20 + it, ok = true) }
        val r = IncidentLog.readiness(entries, now)
        assertEquals(3, r.streak)
        assertFalse(r.ready)
        assertEquals(24, r.fills)
        assertEquals(23, r.cleanFills)
    }

    @Test fun `incident recent bloque meme avec une longue serie`() {
        val entries = (1..25).map { fill(now - 2 * day + it, ok = true) } +
            Entry(now - day, Kind.DELETE, false, "1.6.0", message = "Supprimer ne répond pas")
        val r = IncidentLog.readiness(entries, now)
        assertEquals(25, r.streak)
        assertEquals(1, r.incidents7d)
        assertFalse(r.ready)
    }

    @Test fun `reussite par champ lue dans les donnees`() {
        val data = """{"photosExpected":2,"photosOk":true,"text":{"title":true,"description":true},
            "category":{"ok":true},"details":{"fields":[{"field":"Pour qui","status":"absent"},
            {"field":"État","status":"différent"},{"field":"Couleur","status":"rempli"}]},"priceOk":false}"""
        val f = IncidentLog.fieldsOf(fill(now, ok = false, data = data)).toMap()
        assertEquals(true, f["Photos"])
        assertEquals(true, f["Catégorie"])
        assertEquals(false, f["État"])
        assertEquals(true, f["Couleur"])
        assertEquals(false, f["Prix"])
        assertFalse(f.containsKey("Pour qui"))
    }

    @Test fun `aller-retour JSON`() {
        val e = Entry(now, Kind.REPORT, false, "1.6.0", "/items/new", "Un bouton ne répond pas", """{"a":1}""", "Supprimer muet")
        val back = Entry.fromJson(e.toJson())!!
        assertEquals(e.copy(data = back.data), back)
        assertEquals(1, org.json.JSONObject(back.data).getInt("a"))
    }

    @Test fun `pages privees et chemins`() {
        assertTrue(IncidentLog.privatePage("/inbox/123"))
        assertFalse(IncidentLog.privatePage("/items/new"))
        assertEquals("/items/123-collier", IncidentLog.pathOf("https://www.vinted.fr/items/123-collier?ref=x"))
    }
}
