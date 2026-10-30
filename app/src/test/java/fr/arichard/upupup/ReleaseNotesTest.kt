package fr.arichard.upupup

import fr.arichard.upupup.core.ReleaseNotes
import fr.arichard.upupup.core.UpdateManager
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class ReleaseNotesTest {

    @Test
    fun `fresh install shows no release notes`() {
        assertTrue(ReleaseNotes.versionsToShow(listOf("1.13"), null, "1.13", true).isEmpty())
    }

    @Test
    fun `upgrade without a saved version starts from the baseline`() {
        assertEquals(
            listOf("1.13"),
            ReleaseNotes.versionsToShow(listOf("1.13"), null, "1.13", false),
        )
    }

    @Test
    fun `multi-version upgrade shows every unseen release newest first`() {
        assertEquals(
            listOf("1.15", "1.14"),
            ReleaseNotes.versionsToShow(
                listOf("1.13", "1.14", "1.15", "1.16"),
                "1.13",
                "1.15",
                false,
            ),
        )
    }

    @Test
    fun `same and older versions show no release notes`() {
        val available = listOf("1.13", "1.14")
        assertTrue(ReleaseNotes.versionsToShow(available, "1.14", "1.14", false).isEmpty())
        assertTrue(ReleaseNotes.versionsToShow(available, "1.14", "1.13", false).isEmpty())
    }

    @Test
    fun `versions use numeric ordering and only include releases up to current`() {
        assertEquals(
            listOf("1.10", "1.9"),
            ReleaseNotes.versionsToShow(listOf("1.9", "1.10", "1.11"), "1.8", "1.10", false),
        )
    }

    @Test
    fun `english and french release notes match and every item starts with an emoji`() {
        val english = readArrays(File("src/main/res/values/release_notes.xml"))
        val french = readArrays(File("src/main/res/values-fr/release_notes.xml"))
        assertEquals(english.keys, french.keys)

        english.forEach { (name, items) ->
            assertEquals("Mismatched item count for $name", items.size, french.getValue(name).size)
            assertTrue("Keep each release to three notes or fewer", items.size in 1..3)
            items.forEach { item ->
                assertTrue("Keep each note concise: $item", item.codePointCount(0, item.length) <= 70)
                val firstCodePoint = item.trim().codePointAt(0)
                assertEquals(
                    "Each note must start with an emoji: $item",
                    Character.OTHER_SYMBOL.toInt(),
                    Character.getType(firstCodePoint).toInt(),
                )
            }
        }
    }

    @Test
    fun `release registry covers resources and current app version`() {
        val english = readArrays(File("src/main/res/values/release_notes.xml"))
        val french = readArrays(File("src/main/res/values-fr/release_notes.xml"))
        val registered = ReleaseNotes.versions.map { "release_notes_${it.replace('.', '_')}" }
        assertEquals(registered.toSet(), english.keys)
        assertEquals(registered.toSet(), french.keys)

        val gradleText = File("build.gradle.kts").readText()
        val versionMatch = Regex("""versionName\s*=\s*"([^"]+)"""").find(gradleText)
        assertFalse("app/build.gradle.kts must declare versionName", versionMatch == null)
        val versionName = requireNotNull(versionMatch).groupValues[1]
        val newestNotesVersion = ReleaseNotes.versions.maxWithOrNull(UpdateManager::compareVersions)
        assertTrue("Add notes for app version $versionName", newestNotesVersion != null)
        assertFalse(
            "Add notes for app version $versionName",
            UpdateManager.isNewer(versionName, requireNotNull(newestNotesVersion)),
        )
    }

    private fun readArrays(file: File): Map<String, List<String>> {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val arrays = root.getElementsByTagName("string-array")
        return (0 until arrays.length).associate { index ->
            val array = arrays.item(index) as Element
            val items = array.getElementsByTagName("item")
            array.getAttribute("name") to (0 until items.length).map { itemIndex ->
                items.item(itemIndex).textContent
            }
        }
    }
}