package cd.ctm.agent.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.ctm.credit.network.SyncAction
import com.ctm.credit.network.SyncItem
import com.ctm.credit.network.SyncStatus
import com.ctm.credit.network.SyncStore
import com.google.gson.JsonParser
import java.time.Instant

data class LocalCustomerRef(
    val id: String,
    val fullName: String,
    val phoneNumber: String?,
    val status: SyncStatus,
)

/**
 * File durable des actions hors ligne (SQLite). Survit aux redémarrages.
 * Permet aussi d'inspecter toutes les actions pour corriger les conflits ou échecs.
 */
class SqliteSyncStore(context: Context) : SQLiteOpenHelper(context, "ctm_sync.db", null, 1), SyncStore {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE sync_item (" +
                "local_id INTEGER PRIMARY KEY, " +
                "idempotency_key TEXT NOT NULL UNIQUE, " +
                "action TEXT NOT NULL, " +
                "payload TEXT NOT NULL, " +
                "entity_key TEXT, " +
                "created_at TEXT NOT NULL, " +
                "updated_at TEXT NOT NULL, " +
                "status TEXT NOT NULL, " +
                "result TEXT, " +
                "error_code TEXT, " +
                "attempts INTEGER NOT NULL, " +
                "superseded_by TEXT)",
        )
        db.execSQL("CREATE INDEX sync_item_status_idx ON sync_item (status)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    override fun save(item: SyncItem) {
        val values = ContentValues().apply {
            put("local_id", item.localId)
            put("idempotency_key", item.idempotencyKey)
            put("action", item.action.wire)
            put("payload", item.payloadJson)
            put("entity_key", item.entityKey)
            put("created_at", item.clientCreatedAt.toString())
            put("updated_at", item.clientUpdatedAt.toString())
            put("status", item.status.name)
            put("result", item.resultJson)
            put("error_code", item.errorCode)
            put("attempts", item.attempts)
            put("superseded_by", item.supersededBy)
        }
        writableDatabase.insertWithOnConflict("sync_item", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    override fun sendable(limit: Int, excludeEntities: Set<String>): List<SyncItem> {
        val out = ArrayList<SyncItem>()
        readableDatabase.rawQuery(
            "SELECT * FROM sync_item WHERE status IN ('PENDING','SENT') ORDER BY local_id ASC",
            null,
        ).use { c ->
            while (out.size < limit && c.moveToNext()) {
                val item = readItem(c)
                val key = item.entityKey
                if (key == null || key !in excludeEntities) out.add(item)
            }
        }
        return out
    }

    @Synchronized
    override fun blockedEntities(): Set<String> {
        val out = HashSet<String>()
        readableDatabase.rawQuery(
            "SELECT entity_key FROM sync_item WHERE status IN ('FAILED','CONFLICT') " +
                "AND superseded_by IS NULL AND entity_key IS NOT NULL",
            null,
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    /**
     * Vrai si la création du client (`customer.create`) est encore présente localement et n'a pas encore
     * reçu le statut `APPLIED`. Tant que c'est vrai, les photos Storage dans `kyc/<customer_id>/...`
     * seraient refusées par la RLS du serveur.
     */
    @Synchronized
    fun hasUnappliedCustomerCreate(customerId: String): Boolean {
        readableDatabase.rawQuery(
            "SELECT status FROM sync_item WHERE action = ? AND entity_key = ? AND superseded_by IS NULL",
            arrayOf(SyncAction.CUSTOMER_CREATE.wire, customerId),
        ).use { c ->
            while (c.moveToNext()) {
                if (c.getString(0) != SyncStatus.APPLIED.name) return true
            }
        }
        return false
    }

    /** Clients créés localement sur cet appareil (en attente ou déjà synchronisés). */
    @Synchronized
    fun localCustomers(limit: Int = 30): List<LocalCustomerRef> {
        val out = ArrayList<LocalCustomerRef>()
        readableDatabase.rawQuery(
            "SELECT payload, status FROM sync_item WHERE action = ? AND superseded_by IS NULL ORDER BY local_id DESC LIMIT ?",
            arrayOf(SyncAction.CUSTOMER_CREATE.wire, limit.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val json = c.getString(0)
                val status = runCatching { SyncStatus.valueOf(c.getString(1)) }.getOrDefault(SyncStatus.PENDING)
                runCatching {
                    val obj = JsonParser.parseString(json).asJsonObject
                    val id = obj.get("id")?.takeIf { !it.isJsonNull }?.asString ?: return@runCatching
                    val first = obj.get("first_name")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                    val last = obj.get("last_name")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                    val phone = obj.get("phone_number")?.takeIf { !it.isJsonNull }?.asString
                    out.add(LocalCustomerRef(id = id, fullName = "$first $last".trim().ifBlank { "Client" }, phoneNumber = phone, status = status))
                }
            }
        }
        return out
    }

    @Synchronized
    fun listAll(limit: Int = 100): List<SyncItem> {
        val out = ArrayList<SyncItem>()
        readableDatabase.rawQuery(
            "SELECT * FROM sync_item ORDER BY local_id DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            while (c.moveToNext()) out.add(readItem(c))
        }
        return out
    }

    @Synchronized
    fun nextLocalId(): Long {
        var maxId = System.currentTimeMillis()
        readableDatabase.rawQuery("SELECT COALESCE(MAX(local_id), 0) FROM sync_item", null).use { c ->
            if (c.moveToFirst()) {
                maxId = maxOf(maxId, c.getLong(0) + 1)
            }
        }
        return maxId
    }

    private fun text(c: Cursor, name: String): String? {
        val index = c.getColumnIndexOrThrow(name)
        return if (c.isNull(index)) null else c.getString(index)
    }

    private fun readItem(c: Cursor): SyncItem = SyncItem(
        localId = c.getLong(c.getColumnIndexOrThrow("local_id")),
        idempotencyKey = text(c, "idempotency_key")!!,
        action = SyncAction.fromWire(text(c, "action")!!),
        payloadJson = text(c, "payload")!!,
        entityKey = text(c, "entity_key"),
        clientCreatedAt = Instant.parse(text(c, "created_at")!!),
        clientUpdatedAt = Instant.parse(text(c, "updated_at")!!),
        status = SyncStatus.valueOf(text(c, "status")!!),
        resultJson = text(c, "result"),
        errorCode = text(c, "error_code"),
        attempts = c.getInt(c.getColumnIndexOrThrow("attempts")),
        supersededBy = text(c, "superseded_by"),
    )
}
