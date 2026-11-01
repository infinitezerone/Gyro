package dev.gyro.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvConfigTest {

    @Test
    fun testParseStandardKeyValuePairs() {
        val input = """
            vo=libmpv
            hwdec=mediacodec
            speed=1.5
            keep-open=yes
        """.trimIndent()

        val parsed = MpvConfig.parse(input)

        assertEquals("libmpv", parsed["vo"])
        assertEquals("mediacodec", parsed["hwdec"])
        assertEquals("1.5", parsed["speed"])
        assertEquals("yes", parsed["keep-open"])
        assertEquals(4, parsed.size)
    }

    @Test
    fun testParseCommentsAndEmptyLines() {
        val input = """
            # Top-level comment
            vo=libmpv
            
               # Indented comment with leading whitespace
            hwdec=mediacodec
            
            
            # Another comment
        """.trimIndent()

        val parsed = MpvConfig.parse(input)

        assertEquals(2, parsed.size)
        assertEquals("libmpv", parsed["vo"])
        assertEquals("mediacodec", parsed["hwdec"])
        assertFalse(parsed.keys.any { it.startsWith("#") })
    }

    @Test
    fun testParseStandaloneBooleanFlags() {
        val input = """
            deband
            keep-open
            no-cache
            vo=libmpv
        """.trimIndent()

        val parsed = MpvConfig.parse(input)

        assertEquals("yes", parsed["deband"])
        assertEquals("yes", parsed["keep-open"])
        assertEquals("no", parsed["cache"])
        assertEquals("libmpv", parsed["vo"])
        assertEquals(4, parsed.size)
    }

    @Test
    fun testWhitespaceAndLeadingDashesHandling() {
        val input = """
              --hwdec   =   mediacodec-copy  
             --deband  
           blend-subtitles = no   
        """.trimIndent()

        val parsed = MpvConfig.parse(input)

        assertEquals("mediacodec-copy", parsed["hwdec"])
        assertEquals("yes", parsed["deband"])
        assertEquals("no", parsed["blend-subtitles"])
    }

    @Test
    fun testComplexValuesWithEqualsSigns() {
        val input = """
            af=lavfi=[alimiter=limit=0.95:attack=5:release=50:asc=true]
            gpu-context=android
        """.trimIndent()

        val parsed = MpvConfig.parse(input)

        assertEquals("lavfi=[alimiter=limit=0.95:attack=5:release=50:asc=true]", parsed["af"])
        assertEquals("android", parsed["gpu-context"])
    }

    @Test
    fun testQuotedValuesAndInlineComments() {
        val input = """
            title="My Video #1" # note
            sub-color='#FFFFFF'
            sub-font="Roboto"
        """.trimIndent()

        val parsed = MpvConfig.parse(input)

        assertEquals("My Video #1", parsed["title"])
        assertEquals("#FFFFFF", parsed["sub-color"])
        assertEquals("Roboto", parsed["sub-font"])
    }

    @Test
    fun testEmptyInputAndCommentsOnly() {
        assertTrue(MpvConfig.parse("").isEmpty())
        assertTrue(MpvConfig.parse("   \n\n\t  ").isEmpty())
        assertTrue(MpvConfig.parse("# Just a comment\n# Another").isEmpty())
    }

    @Test
    fun testDefaultTemplateValidity() {
        val template = MpvConfig.DEFAULT_TEMPLATE
        assertTrue("Template must not be blank", template.isNotBlank())

        val parsed = MpvConfig.parse(template)
        assertEquals("libmpv", parsed["vo"])
        assertEquals("android", parsed["gpu-context"])
        assertEquals("no", parsed["blend-subtitles"])
        assertEquals("no", parsed["sub-ass-override"])
    }

    @Test
    fun testDataClassPropertiesAndCopy() {
        val raw = "vo=libmpv\ndeband"
        val map = mapOf("vo" to "libmpv", "deband" to "yes")
        val config1 = MpvConfig(raw, map)
        val config2 = config1.copy(rawText = "vo=gpu")

        assertEquals(raw, config1.rawText)
        assertEquals(map, config1.options)
        assertEquals("vo=gpu", config2.rawText)
        assertEquals(map, config2.options)
        assertEquals(config1, MpvConfig(raw, map))
        assertEquals(config1.hashCode(), MpvConfig(raw, map).hashCode())
    }

    @Test
    fun testHexColorsUnquotedAndQuotedWithComments() {
        val input = """
            osd-color=#FF0000
            sub-color='#00FF00'
            osd-border-color=#80000000 # Semi-transparent black
            sub-back-color="#102030" # Quoted with comment
            sub-border-color = #FFFFFF 
        """.trimIndent()

        val parsed = MpvConfig.parse(input)
        assertEquals("#FF0000", parsed["osd-color"])
        assertEquals("#00FF00", parsed["sub-color"])
        assertEquals("#80000000", parsed["osd-border-color"])
        assertEquals("#102030", parsed["sub-back-color"])
        assertEquals("#FFFFFF", parsed["sub-border-color"])
    }
}
