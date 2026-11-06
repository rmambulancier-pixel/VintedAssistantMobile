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
        val article = Article(1, "Pull", costCents = 850, priceCents = 2500, status = ArticleStatus.DRAFT, createdAt = 0)
        assertEquals(1650L, article.marginCents)
        assertEquals(-100L, article.copy(priceCents = 750).marginCents)
    }

    @Test
    fun fullFormValidatesFloorPrice() {
        val ok = validateArticleForm(ArticleForm(title = "Pull", cost = "5", price = "20", floorPrice = "15", brand = " Zara "))
        assertTrue(ok is FormValidation.Valid)
        val fields = (ok as FormValidation.Valid).fields
        assertEquals(1500L, fields.floorPriceCents)
        assertEquals("Zara", fields.brand)

        val noFloor = validateArticleForm(ArticleForm(title = "Pull", cost = "5", price = "20")) as FormValidation.Valid
        assertEquals(null, noFloor.fields.floorPriceCents)

        val tooHigh = validateArticleForm(ArticleForm(title = "Pull", cost = "5", price = "20", floorPrice = "21"))
        assertTrue(tooHigh is FormValidation.Invalid)
    }

    @Test
    fun articleToFormRoundTrips() {
        val article = Article(1, "Pull", 850, 2500, ArticleStatus.ONLINE, 0, floorPriceCents = 2000, brand = "Zara")
        val back = validateArticleForm(article.toForm()) as FormValidation.Valid
        assertEquals(850L, back.fields.costCents)
        assertEquals(2500L, back.fields.priceCents)
        assertEquals(2000L, back.fields.floorPriceCents)
        assertEquals("Zara", back.fields.brand)
    }

    @Test
    fun statusFromUnknownLabelFallsBackToDraft() {
        assertEquals(ArticleStatus.ONLINE, ArticleStatus.fromLabel("En ligne"))
        assertEquals(ArticleStatus.DRAFT, ArticleStatus.fromLabel("???"))
        assertTrue(ArticleStatus.RESERVED.inStock)
        assertTrue(!ArticleStatus.SOLD.inStock)
    }
}
