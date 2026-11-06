package fr.vintedassistant.mobile.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VintedExportParserTest {

    private fun zip(vararg files: Pair<String, String>): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            files.forEach { (name, content) ->
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray())
                z.closeEntry()
            }
        }
        return ByteArrayInputStream(out.toByteArray())
    }

    @Test
    fun readsItemsFromJsonAndPrefersItemFiles() {
        val items = """{"items":[
            {"id":1,"title":"Veste en jean","price":{"amount":"25.0","currency_code":"EUR"},"brand_title":"Levi's","size_title":"M","description":"Très bon état"},
            {"id":2,"title":"Robe","price":"19.99"},
            {"id":3,"title":"Robe","price":"19.99"}
        ]}"""
        val transactions = """[{"title":"Achat chaussures","price":"30"}]"""
        val report = VintedExportParser.parse(zip("data/items.json" to items, "data/transactions.json" to transactions, "photo.jpg" to "x"))

        assertEquals(2, report.candidates.size) // doublon retiré, transactions ignorées
        val veste = report.candidates.first()
        assertEquals("Veste en jean", veste.title)
        assertEquals(2500L, veste.priceCents)
        assertEquals("Levi's", veste.brand)
        assertEquals("M", veste.size)
        assertEquals(1999L, report.candidates[1].priceCents)
        assertEquals(2, report.files.size)
        assertTrue(report.structureText().contains("items.json"))
        assertTrue(!report.structureText().contains("Veste")) // pas de données personnelles dans le rapport
    }

    @Test
    fun readsSemicolonCsvWithQuotes() {
        val csv = "﻿Titre;Prix;Marque\n\"Pull; laine\";\"12,50 €\";Zara\nT-shirt;5;\n"
        val report = VintedExportParser.parse(zip("annonces.csv" to csv))
        assertEquals(2, report.candidates.size)
        assertEquals("Pull; laine", report.candidates[0].title)
        assertEquals(1250L, report.candidates[0].priceCents)
        assertEquals("Zara", report.candidates[0].brand)
    }

    @Test
    fun fallsBackToAnyFileWhenNoItemFile() {
        val report = VintedExportParser.parse(zip("export/data.json" to """[{"name":"Sac","prix":"40"}]"""))
        assertEquals(1, report.candidates.size)
        assertEquals(4000L, report.candidates.single().priceCents)
    }

    @Test
    fun ignoresRecordsWithoutPriceAndBrokenFiles() {
        val report = VintedExportParser.parse(zip("items.json" to """[{"title":"Sans prix"}]""", "items2.json" to "{cassé"))
        assertTrue(report.candidates.isEmpty())
        assertEquals(2, report.files.size)
    }

    @Test
    fun priceParsing() {
        assertEquals(1200L, VintedExportParser.parsePrice("12.0"))
        assertEquals(700L, VintedExportParser.parsePrice("EUR 7"))
        assertEquals(1250L, VintedExportParser.parsePrice("12,50 €"))
        assertEquals(null, VintedExportParser.parsePrice("gratuit"))
    }
}
