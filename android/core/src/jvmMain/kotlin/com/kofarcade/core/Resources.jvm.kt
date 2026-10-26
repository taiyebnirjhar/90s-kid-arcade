package com.kofarcade.core

private object Resources

internal actual fun resourceText(path: String): String? =
    Resources::class.java.getResourceAsStream("/$path")?.bufferedReader()?.use { it.readText() }
