package fr.vintedassistant.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleValidationTest {

    @Test
    fun validInputIsTrimmedAndConverted() {
        val result = validateNewArticle("  Veste en jean  ", "8,50", "25")
        assertTrue(result is ArticleValidation.Valid)
        assertEquals(NewArticle("Veste en jean", 850, 2500), (result as ArticleValidation.Valid).article)
    }

    @Test
    fun blankTitleIsRejected() {
        assertTrue(validateNewArticle("   ", "1", "2") is ArticleValidation.Invalid)
    }

    @Test
    fun tooLongTitleIsRejected() {
        val title = "a".repeat(Article.TITLE_MAX_LENGTH + 1)
        assertTrue(validateNewArticle(title, "1", "2") is ArticleValidation.Invalid)
        assertTrue(validateNewArticle("a".repeat(Article.TITLE_MAX_LENGTH), "1", "2") is ArticleValidation.Valid)
    }

    @Test
    fun invalidAmountsAreRejectedWithSpecificMessage() {
        val badCost = validateNewArticle("Pull", "abc", "10") as ArticleValidation.Invalid
        assertTrue(badCost.message.startsWith("Coût"))
        val badPrice = validateNewArticle("Pull", "5", "-10") as ArticleValidation.Invalid
        assertTrue(badPrice.message.startsWith("Prix"))
    }

    @Test
    fun marginIsPriceMinusCost() {
        val article = Article(1, "Pull", costCents = 850, priceCents = 2500, status = Article.STATUS_DRAFT, createdAt = 0)
        assertEquals(1650L, article.marginCents)
        assertEquals(-100L, article.copy(priceCents = 750).marginCents)
    }
}
