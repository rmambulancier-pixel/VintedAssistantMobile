package fr.vintedassistant.mobile.data

import fr.vintedassistant.mobile.data.files.ImageDownloader
import fr.vintedassistant.mobile.data.files.PhotoStore
import fr.vintedassistant.mobile.data.local.VintedBoostDatabase
import fr.vintedassistant.mobile.data.local.WishEntity
import kotlinx.coroutines.flow.Flow

/** Pépites gardées pour un achat plus tard. La photo est téléchargée tout de suite (le lien d'image peut expirer). */
class WishRepository(db: VintedBoostDatabase, private val photoStore: PhotoStore?, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao = db.wishDao()
    val items: Flow<List<WishEntity>> = dao.observeAll()

    /** Garde un bijou ; renvoie faux s'il était déjà dans la liste. */
    suspend fun keep(item: WishEntity): Boolean {
        if (dao.byUrl(item.url) != null) return false
        val image = item.imageUrl.takeIf { it.startsWith("https://") }?.let { url ->
            runCatching { photoStore?.importBytes(ImageDownloader.download(url, anyHttps = true)) }.getOrNull()
        }
        return dao.insert(item.copy(id = 0, imageFile = image, savedAt = clock())) > 0
    }

    suspend fun setBought(id: Long, bought: Boolean) {
        val w = dao.getById(id) ?: return
        dao.update(w.copy(boughtAt = if (bought) clock() else null))
    }

    suspend fun remove(id: Long) {
        val w = dao.getById(id) ?: return
        dao.delete(id)
        w.imageFile?.let { photoStore?.delete(it) }
    }
}
