package dev.gyro.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.io.FileNotFoundException
import java.util.regex.Pattern
import javax.xml.parsers.DocumentBuilderFactory

class I18nParityTest {

    private fun findStringsFile(resDirName: String): File {
        val relative = "src/main/res/$resDirName/strings.xml"

        // 1. Direct path from core/designsystem module directory
        val direct = File(relative)
        if (direct.exists()) return direct

        // 2. Relative from project root
        val fromRoot = File("core/designsystem", relative)
        if (fromRoot.exists()) return fromRoot

        // 3. Search upwards through user.dir hierarchy
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null && dir.exists()) {
            val candidate = File(dir, "core/designsystem/$relative")
            if (candidate.exists()) return candidate
            val candidateDirect = File(dir, relative)
            if (candidateDirect.exists()) return candidateDirect
            dir = dir.parentFile
        }

        throw FileNotFoundException("Could not locate $relative from ${System.getProperty("user.dir")}")
    }

    private data class ParsedStrings(
        val stringMap: LinkedHashMap<String, String>,
        val duplicates: List<String>,
        val emptyValues: List<String>
    )

    private fun parseStringsXml(file: File): ParsedStrings {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(file)
        doc.documentElement.normalize()

        val stringNodes = doc.getElementsByTagName("string")

        val stringMap = linkedMapOf<String, String>()
        val duplicates = mutableListOf<String>()
        val emptyValues = mutableListOf<String>()

        for (i in 0 until stringNodes.length) {
            val node = stringNodes.item(i)
            if (node is Element) {
                val name = node.getAttribute("name").trim()
                val textContent = node.textContent?.trim() ?: ""

                if (name.isNotEmpty()) {
                    if (stringMap.containsKey(name)) {
                        duplicates.add(name)
                    } else {
                        stringMap[name] = textContent
                    }
                    if (textContent.isEmpty()) {
                        emptyValues.add(name)
                    }
                }
            }
        }
        return ParsedStrings(stringMap, duplicates, emptyValues)
    }

    private fun extractFormats(text: String, pattern: Pattern): List<String> {
        val matcher = pattern.matcher(text)
        val matches = mutableListOf<String>()
        while (matcher.find()) {
            matches.add(matcher.group())
        }
        return matches
    }

    @Test
    fun testDefaultStringsXmlExistsAndIsValid() {
        val defaultFile = findStringsFile("values")
        assertTrue("values/strings.xml must exist", defaultFile.exists())
        val parsed = parseStringsXml(defaultFile)
        assertTrue("values/strings.xml must contain string keys", parsed.stringMap.isNotEmpty())
        assertTrue("values/strings.xml must not have duplicate keys: ${parsed.duplicates}", parsed.duplicates.isEmpty())
        assertTrue("values/strings.xml must not have empty strings: ${parsed.emptyValues}", parsed.emptyValues.isEmpty())
    }

    @Test
    fun testEnglishStringsXmlExistsAndIsValid() {
        val enFile = findStringsFile("values-en")
        assertTrue("values-en/strings.xml must exist", enFile.exists())
        val parsed = parseStringsXml(enFile)
        assertTrue("values-en/strings.xml must contain string keys", parsed.stringMap.isNotEmpty())
        assertTrue("values-en/strings.xml must not have duplicate keys: ${parsed.duplicates}", parsed.duplicates.isEmpty())
        assertTrue("values-en/strings.xml must not have empty strings: ${parsed.emptyValues}", parsed.emptyValues.isEmpty())
    }

    @Test
    fun testStringParityBetweenDefaultAndEnglish() {
        val defaultFile = findStringsFile("values")
        val enFile = findStringsFile("values-en")

        val defaultParsed = parseStringsXml(defaultFile)
        val enParsed = parseStringsXml(enFile)

        val defaultKeys = defaultParsed.stringMap.keys
        val enKeys = enParsed.stringMap.keys

        val missingInEn = defaultKeys - enKeys
        val extraInEn = enKeys - defaultKeys

        val failureMessage = buildString {
            if (missingInEn.isNotEmpty()) {
                appendLine("Missing strings in values-en/strings.xml (${missingInEn.size}):")
                missingInEn.sorted().forEach { appendLine("  - $it") }
            }
            if (extraInEn.isNotEmpty()) {
                appendLine("Extra strings in values-en/strings.xml not in values/strings.xml (${extraInEn.size}):")
                extraInEn.sorted().forEach { appendLine("  - $it") }
            }
        }

        assertTrue(
            "String keys in values/strings.xml and values-en/strings.xml must match 100% 1:1.\n$failureMessage",
            missingInEn.isEmpty() && extraInEn.isEmpty()
        )
        assertEquals("Total count of string keys must be identical", defaultKeys.size, enKeys.size)

        // Verify format specifier parity (%d, %s, %1$s, etc.)
        val formatPattern = Pattern.compile("%[0-9]*\\$?[a-zA-Z0-9\\.]*[a-zA-Z]")
        for ((key, zhValue) in defaultParsed.stringMap) {
            val enValue = enParsed.stringMap[key]!!
            val zhFormats = extractFormats(zhValue, formatPattern)
            val enFormats = extractFormats(enValue, formatPattern)

            assertEquals(
                "Format specifier count mismatch for key '$key': ZH has $zhFormats vs EN has $enFormats",
                zhFormats.size,
                enFormats.size
            )
        }
    }
}
