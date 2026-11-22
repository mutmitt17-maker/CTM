package com.ctm.credit.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValidatorsTest {
    @Test
    fun `imei check`() {
        assertTrue(Imei.isValid("490154203237518"))
        assertFalse(Imei.isValid("490154203237519")) // wrong check digit
        assertFalse(Imei.isValid("49015420323751")) // 14 digits
        assertFalse(Imei.isValid("4901542032375180")) // 16 digits
        assertFalse(Imei.isValid("49015420323751A"))
        assertFalse(Imei.isValid(null))
    }

    @Test
    fun `congo phone numbers`() {
        assertTrue(CongoPhone.isValid("+243810000001"))
        assertFalse(CongoPhone.isValid("0810000001"))
        assertFalse(CongoPhone.isValid("+24381000000")) // too short
        assertFalse(CongoPhone.isValid("+2438100000012")) // too long
        assertFalse(CongoPhone.isValid(null))
    }
}
