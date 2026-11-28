package fr.vintedassistant.mobile.data

import fr.vintedassistant.mobile.data.local.SupplyEntity
import fr.vintedassistant.mobile.data.local.VintedBoostDatabase
import fr.vintedassistant.mobile.domain.Supply
import fr.vintedassistant.mobile.domain.SupplyKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Fournitures achetées pour l'activité (pochettes, étiquettes, matériel photo…). */
class SupplyRepository(db: VintedBoostDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao = db.supplyDao()

    val items: Flow<List<Supply>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    /** Enregistre un achat (id 0) ou le met à jour. Renvoie son id. */
    suspend fun save(s: Supply): Long {
        val entity = SupplyEntity(
            id = s.id, boughtAt = s.boughtAt, label = s.label.trim(), kind = s.kind.name, supplier = s.supplier.trim(),
            quantity = s.quantity, amountCents = s.amountCents, perParcel = s.perParcel, note = s.note.trim(),
            createdAt = clock(),
        )
        if (s.id == 0L) return dao.insert(entity)
        val old = dao.getById(s.id) ?: return dao.insert(entity.copy(id = 0))
        dao.update(entity.copy(createdAt = old.createdAt))
        return s.id
    }

    suspend fun delete(id: Long) = dao.delete(id)

    private fun SupplyEntity.toDomain() = Supply(
        id, boughtAt, label, SupplyKind.of(kind), supplier, quantity, amountCents, perParcel, note,
    )
}
