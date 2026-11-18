package com.ctm.credit.agent

import java.math.BigDecimal

/**
 * Petit constructeur JSON sans dépendance (Kotlin pur).
 * Échappe correctement guillemets, antislashs, retours à la ligne et caractères de contrôle.
 */
internal object Json {
    /** JSON déjà prêt (tableau ou objet imbriqué), inséré tel quel. */
    class Raw(val json: String)

    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    fun value(v: Any?): String = when (v) {
        null -> "null"
        is Raw -> v.json
        is String -> quote(v)
        is Boolean -> v.toString()
        is BigDecimal -> v.toPlainString()
        is Number -> v.toString()
        else -> quote(v.toString())
    }

    fun obj(vararg pairs: Pair<String, Any?>): String =
        pairs.joinToString(",", "{", "}") { (k, v) -> quote(k) + ":" + value(v) }
}
