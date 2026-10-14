package app.novalabs.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The terminal's text engine (Ssh.kt TermBuffer), fed what real programs print. */
class TermBufferTest {
    private fun term(cols: Int = 20, rows: Int = 5) = TermBuffer().apply { resize(cols, rows) }

    @Test fun plainTextAndNewlines() {
        val t = term(); t.feed("hello\r\nworld")
        assertEquals("hello\nworld", t.text())
    }
    @Test fun carriageReturnOverwrites() {                 // progress bars: 10% … then 100% on the same line
        val t = term(); t.feed("10%\r100%")
        assertEquals("100%", t.text())
    }
    @Test fun eraseLine() {                                // ESC[2K clears the line, ESC[K clears to the end
        val t = term(); t.feed("abcdef\r\u001b[2Kxy"); assertEquals("xy", t.text())
        val u = term(); u.feed("abcdef\u001b[3D\u001b[K"); assertEquals("abc", u.text())
    }
    @Test fun wrapsAtTheWidth() {
        val t = term(cols = 20); t.feed("abcdefghijklmnopqrstuvwxy")            // (20 is the narrowest it goes)
        assertEquals("abcdefghijklmnopqrst\nuvwxy", t.text())
    }
    @Test fun colorsAreKeptButNotPrinted() {               // ls --color, prompts
        val t = term(); t.feed("\u001b[01;34mdir\u001b[0m file")
        assertEquals("dir file", t.text())
        val s = t.styled(androidx.compose.ui.graphics.Color.White, androidx.compose.ui.graphics.Color.Black)
        assertTrue("the blue part is styled", s.spanStyles.any { it.start == 0 && it.end == 3 })
    }
    @Test fun trueColorAnd256() {
        val t = term(); t.feed("\u001b[38;5;196mred\u001b[38;2;10;20;30mrgb\u001b[0m")
        assertEquals("redrgb", t.text())
        assertEquals(2, t.styled(androidx.compose.ui.graphics.Color.White, androidx.compose.ui.graphics.Color.Black).spanStyles.size)
    }
    @Test fun clearScreen() {
        val t = term(); t.feed("one\r\ntwo\r\n\u001b[H\u001b[2J"); t.feed("three")
        assertTrue(t.text().trim().endsWith("three")); assertTrue(!t.text().contains("one") || t.text().lines().size <= 5)
    }
    @Test fun fullScreenProgramGivesTheScrollbackBack() {  // top / nano / less use the alternate screen
        val t = term(); t.feed("before\r\n"); t.feed("\u001b[?1049h\u001b[Htop stuff"); t.feed("\u001b[?1049l")
        assertTrue(t.text().contains("before")); assertTrue(!t.text().contains("top stuff"))
    }
    @Test fun cursorMovesWithinTheScreen() {
        val t = term(cols = 10, rows = 3); t.feed("aaa\r\nbbb\r\nccc"); t.feed("\u001b[1;1HX")
        assertEquals("Xaa\nbbb\nccc", t.text().trimEnd())                     // (the screen is padded to its full height, like a real one)
    }
    @Test fun oscTitlesAreDropped() {
        val t = term(); t.feed("\u001b]0;user@host: ~\u0007prompt$ ")
        assertEquals("prompt$ ", t.text())
    }
}
