package de.melnichuk.ssi

import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals

class SsiParserTest {

    private fun parse(html: String): String =
        SsiParser { cmd, params ->
            when (cmd) {
                "include" -> "[${params["virtual"]}]"
                else      -> throw IllegalArgumentException("Unknown cmd")
            }
        }.feed(html.byteInputStream(Charsets.UTF_8), Charsets.UTF_8)

    @Test fun `non-ascii content passes through unchanged`() {
        assertEquals("<p>Hellö 🫤 é</p>", parse("<p>Hellö 🫤 é</p>"))
    }

    @Test fun `single include directive is replaced`() {
        assertEquals("[/nav.html]", parse("""<!--#include virtual="/nav.html" -->"""))
    }

    @Test fun `directive is replaced inline, surrounding html preserved`() {
        assertEquals("<header>[/nav.html]</header>", parse("""<header><!--#include virtual="/nav.html" --></header>"""))
    }

    @Test fun `multiple directives are each replaced`() {
        assertEquals("[/nav.html][/footer.html]", parse("""<!--#include virtual="/nav.html" --><!--#include virtual="/footer.html" -->"""))
    }

    @Test fun `whitespace between hash and command name is accepted`() {
        assertEquals("[/nav.html]", parse("""<!--# include virtual="/nav.html" -->"""))
    }

    @Test fun `newline between hash and command name is accepted`() {
        assertEquals("[/nav.html]", parse("<!--#\n    include virtual=\"/nav.html\" -->"))
    }

    @Test fun `unknown command is passed to handler`() {
        assertThrows<IllegalArgumentException> {
            parse("""<!--#echo var="DATE" -->""")
        }
    }

    @Test fun `malformed directive is silently dropped`() {
        assertEquals("<p>after</p>", parse("""<!--#!! bad -->""" + "<p>after</p>"))
    }

    @Test fun `incomplete tag prefix at end of input passes through`() {
        assertEquals("hello<!--", parse("hello<!--"))
    }

    @Test fun `false tag start passes through`() {
        assertEquals("a < b", parse("a < b"))
    }

    @Test fun `single-quoted param value is accepted`() {
        assertEquals("[/nav.html]", parse("""<!--#include virtual='/nav.html' -->"""))
    }

    @Test fun `escaped quote inside value is accepted`() {
        assertEquals("""[/path/"x"]""", parse("""<!--#include virtual="/path/\"x\"" -->"""))
    }

    @Test fun `multiple params are all collected`() {
        var seenParams = emptyMap<String, String>()
        val html = """<!--#set var1="X" var2="42" -->"""
        SsiParser { _, params -> seenParams = params; "" }.feed(html.byteInputStream(Charsets.UTF_8), Charsets.UTF_8)
        assertEquals(mapOf("var1" to "X", "var2" to "42"), seenParams)
    }
}
