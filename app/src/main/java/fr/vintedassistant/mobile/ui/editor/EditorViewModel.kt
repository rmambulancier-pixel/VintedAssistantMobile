package fr.vintedassistant.mobile.ui.editor

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.vintedassistant.mobile.VintedBoostApp
import fr.vintedassistant.mobile.ai.ListingAssistant
import fr.vintedassistant.mobile.data.ArticleRepository
import fr.vintedassistant.mobile.data.SettingsStore
import fr.vintedassistant.mobile.data.files.PhotoStore
import fr.vintedassistant.mobile.domain.Article
import fr.vintedassistant.mobile.domain.ArticleForm
import fr.vintedassistant.mobile.domain.ArticleStatus
import fr.vintedassistant.mobile.domain.FormValidation
import fr.vintedassistant.mobile.domain.Money
import fr.vintedassistant.mobile.domain.SaleValidation
import fr.vintedassistant.mobile.domain.toForm
import fr.vintedassistant.mobile.domain.validateArticleForm
import fr.vintedassistant.mobile.domain.validateSale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class EditorViewModel(
    initialId: Long,
    private val repository: ArticleRepository,
    private val assistant: ListingAssistant,
    private val photoStore: PhotoStore,
    private val settingsStore: SettingsStore,
    private val photoEnhancer: fr.vintedassistant.mobile.data.files.PhotoEnhancer,
) : ViewModel() {

    private val articleId = MutableStateFlow(initialId)
    val id: StateFlow<Long> = articleId.asStateFlow()

    val article: StateFlow<Article?> =
        combine(articleId, repository.articles) { id, list -> list.firstOrNull { it.id == id } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _form = MutableStateFlow(ArticleForm())
    val form: StateFlow<ArticleForm> = _form.asStateFlow()

    /** Dernière version enregistrée du formulaire, pour savoir s'il reste des modifications. */
    private val _saved = MutableStateFlow(ArticleForm())
    val saved: StateFlow<ArticleForm> = _saved.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _info = MutableStateFlow<String?>(null)
    val info: StateFlow<String?> = _info.asStateFlow()

    init {
        if (initialId > 0) {
            viewModelScope.launch {
                repository.getArticle(initialId)?.let {
                    _form.value = it.toForm()
                    _saved.value = it.toForm()
                }
            }
        }
    }

    fun edit(transform: (ArticleForm) -> ArticleForm) {
        _form.value = transform(_form.value)
        _error.value = null
    }

    fun dismissInfo() {
        _info.value = null
    }

    /** Enregistre la fiche. Renvoie false si la saisie est invalide. */
    fun save(): Boolean {
        val fields = when (val v = validateArticleForm(_form.value)) {
            is FormValidation.Invalid -> {
                _error.value = v.message
                return false
            }
            is FormValidation.Valid -> v.fields
        }
        val snapshot = _form.value
        work("Enregistrement impossible. Réessaie.") {
            if (articleId.value > 0) {
                repository.update(articleId.value, fields)
            } else {
                articleId.value = repository.create(fields)
            }
            _saved.value = snapshot
            _info.value = "Fiche enregistrée."
        }
        return true
    }

    fun addPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val id = articleId.value
        if (id <= 0) {
            _error.value = "Enregistre d'abord la fiche, puis ajoute les photos."
            return
        }
        work("Import des photos impossible.") {
            val failures = repository.addPhotos(id, uris)
            _info.value = when {
                failures == 0 -> "${uris.size} photo(s) ajoutée(s)."
                failures == uris.size -> "Aucune photo n'a pu être importée."
                else -> "${uris.size - failures} photo(s) ajoutée(s), $failures illisible(s)."
            }
        }
    }

    /** Ajoute une version améliorée de la photo (bijou détouré sur fond clair), en première position. */
    fun enhancePhoto(fileName: String) = work("Amélioration impossible. Vérifie la connexion la première fois (téléchargement du détourage), puis réessaie.") {
        val cut = repository.enhancePhoto(articleId.value, fileName, photoEnhancer)
        _info.value = if (cut) "Photo améliorée ajoutée en première position. L'originale est gardée : retire celle que tu ne veux pas."
            else "Bijou non détouré sur cette photo : seuls le cadrage et la lumière ont été améliorés. L'originale est gardée."
    }

    fun removePhoto(fileName: String) = work("Suppression de la photo impossible.") {
        repository.removePhoto(articleId.value, fileName)
    }

    /** Rédige titre, description et mots-clés, avec l'IA si demandé et disponible. Rien n'est enregistré avant « Enregistrer ». */
    fun draft(useAi: Boolean) {
        val current = _form.value
        if (current.title.isBlank()) {
            _error.value = "Indique au moins le nom de l'article (par exemple « Veste en jean »)."
            return
        }
        work("Rédaction impossible.") {
            val base = previewArticle(current)
            val photo = if (useAi) base.photos.firstOrNull()?.let { photoStore.thumbnail(it, 1024) } else null
            val result = assistant.draftListing(base, photo, aiEnabled = useAi && settingsStore.current().aiEnabled)
            _form.value = _form.value.copy(
                title = result.value.title.ifBlank { current.title },
                description = result.value.description,
                keywords = result.value.keywords,
            )
            _info.value = result.note ?: "Annonce rédigée : relis, ajuste, puis enregistre."
        }
    }

    fun setStatus(status: ArticleStatus) = withSaved { repository.setStatus(it, status) }

    fun markPublished() = withSaved {
        repository.markPublished(it)
        _info.value = "Annonce marquée comme publiée."
    }

    fun markRepublished() = withSaved {
        repository.markRepublished(it)
        _info.value = "Republication enregistrée."
    }

    /** Renvoie un message d'erreur, ou null si la vente est enregistrée. */
    fun recordSale(priceText: String, feesText: String, buyer: String): String? {
        val id = articleId.value
        if (id <= 0) return "Enregistre d'abord la fiche."
        return when (val v = validateSale(priceText, feesText, buyer)) {
            is SaleValidation.Invalid -> v.message
            is SaleValidation.Valid -> {
                work("Enregistrement de la vente impossible.") {
                    repository.recordSale(id, v)
                    _info.value = "Vente enregistrée. Retrouve-la dans l'onglet Ventes."
                }
                null
            }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val id = articleId.value
        if (id <= 0) {
            onDeleted()
            return
        }
        work("Suppression impossible.") {
            repository.delete(id)
            onDeleted()
        }
    }

    /** Texte complet à coller dans Vinted : description, puis mots-clés s'ils n'y sont pas déjà. */
    fun fullDescription(): String {
        val f = _form.value
        val tags = f.keywords.trim()
        return if (tags.isBlank() || f.description.contains(tags)) f.description.trim()
        else f.description.trim() + "\n\n" + tags
    }

    private fun previewArticle(f: ArticleForm): Article {
        val existing = article.value
        return Article(
            id = articleId.value,
            title = f.title.trim(),
            costCents = Money.parseEuros(f.cost) ?: 0,
            priceCents = Money.parseEuros(f.price) ?: 0,
            status = existing?.status ?: ArticleStatus.DRAFT,
            createdAt = existing?.createdAt ?: 0,
            floorPriceCents = Money.parseEuros(f.floorPrice),
            brand = f.brand.trim(),
            size = f.size.trim(),
            category = f.category.trim(),
            condition = f.condition.trim(),
            color = f.color.trim(),
            description = f.description,
            keywords = f.keywords,
            photos = existing?.photos.orEmpty(),
        )
    }

    private fun withSaved(block: suspend (Long) -> Unit) {
        val id = articleId.value
        if (id <= 0) {
            _error.value = "Enregistre d'abord la fiche."
            return
        }
        work("Action impossible. Réessaie.") { block(id) }
    }

    private fun work(errorMessage: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = errorMessage
            } finally {
                _busy.value = false
            }
        }
    }

    companion object {
        fun factory(articleId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as VintedBoostApp
                val c = app.container
                EditorViewModel(articleId, c.articleRepository, c.assistant, c.photoStore, c.settingsStore, c.photoEnhancer)
            }
        }
    }
}
