package de.melnichuk.ssi

import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset

private fun Char.isParamChar() = isLetterOrDigit() || this == '_'

private sealed class State {

    object Html : State()

    // matching "<!--#"
    data class Tag(val matched: Int) : State()

        // matching closing "-->"
    data class CommentEnd(
        val matched: Int,
        val cmd: String,
        val params: Map<String, String> = emptyMap()
    ) : State()

    object PreCommand : State()
    data class Command   (val name: String) : State()
    data class PreParam  (val cmd: String, val params: Map<String, String> = emptyMap()) : State()
    data class Param     (val cmd: String, val params: Map<String, String>, val paramName: String) : State()
    data class PreEqual  (val cmd: String, val params: Map<String, String>, val paramName: String) : State()
    data class PreValue  (val cmd: String, val params: Map<String, String>, val paramName: String) : State()

    data class QuotedValue(
        val cmd: String,
        val params: Map<String, String>,
        val paramName: String,
        val value: String,
        val quote: Char
    ) : State()

    data class QuoteEscape(
        val cmd: String,
        val params: Map<String, String>,
        val paramName: String,
        val value: String,
        val quote: Char
    ) : State()

    object Error : State()
    data class ErrorEnd(val matched: Int) : State()
}

sealed class Segment {
    data class PlainTextSegment(
        val text: String,
    ): Segment()

    data class CommandSegment(
        val command: String,
        val params: Map<String, String> = emptyMap()
    ): Segment()
}

private const val SSI_PREFIX = "<!--#"
private const val SSI_SUFFIX = "-->"

class Parser {
    private val output = StringBuilder()
    private val pending = StringBuilder()

    private val segments = mutableListOf<Segment>()

    fun parse(
        inputStream: InputStream,
        charset: Charset = Charsets.UTF_8
    ): List<Segment> {
        var state: State = State.Html
        val reader = InputStreamReader(inputStream, charset)

        while (true) {
            val char = reader.read()
            if (char == -1) {
                break
            }

           state = step(state, char.toChar())
        }

        if (output.isNotEmpty()) {
            segments += Segment.PlainTextSegment(output.toString())
            output.clear()
        }

        if (pending.isNotEmpty()) {
            segments += Segment.PlainTextSegment(pending.toString())
            pending.clear()
        }

        return segments
    }

    private fun step(state: State, char: Char): State = when (state) {
        is State.Html -> if (char == '<') {
            pending.append('<')
            State.Tag(matched = 1)
        } else {
            output.append(char)
            State.Html
        }

        is State.Tag -> {
            val expected = SSI_PREFIX[state.matched]

            when (char) {
                expected -> {
                    val next = state.matched + 1
                    if (next == SSI_PREFIX.length) { // "<!--#" found
                        pending.clear()
                        segments.add(Segment.PlainTextSegment(output.toString()))
                        output.clear()
                        State.PreCommand
                    } else { // still parsing "<!--#"
                        pending.append(char)
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
                    output.append(char)
                    State.Html
                }
            }
        }

        is State.PreCommand -> when {
            char.isLetter() -> State.Command(char.toString())
            char.isWhitespace() -> State.PreCommand
            else -> State.Error
        }

        is State.Command -> when {
            char.isParamChar() -> State.Command(state.name + char)
            char.isWhitespace() -> State.PreParam(state.name)
            char == '-' -> State.CommentEnd(1, state.name)
            else -> State.Error
        }

        is State.PreParam -> when {
            char.isLetter() -> State.Param(state.cmd, state.params, char.toString())
            char.isWhitespace() -> state
            char == '-' -> State.CommentEnd(1, state.cmd, state.params)
            else -> State.Error
        }

        is State.Param -> when {
            char.isParamChar() -> State.Param(state.cmd, state.params, state.paramName + char)
            char.isWhitespace() -> State.PreEqual(state.cmd, state.params, state.paramName)
            char == '=' -> State.PreValue(state.cmd, state.params, state.paramName)
            else -> State.Error
        }

        is State.PreEqual -> when (char) {
            '=' -> State.PreValue(state.cmd, state.params, state.paramName)
            ' ', '\t' -> state
            else -> State.Error
        }

        is State.PreValue -> when (char) {
            '"', '\'' -> State.QuotedValue(state.cmd, state.params, state.paramName, value = "", quote = char)
            ' ', '\t' -> state
            else -> State.Error
        }

        is State.QuotedValue -> when (char) {
            state.quote -> State.PreParam(state.cmd, state.params + (state.paramName to state.value))
            '\\' -> State.QuoteEscape(state.cmd, state.params, state.paramName, state.value, state.quote)
            else -> State.QuotedValue(state.cmd, state.params, state.paramName, state.value + char, state.quote)
        }

        is State.QuoteEscape -> State.QuotedValue(state.cmd, state.params, state.paramName, state.value + char, state.quote)

        is State.CommentEnd -> {
            when (char) {
                SSI_SUFFIX[state.matched] -> {
                    when (val next = state.matched + 1) {
                        SSI_SUFFIX.length -> {
                            segments += Segment.CommandSegment(state.cmd, state.params)
                            State.Html
                        }

                        else -> State.CommentEnd(next, state.cmd, state.params)
                    }
                }

                else -> State.Error
            }
        }

        State.Error -> when (char) {
            SSI_SUFFIX[0] -> State.ErrorEnd(1)
            else -> State.Error
        }

        is State.ErrorEnd -> {
            when (char) {
                SSI_SUFFIX[state.matched] -> {
                    when (val next = state.matched + 1) {
                        SSI_SUFFIX.length -> State.Html
                        else -> State.ErrorEnd(next)
                    }
                }

                else -> State.Error
            }
        }
    }
}
