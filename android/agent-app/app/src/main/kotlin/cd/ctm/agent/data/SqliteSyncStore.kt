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
import java.time.Instant

/**
 * File des actions à envoyer au serveur, gardée dans une base SQLite du téléphone : elle survit à un redémarrage.
 *
 * LIMITE CONNUE (jalon 3c) : la base n'est pas encore chiffrée avec SQLCipher ; elle reste dans le stockage privé
 * de l'application. Le chiffrement avec la clé du Keystore (interface DatabaseKey) viendra avec le jalon 3c.
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

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 : rien à migrer.
    }

    @Synchronized
    override fun save(item: SyncItem) {
        val values = ContentValues()
        values.put("local_id", item.localId)
        values.put("idempotency_key", item.idempotencyKey)
        values.put("action", item.action.wire)
        values.put("payload", item.payloadJson)
        values.put("entity_key", item.entityKey)
        values.put("created_at", item.clientCreatedAt.toString())
        values.put("updated_at", item.clientUpdatedAt.toString())
        values.put("status", item.status.name)
        values.put("result", item.resultJson)
        values.put("error_code", item.errorCode)
        values.put("attempts", item.attempts)
        values.put("superseded_by", item.supersededBy)
        writableDatabase.insertWithOnConflict("sync_item", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    override fun sendable(limit: Int, excludeEntities: Set<String>): List<SyncItem> {
        val out = ArrayList<SyncItem>()
        readableDatabase.rawQuery(
            "SELECT * FROM sync_item WHERE status IN ('PENDING','SENT') ORDER BY local_id ASC", null,
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
