package com.kofarcade.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The newest version, as the website says (its version.json, written when
 * it's published: .github/workflows/pages.yml): its build number ([code], the
 * APK's versionCode), its name (1.0.245), what's new, and where its APK is.
 */
data class AppUpdate(val code: Int, val name: String, val notes: List<String>, val apk: String) {
    /** Newer than the build [current] (this APK's versionCode). */
    fun newerThan(current: Int): Boolean = code > current

    companion object {
        /** version.json, read; null if it isn't one (a page with no version, half a file). */
        fun parse(text: String): AppUpdate? = runCatching {
            val o = Json.parseToJsonElement(text).jsonObject
            val code = o["code"]?.jsonPrimitive?.intOrNull ?: return null
            val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return null
            val apk = o["apk"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("https://") } ?: return null
            val notes = o["notes"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()
            AppUpdate(code, name, notes, apk)
        }.getOrNull()
    }
}
