package com.ctm.credit.client

import java.math.BigDecimal

/**
 * Lit un montant saisi (virgule ou point). Renvoie null si ce n'est pas un nombre, s'il est négatif
 * ou s'il a plus de 2 décimales.
 */
internal fun parseMoney(text: String): BigDecimal? {
    val v = text.trim().replace(',', '.').toBigDecimalOrNull() ?: return null
    if (v.signum() < 0) return null
    if (v.stripTrailingZeros().scale() > 2) return null
    return v.setScale(2)
}
