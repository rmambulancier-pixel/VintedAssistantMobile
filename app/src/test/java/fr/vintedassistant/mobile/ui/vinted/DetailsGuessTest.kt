package fr.vintedassistant.mobile.ui.vinted

import org.junit.Assert.assertEquals
import org.junit.Test

class DetailsGuessTest {
    private fun colors(title: String, color: String = "", description: String = "") =
        DetailsGuess.colors(fr.vintedassistant.mobile.domain.Article(id = 1, title = title, costCents = 0, priceCents = 600, status = fr.vintedassistant.mobile.domain.ArticleStatus.entries.first(), createdAt = 0, color = color, description = description))

    @Test fun `ouvert ne donne pas vert`() {
        assertEquals(listOf("Doré"), colors("Bracelet Jonc Ouvert Maille Grain de Café Acier Inoxydable 304 Doré"))
    }

    @Test fun `la couleur de la fiche decide`() {
        assertEquals(listOf("Doré"), colors("Bracelet jonc ouvert vert émeraude", color = "doré"))
    }

    @Test fun `deux couleurs au plus, en mots entiers`() {
        assertEquals(listOf("Doré", "Vert"), colors("Collier trèfle doré vert émeraude noir"))
        assertEquals(listOf("Doré"), colors("Collier plaqué or décor trésor"))
        assertEquals(emptyList<String>(), colors("Bracelet jonc ouvert"))
    }
}
