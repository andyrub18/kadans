package app.kadans.api

import app.kadans.api.model.ApiProblem

class KadansApiException(
    val httpStatus: Int,
    val problem: ApiProblem?,
) : Exception(problem?.detail ?: problem?.title ?: "Kadans API request failed with HTTP $httpStatus") {
    val errorCode: String? get() = problem?.errorCode
}

/**
 * The server could not answer a session refresh: restarting for a deploy, overloaded, or behind a proxy that
 * answered for it. The session is kept and only this call fails; the next one tries again.
 */
class ServerUnavailableException(val httpStatus: Int) :
    Exception("The server could not refresh the session (HTTP $httpStatus).")
