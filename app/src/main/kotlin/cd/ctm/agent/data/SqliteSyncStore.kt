package cd.ctm.agent.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.ctm.credit.network.SyncAction
import com.ctm.credit.network.SyncItem
import com.ctm.credit.network.SyncStatus
import com.ctm.credit.network.SyncStore
import com.ctm.credit.security.DatabaseKey
import com.google.gson.JsonParser
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.time.Instant

data class LocalCustomerRef(
    val id: String,
    val fullName: String,
    val phoneNumber: String?,
    val status: SyncStatus,
)

/**
 * File durable chiffrée (SQLCipher + Android KeyStore) des actions hors ligne.
 * - Migre automatiquement l'ancienne base non chiffrée `ctm_sync.db` vers `ctm_sync_enc.db`.
 * - Isole les actions par `agent_id` afin qu'un autre agent connecté sur le même appareil
 *   n'envoie pas accidentellement les actions d'un collègue.
 */
class SqliteSyncStore(
    private val context: Context,
    databaseKey: DatabaseKey,
    private val currentAgentId: () -> String? = { null },
) : SyncStore {

    private val helper: SupportSQLiteOpenHelper

    init {
        System.loadLibrary("sqlcipher")
        val factory = SupportOpenHelperFactory(databaseKey.get())
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(ENCRYPTED_DB_NAME)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(DB_VERSION) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        createSchema(db)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        if (oldVersion < 2) {
                            db.execSQL("ALTER TABLE sync_item ADD COLUMN agent_id TEXT")
                        }
                    }
                },
            )
            .build()
        helper = factory.create(config)
        migrateLegacyPlaintextIfNeeded()
    }

    private fun createSchema(db: SupportSQLiteDatabase) {
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
                "superseded_by TEXT, " +
                "agent_id TEXT)",
        )
        db.execSQL("CREATE INDEX sync_item_status_idx ON sync_item (status)")
        db.execSQL("CREATE INDEX sync_item_agent_idx ON sync_item (agent_id)")
    }

    private fun migrateLegacyPlaintextIfNeeded() {
        val legacyFile = context.getDatabasePath(LEGACY_DB_NAME)
        if (!legacyFile.exists()) return
        runCatching {
            val encDb = helper.writableDatabase
            SQLiteDatabase.openDatabase(legacyFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { plain ->
                plain.rawQuery("SELECT * FROM sync_item", null).use { c ->
                    encDb.beginTransaction()
                    try {
                        while (c.moveToNext()) {
                            val cv = ContentValues().apply {
                                put("local_id", c.getLong(c.getColumnIndexOrThrow("local_id")))
                                put("idempotency_key", c.getString(c.getColumnIndexOrThrow("idempotency_key")))
                                put("action", c.getString(c.getColumnIndexOrThrow("action")))
                                put("payload", c.getString(c.getColumnIndexOrThrow("payload")))
                                putStringOrNull(this, "entity_key", c)
                                put("created_at", c.getString(c.getColumnIndexOrThrow("created_at")))
                                put("updated_at", c.getString(c.getColumnIndexOrThrow("updated_at")))
                                put("status", c.getString(c.getColumnIndexOrThrow("status")))
                                putStringOrNull(this, "result", c)
                                putStringOrNull(this, "error_code", c)
                                put("attempts", c.getInt(c.getColumnIndexOrThrow("attempts")))
                                putStringOrNull(this, "superseded_by", c)
                                put("agent_id", currentAgentId())
                            }
                            encDb.insert("sync_item", SQLiteDatabase.CONFLICT_REPLACE, cv)
                        }
                        encDb.setTransactionSuccessful()
                    } finally {
                        encDb.endTransaction()
                    }
                }
            }
            context.deleteDatabase(LEGACY_DB_NAME)
        }
    }

    private fun putStringOrNull(cv: ContentValues, column: String, c: Cursor) {
        val idx = c.getColumnIndex(column)
        if (idx >= 0 && !c.isNull(idx)) {
            cv.put(column, c.getString(idx))
        } else {
            cv.putNull(column)
        }
    }

    private fun ownerFilterClause(agentId: String?): Pair<String, Array<Any>> {
        return if (agentId.isNullOrBlank()) {
            "agent_id IS NULL" to emptyArray()
        } else {
            "(agent_id IS NULL OR agent_id = ?)" to arrayOf(agentId)
        }
    }

    @Synchronized
    override fun save(item: SyncItem) {
        val db = helper.writableDatabase
        var preservedAgentId: String? = null
        db.query(
            "SELECT agent_id FROM sync_item WHERE local_id = ? LIMIT 1",
            arrayOf(item.localId),
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) preservedAgentId = c.getString(0)
        }
        val owner = preservedAgentId ?: currentAgentId()
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
            put("agent_id", owner)
        }
        db.insert("sync_item", SQLiteDatabase.CONFLICT_REPLACE, values)
    }

    @Synchronized
    override fun sendable(limit: Int, excludeEntities: Set<String>): List<SyncItem> {
        val out = ArrayList<SyncItem>()
        val (ownerClause, ownerArgs) = ownerFilterClause(currentAgentId())
        helper.readableDatabase.query(
            "SELECT * FROM sync_item WHERE status IN ('PENDING','SENT') AND $ownerClause ORDER BY local_id ASC",
            ownerArgs,
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
        val (ownerClause, ownerArgs) = ownerFilterClause(currentAgentId())
        helper.readableDatabase.query(
            "SELECT entity_key FROM sync_item WHERE status IN ('FAILED','CONFLICT') " +
                "AND superseded_by IS NULL AND entity_key IS NOT NULL AND $ownerClause",
            ownerArgs,
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    /** Nombre d'actions non encore appliquées appartenant à un autre agent sur cet appareil. */
    @Synchronized
    fun pendingForOtherAgentCount(activeAgentId: String?): Int {
        if (activeAgentId.isNullOrBlank()) return 0
        helper.readableDatabase.query(
            "SELECT COUNT(*) FROM sync_item WHERE status IN ('PENDING','SENT','FAILED','CONFLICT') " +
                "AND superseded_by IS NULL AND agent_id IS NOT NULL AND agent_id != ?",
            arrayOf(activeAgentId),
        ).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /**
     * Vrai si la création du client (`customer.create`) est encore présente localement et n'a pas encore
     * reçu le statut `APPLIED`. Tant que c'est vrai, les photos Storage dans `kyc/<customer_id>/...`
     * seraient refusées par la RLS du serveur.
     */
    @Synchronized
    fun hasUnappliedCustomerCreate(customerId: String): Boolean {
        helper.readableDatabase.query(
            "SELECT status FROM sync_item WHERE action = ? AND entity_key = ? AND superseded_by IS NULL",
            arrayOf(SyncAction.CUSTOMER_CREATE.wire, customerId),
        ).use { c ->
            while (c.moveToNext()) {
                if (c.getString(0) != SyncStatus.APPLIED.name) return true
            }
        }
        return false
    }

    /** Clients créés localement par l'agent actif (en attente ou déjà synchronisés). */
    @Synchronized
    fun localCustomers(limit: Int = 50): List<LocalCustomerRef> {
        val out = ArrayList<LocalCustomerRef>()
        val (ownerClause, ownerArgs) = ownerFilterClause(currentAgentId())
        val args: Array<Any> = arrayOf(SyncAction.CUSTOMER_CREATE.wire, *ownerArgs, limit)
        helper.readableDatabase.query(
            "SELECT payload, status FROM sync_item WHERE action = ? AND superseded_by IS NULL AND $ownerClause ORDER BY local_id DESC LIMIT ?",
            args,
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
        val (ownerClause, ownerArgs) = ownerFilterClause(currentAgentId())
        val args: Array<Any> = arrayOf(*ownerArgs, limit)
        helper.readableDatabase.query(
            "SELECT * FROM sync_item WHERE $ownerClause ORDER BY local_id DESC LIMIT ?",
            args,
        ).use { c ->
            while (c.moveToNext()) out.add(readItem(c))
        }
        return out
    }

    @Synchronized
    fun nextLocalId(): Long {
        var maxId = System.currentTimeMillis()
        helper.readableDatabase.query("SELECT COALESCE(MAX(local_id), 0) FROM sync_item", emptyArray()).use { c ->
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

    private companion object {
        const val LEGACY_DB_NAME = "ctm_sync.db"
        const val ENCRYPTED_DB_NAME = "ctm_sync_enc.db"
        const val DB_VERSION = 2
    }
}
