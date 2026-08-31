package app.kadans.api

import app.kadans.api.model.ApiProblem

class KadansApiException(
    val httpStatus: Int,
    val problem: ApiProblem?,
) : Exception(problem?.detail ?: problem?.title ?: "Kadans API request failed with HTTP $httpStatus") {
    val errorCode: String? get() = problem?.errorCode
}
