package com.ctm.credit.agent

import com.ctm.credit.network.IdempotencyKeys
import com.ctm.credit.network.SyncAction
import com.ctm.credit.network.SyncItem
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/** Identifiants locaux strictement croissants : deux actions créées dans la même milliseconde n'entrent jamais en collision. */
internal object LocalIds {
    private val last = AtomicLong(0)

    fun next(): Long {
        while (true) {
            val prev = last.get()
            val candidate = maxOf(System.currentTimeMillis(), prev + 1)
            if (last.compareAndSet(prev, candidate)) return candidate
        }
    }
}

/** Crée une action à mettre en file, avec une nouvelle clé d'idempotence. */
internal fun queuedItem(action: SyncAction, payloadJson: String, entityKey: String?): SyncItem {
    val now = Instant.now()
    return SyncItem(
        localId = LocalIds.next(),
        idempotencyKey = IdempotencyKeys.new(),
        action = action,
        payloadJson = payloadJson,
        entityKey = entityKey,
        clientCreatedAt = now,
        clientUpdatedAt = now,
    )
}

/**
 * Lit un montant saisi (virgule ou point). Renvoie null si le texte n'est pas un nombre,
 * s'il est négatif ou s'il a plus de 2 décimales (le calculateur et le serveur refusent).
 */
internal fun parseMoney(text: String): BigDecimal? {
    val v = text.trim().replace(',', '.').toBigDecimalOrNull() ?: return null
    if (v.signum() < 0) return null
    if (v.stripTrailingZeros().scale() > 2) return null
    return v.setScale(2)
}
