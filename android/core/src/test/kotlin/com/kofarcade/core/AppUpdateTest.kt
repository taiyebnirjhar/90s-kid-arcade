package com.kofarcade.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The website's version.json, as the app reads it to offer an update. */
class AppUpdateTest {
    @Test
    fun theSitesVersionIsRead() {
        val u = AppUpdate.parse(
            """{"code": 245, "name": "1.0.245", "notes": ["Play from My Library", " ", "A download count"], "apk": "https://arcade.taiyebnirjhor.com/90s-Kid-Arcade.apk"}""",
        )!!
        assertEquals(245, u.code)
        assertEquals("1.0.245", u.name)
        assertEquals(listOf("Play from My Library", "A download count"), u.notes)
        assertTrue(u.newerThan(243))
        assertFalse(u.newerThan(245))
        assertFalse(u.newerThan(250))
    }

    @Test
    fun anythingElseIsNoUpdate() {
        assertNull(AppUpdate.parse("<html>404</html>"))
        assertNull(AppUpdate.parse("""{"name": "1.0.1"}"""))
        assertNull(AppUpdate.parse("""{"code": 3, "name": "1.0.3", "apk": "http://plain.example/app.apk"}"""))
        assertNull(AppUpdate.parse("""{"code": 3, "na"""))
    }

    @Test
    fun noNotesIsFine() {
        assertEquals(emptyList(), AppUpdate.parse("""{"code": 3, "name": "1.0.3", "apk": "https://x.test/a.apk"}""")!!.notes)
    }
}
