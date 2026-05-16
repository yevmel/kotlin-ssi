package de.melnichuk.ssi

import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset

private fun Char.isParamChar() = isLetterOrDigit() || this == '_'

private sealed class State {

    object Html : State()

    // matching "<!--#"
    data class Tag(val matched: Int) : State()

    object PreCommand : State()
    data class Command   (val name: String) : State()
    data class PreParam  (val cmd: String, val params: Map<String, String>) : State()
    data class Param     (val cmd: String, val params: Map<String, String>, val paramName: String) : State()
    data class PreEqual  (val cmd: String, val params: Map<String, String>, val paramName: String) : State()
    data class PreValue  (val cmd: String, val params: Map<String, String>, val paramName: String) : State()

    data class QuotedValue(val cmd: String, val params: Map<String, String>,
                           val paramName: String, val value: String, val quote: Char) : State()
    data class QuoteEscape(val cmd: String, val params: Map<String, String>,
                           val paramName: String, val value: String, val quote: Char) : State()

    // matching closing "-->"
    data class CommentEnd(val matched: Int, val cmd: String, val params: Map<String, String>) : State()

    object Error : State()
    data class ErrorEnd(val matched: Int) : State()
}

private val SSI_PREFIX = "<!--#"
private val SSI_SUFFIX = "-->"

class SsiParser(
    private val handler: (cmd: String, params: Map<String, String>) -> String)
{
    private var state: State = State.Html

    private val output  = StringBuilder()
    private val pending = StringBuilder() // buffers "<!--#" prefix until confirmed or rejected

    fun feed(input: InputStream, charset: Charset): String {
        val reader = InputStreamReader(input, charset)

        while (true) {
            val cp = reader.read()
            if (cp == -1) {
                break
            }

            state = step(state, cp.toChar())
        }

        output.append(pending) // unclosed tag prefix at end of input → pass through as-is
        pending.clear()

        try {
            return output.toString()
        } finally {
            output.clear()
        }
    }

    private fun step(s: State, c: Char): State = when (s) {
        State.Html -> if (c == '<') {
            pending.append('<')
            State.Tag(matched = 1)
        } else {
            output.append(c)
            State.Html
        }

        is State.Tag -> {
            val expected = SSI_PREFIX[s.matched]
            when (c) {
                expected -> {
                    val next = s.matched + 1
                    if (next == SSI_PREFIX.length) {
                        pending.clear() // "<!--#" found
                        State.PreCommand
                    } else {
                        pending.append(c)
                        State.Tag(next)
                    }
                }

                '<' -> {
                    output.append(pending)
                    pending.clear()
                    pending.append('<')
                    State.Tag(matched = 1)
                }

                else -> {
                    output.append(pending)
                    pending.clear()
                    output.append(c)
                    State.Html
                }
            }
        }

        State.PreCommand -> when {
            c.isLetter()        -> State.Command(c.toString())
            c.isWhitespace() -> State.PreCommand
            else                -> State.Error
        }

        is State.Command -> when {
            c.isParamChar()     -> State.Command(s.name + c)
            c.isWhitespace() -> State.PreParam(s.name, emptyMap())
            c == '-'            -> State.CommentEnd(1, s.name, emptyMap())
            else                -> State.Error
        }

        is State.PreParam -> when {
            c.isLetter()        -> State.Param(s.cmd, s.params, c.toString())
            c.isWhitespace() -> s
            c == '-'            -> State.CommentEnd(1, s.cmd, s.params)
            else                -> State.Error
        }

        is State.Param -> when {
            c.isParamChar()     -> State.Param(s.cmd, s.params, s.paramName + c)
            c.isWhitespace() -> State.PreEqual(s.cmd, s.params, s.paramName)
            c == '='            -> State.PreValue(s.cmd, s.params, s.paramName)
            else                -> State.Error
        }

        is State.PreEqual -> when (c) {
            '='        -> State.PreValue(s.cmd, s.params, s.paramName)
            ' ', '\t'  -> s
            else       -> State.Error
        }

        is State.PreValue -> when (c) {
            '"', '\'' -> State.QuotedValue(s.cmd, s.params, s.paramName, value = "", quote = c)
            ' ', '\t' -> s
            else      -> State.Error
        }

        is State.QuotedValue -> when {
            c == s.quote -> State.PreParam(s.cmd, s.params + (s.paramName to s.value))
            c == '\\'    -> State.QuoteEscape(s.cmd, s.params, s.paramName, s.value, s.quote)
            else         -> State.QuotedValue(s.cmd, s.params, s.paramName, s.value + c, s.quote)
        }

        is State.QuoteEscape -> State.QuotedValue(s.cmd, s.params, s.paramName, s.value + c, s.quote)

        is State.CommentEnd -> {
            if (c == SSI_SUFFIX[s.matched]) {
                val next = s.matched + 1
                if (next == SSI_SUFFIX.length) {
                    output.append(handler(s.cmd, s.params))
                    State.Html
                } else {
                    State.CommentEnd(next, s.cmd, s.params)
                }
            } else State.Error
        }

        State.Error -> if (c == SSI_SUFFIX[0]) State.ErrorEnd(1) else State.Error

        is State.ErrorEnd -> {
            if (c == SSI_SUFFIX[s.matched]) {
                val next = s.matched + 1
                if (next == SSI_SUFFIX.length) State.Html else State.ErrorEnd(next)
            } else State.Error
        }
    }
}
