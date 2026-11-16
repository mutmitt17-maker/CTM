package com.ctm.credit.network

enum class ApiErrorKind {
    /** No answer: offline, timeout, connection cut. The request may or may not have reached the server. */
    NETWORK,
    /** HTTP 401: the token is missing, expired or invalid. */
    UNAUTHORIZED,
    /** HTTP 403 (code 42501): the account has no right to do this. */
    FORBIDDEN,
    /** HTTP 409 (code P0409): business conflict. */
    CONFLICT,
    /** Other 4xx, including HTTP 400: the request was refused. */
    REJECTED,
    /** HTTP 5xx: the server failed. Retry later. */
    SERVER,
}

class ApiException(val kind: ApiErrorKind, message: String? = null, cause: Throwable? = null) :
    Exception(message ?: kind.name, cause)

object ApiErrorClassifier {
    /**
     * @param httpStatus null when no HTTP answer was received.
     * @param code the "code" field of the error body, when present.
     */
    fun classify(httpStatus: Int?, code: String? = null): ApiErrorKind = when {
        httpStatus == null -> ApiErrorKind.NETWORK
        httpStatus == 401 -> ApiErrorKind.UNAUTHORIZED
        httpStatus == 403 || code == "42501" -> ApiErrorKind.FORBIDDEN
        httpStatus == 409 || code == "P0409" -> ApiErrorKind.CONFLICT
        httpStatus >= 500 -> ApiErrorKind.SERVER
        httpStatus in 400..499 -> ApiErrorKind.REJECTED
        else -> ApiErrorKind.SERVER
    }
}
