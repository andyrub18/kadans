package app.kadans.api.model

import kotlinx.serialization.Serializable

/** RFC 9457 problem details as the API emits them, with Kadans' `errorCode` extension. */
@Serializable
data class ApiProblem(
    val type: String? = null,
    val title: String? = null,
    val status: Int? = null,
    val detail: String? = null,
    val instance: String? = null,
    val errorCode: String? = null,
    val errors: List<FieldError>? = null,
) {
    @Serializable
    data class FieldError(val code: String? = null, val message: String? = null)
}
