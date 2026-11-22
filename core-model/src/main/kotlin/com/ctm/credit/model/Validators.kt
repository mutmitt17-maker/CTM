package com.ctm.credit.model

/** IMEI check identical to the server function is_valid_imei(): 15 digits and a Luhn check digit. */
object Imei {
    fun isValid(value: String?): Boolean {
        if (value == null || value.length != 15 || !value.all { it in '0'..'9' }) return false
        var sum = 0
        for (i in 1..15) {
            var d = value[i - 1] - '0'
            if (i % 2 == 0) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
        }
        return sum % 10 == 0
    }
}

/** Democratic Republic of the Congo mobile numbers in international format, for example +243810000001. */
object CongoPhone {
    private val pattern = Regex("^\\+243[0-9]{9}$")

    fun isValid(value: String?): Boolean = value != null && pattern.matches(value)
}
