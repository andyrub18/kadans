package app.kadans.config

actual fun defaultApiBaseUrl(): String =
    System.getenv("KADANS_API_URL") ?: "http://localhost:5199"
