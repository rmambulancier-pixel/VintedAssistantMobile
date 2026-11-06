package fr.vintedassistant.mobile.data.legacy

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vérifie la reprise du format exact écrit par la version 0.1. */
class LegacyInventoryParserTest {

    @Test
    fun parsesItemsWrittenByVersion01() {
        val raw = """[
            {"id":1760777000000,"title":"Veste en jean","cost":8.5,"price":25.0,"status":"Brouillon"},
            {"id":1760777100000,"title":"Robe","cost":19.99,"price":35.1,"status":"Brouillon"}
        ]"""
        val result = LegacyInventoryParser.parse(raw)
        assertEquals(0, result.skipped)
        assertEquals(
            listOf(
                LegacyItem(1760777000000, "Veste en jean", 850, 2500, "Brouillon"),
                LegacyItem(1760777100000, "Robe", 1999, 3510, "Brouillon"),
            ),
            result.items,
        )
    }

    @Test
    fun skipsIncompleteEntriesAndCountsThem() {
        val raw = """[
            {"id":1,"title":"Ok","cost":1,"price":2},
            {"id":2,"title":"   ","cost":1,"price":2},
            {"id":3,"title":"Sans prix","cost":1},
            {"id":4,"title":"Négatif","cost":-1,"price":2},
            {"title":"Sans id","cost":1,"price":2},
            "pas un objet"
        ]"""
        val result = LegacyInventoryParser.parse(raw)
        assertEquals(1, result.items.size)
        assertEquals(5, result.skipped)
    }

    @Test
    fun missingStatusDefaultsToDraft() {
        val result = LegacyInventoryParser.parse("""[{"id":1,"title":"Pull","cost":1,"price":2}]""")
        assertEquals("Brouillon", result.items.single().status)
    }

    @Test
    fun emptyArrayGivesNothing() {
        val result = LegacyInventoryParser.parse("[]")
        assertTrue(result.items.isEmpty())
        assertEquals(0, result.skipped)
    }

    @Test(expected = JSONException::class)
    fun corruptJsonThrows() {
        LegacyInventoryParser.parse("{pas du json")
    }

    @Test
    fun toCentsAvoidsFloatingPointErrors() {
        assertEquals(1999L, LegacyInventoryParser.toCents(19.99))
        assertEquals(30L, LegacyInventoryParser.toCents(0.1 + 0.2))
        assertEquals(1L, LegacyInventoryParser.toCents(0.005))
        assertEquals(0L, LegacyInventoryParser.toCents(0.0))
    }
}
