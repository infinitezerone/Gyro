package dev.gyro.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvConfigAdversarialTest {

    @Test
    fun testMalformedLinesWithoutKey() {
        // Testing how parser handles malformed lines like '=', '=value', '===', etc.
        val input = """
            =
            =value
            ===
             = = 
            valid-key=valid-value
        """.trimIndent()

        val parsed = MpvConfig.parse(input)
        assertEquals("valid-value", parsed["valid-key"])
        
        // Check if malformed keys slipped in
        assertNull("Empty key or '=' should not be a valid option", parsed["="])
        assertNull("Malformed '=value' should not be treated as a boolean flag", parsed["=value"])
        assertNull("Malformed '===' should not be treated as an option", parsed["==="])
        assertFalse("Map should not contain empty keys", parsed.containsKey(""))
    }

    @Test
    fun testMultipleEqualsSignsInValue() {
        val input = """
            af=lavfi=[alimiter=limit=0.95:attack=5:release=50:asc=true]
            complex=a=b=c=d=e
            leading_eq===double
        """.trimIndent()

        val parsed = MpvConfig.parse(input)
        assertEquals("lavfi=[alimiter=limit=0.95:attack=5:release=50:asc=true]", parsed["af"])
        assertEquals("a=b=c=d=e", parsed["complex"])
        assertEquals("==double", parsed["leading_eq"])
    }

    @Test
    fun testEscapedCharactersAndQuotes() {
        val input = """
            path="C:\\Users\\test\\video.mp4"
            double_quoted="value with spaces"
            single_quoted='another value'
            mixed_single_in_double="He said 'hello'"
            mixed_double_in_single='She said "goodbye"'
            empty_double=""
            empty_single=''
            whitespace_in_quotes="   "
        """.trimIndent()

        val parsed = MpvConfig.parse(input)
        assertEquals("""C:\\Users\\test\\video.mp4""", parsed["path"])
        assertEquals("value with spaces", parsed["double_quoted"])
        assertEquals("another value", parsed["single_quoted"])
        assertEquals("He said 'hello'", parsed["mixed_single_in_double"])
        assertEquals("""She said "goodbye"""", parsed["mixed_double_in_single"])
        assertEquals("", parsed["empty_double"])
        assertEquals("", parsed["empty_single"])
        assertEquals("   ", parsed["whitespace_in_quotes"])
    }

    @Test
    fun testUnmatchedQuotes() {
        val input = """
            unmatched_start="hello
            unmatched_end=world"
            single_quote="
            two_single_quotes=''
        """.trimIndent()

        val parsed = MpvConfig.parse(input)
        // Should not crash on unmatched quotes
        assertEquals("\"hello", parsed["unmatched_start"])
        assertEquals("world\"", parsed["unmatched_end"])
        assertEquals("\"", parsed["single_quote"])
        assertEquals("", parsed["two_single_quotes"])
    }

    @Test
    fun testInlineCommentsWithHash() {
        val input = """
            # Full line comment
            sub-color='#FFFFFF' # hex color in quotes followed by comment
            sub-font="Roboto#Bold" # hash inside quote is not comment
            param=value#notacomment
            param2=value #isacomment
            param3=value    # indented comment
        """.trimIndent()

        val parsed = MpvConfig.parse(input)
        assertEquals("#FFFFFF", parsed["sub-color"])
        assertEquals("Roboto#Bold", parsed["sub-font"])
        assertEquals("value#notacomment", parsed["param"])
        assertEquals("value", parsed["param2"])
        assertEquals("value", parsed["param3"])
    }

    @Test
    fun testUnicodeAndSpecialCharacters() {
        val input = """
            sub-font="思源黑体"
            title="Gyro 播放器 🚀 4K HDR"
            cyrillic=Привет_мир
            symbols=α+β=γ&δ!
            math=∑(x^2)≥0
        """.trimIndent()

        val parsed = MpvConfig.parse(input)
        assertEquals("思源黑体", parsed["sub-font"])
        assertEquals("Gyro 播放器 🚀 4K HDR", parsed["title"])
        assertEquals("Привет_мир", parsed["cyrillic"])
        assertEquals("α+β=γ&δ!", parsed["symbols"])
        assertEquals("∑(x^2)≥0", parsed["math"])
    }

    @Test
    fun testEmptyAndCommentOnlyInputs() {
        assertTrue(MpvConfig.parse("").isEmpty())
        assertTrue(MpvConfig.parse("   \n\t\r\n   ").isEmpty())
        assertTrue(MpvConfig.parse("# Comment 1\n# Comment 2\n   # Indented").isEmpty())
        assertTrue(MpvConfig.parse("### ### ###").isEmpty())
        assertTrue(MpvConfig.parse("\n\n\n").isEmpty())
    }

    @Test
    fun testDuplicateKeysPrecedence() {
        val input = """
            vo=gpu
            vo=gpu-next
            vo=libmpv
            hwdec=no
            hwdec=mediacodec
        """.trimIndent()

        val parsed = MpvConfig.parse(input)
        // Later lines must overwrite earlier lines
        assertEquals("libmpv", parsed["vo"])
        assertEquals("mediacodec", parsed["hwdec"])
        assertEquals(2, parsed.size)
    }

    @Test
    fun testStandaloneBooleansAndNegations() {
        val input = """
            deband
            no-deband
            keep-open
            --blend-subtitles
            --no-correct-pts
            no-
        """.trimIndent()

        val parsed = MpvConfig.parse(input)
        assertEquals("no", parsed["deband"]) // no-deband overwrote deband
        assertEquals("yes", parsed["keep-open"])
        assertEquals("yes", parsed["blend-subtitles"])
        assertEquals("no", parsed["correct-pts"])
        assertFalse("Lone 'no-' should not generate empty string key", parsed.containsKey(""))
    }
}
