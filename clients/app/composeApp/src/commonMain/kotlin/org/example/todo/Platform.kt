package org.example.todo

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform