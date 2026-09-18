package dev.deepagent.mobile.agent.security

import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.Locale

class CanonicalArgsException(
    val code: String,
    message: String,
) : IllegalArgumentException(message)

object CanonicalArgs {
    const val VERSION = 1
    private const val MAX_INPUT_CHARS = 2 * 1024 * 1024
    private const val MAX_NUMBER_CHARS = 256
    private const val MAX_CANONICAL_CHARS = 4 * 1024 * 1024

    fun canonicalize(rawJson: String): ByteArray {
        require(rawJson.length <= MAX_INPUT_CHARS) {
            "Аргументы превышают лимит CanonicalArgs"
        }
        val value = Parser(rawJson).parseDocument()
        val canonical = encode(value)
        if (canonical.length > MAX_CANONICAL_CHARS) {
            throw CanonicalArgsException(
                "CANONICAL_TOO_LARGE",
                "CanonicalArgs превышают лимит",
            )
        }
        return canonical.toByteArray(StandardCharsets.UTF_8)
    }

    fun canonicalString(rawJson: String): String =
        String(canonicalize(rawJson), StandardCharsets.UTF_8)

    fun sha256(rawJson: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalize(rawJson))
        return digest.joinToString(separator = "") { byte ->
            "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
        }
    }

    fun hex(bytes: ByteArray): String =
        bytes.joinToString(separator = "") { byte ->
            "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
        }

    private sealed interface Value {
        data object Null : Value
        data class BooleanValue(val value: Boolean) : Value
        data class NumberValue(val value: String) : Value
        data class StringValue(val value: String) : Value
        data class ArrayValue(val values: List<Value>) : Value
        data class ObjectValue(val values: Map<String, Value>) : Value
    }

    private fun encode(value: Value): String {
        return when (value) {
            Value.Null -> "null"
            is Value.BooleanValue -> if (value.value) "true" else "false"
            is Value.NumberValue -> value.value
            is Value.StringValue -> encodeString(value.value)
            is Value.ArrayValue -> value.values.joinToString(
                prefix = "[",
                postfix = "]",
                separator = ",",
                transform = ::encode,
            )
            is Value.ObjectValue -> value.values.entries
                .sortedBy { it.key }
                .joinToString(
                    prefix = "{",
                    postfix = "}",
                    separator = ",",
                ) { (key, item) ->
                    encodeString(key) + ":" + encode(item)
                }
        }
    }

    private fun encodeString(value: String): String {
        val out = StringBuilder(value.length + 2).append('"')
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when (character) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                in '\u0000'..'\u001F' -> out.append(
                    "\\u" + "%04x".format(Locale.ROOT, character.code),
                )
                in '\uD800'..'\uDBFF' -> {
                    if (
                        index + 1 >= value.length ||
                        value[index + 1] !in '\uDC00'..'\uDFFF'
                    ) {
                        throw CanonicalArgsException(
                            "INVALID_STRING",
                            "Непарный surrogate в строке CanonicalArgs",
                        )
                    }
                    out.append(character)
                    out.append(value[index + 1])
                    index++
                }
                in '\uDC00'..'\uDFFF' ->
                    throw CanonicalArgsException(
                        "INVALID_STRING",
                        "Непарный surrogate в строке CanonicalArgs",
                    )
                else -> out.append(character)
            }
            index++
        }
        return out.append('"').toString()
    }

    private class Parser(
        private val input: String,
    ) {
        private var index: Int = 0

        fun parseDocument(): Value {
            skipWhitespace()
            val result = parseValue()
            skipWhitespace()
            if (index != input.length) {
                fail("TRAILING_DATA", "Лишние данные после JSON")
            }
            return result
        }

        private fun parseValue(): Value {
            if (index >= input.length) {
                fail("UNEXPECTED_EOF", "Ожидалось значение JSON")
            }
            return when (input[index]) {
                'n' -> {
                    expectLiteral("null")
                    Value.Null
                }
                't' -> {
                    expectLiteral("true")
                    Value.BooleanValue(true)
                }
                'f' -> {
                    expectLiteral("false")
                    Value.BooleanValue(false)
                }
                '"' -> Value.StringValue(parseString())
                '[' -> parseArray()
                '{' -> parseObject()
                '-' -> Value.NumberValue(parseNumber())
                in '0'..'9' -> Value.NumberValue(parseNumber())
                else -> fail("INVALID_VALUE", "Недопустимое JSON-значение")
            }
        }

        private fun parseObject(): Value.ObjectValue {
            consume('{')
            skipWhitespace()
            if (consumeIf('}')) {
                return Value.ObjectValue(emptyMap())
            }
            val values = LinkedHashMap<String, Value>()
            while (true) {
                skipWhitespace()
                if (index >= input.length || input[index] != '"') {
                    fail("INVALID_OBJECT", "Ожидался ключ JSON-объекта")
                }
                val key = parseString()
                if (values.containsKey(key)) {
                    fail("DUPLICATE_KEY", "Дублирующийся ключ JSON-объекта")
                }
                skipWhitespace()
                consume(':')
                values[key] = parseValue()
                skipWhitespace()
                if (consumeIf('}')) break
                consume(',')
            }
            return Value.ObjectValue(values)
        }

        private fun parseArray(): Value.ArrayValue {
            consume('[')
            skipWhitespace()
            if (consumeIf(']')) {
                return Value.ArrayValue(emptyList())
            }
            val values = mutableListOf<Value>()
            while (true) {
                values += parseValue()
                skipWhitespace()
                if (consumeIf(']')) break
                consume(',')
                skipWhitespace()
            }
            return Value.ArrayValue(values)
        }

        private fun parseString(): String {
            consume('"')
            val out = StringBuilder()
            while (index < input.length) {
                val character = input[index++]
                when (character) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (index >= input.length) {
                            fail("INVALID_STRING", "Оборванная escape-последовательность")
                        }
                        when (val escaped = input[index++]) {
                            '"', '\\', '/' -> out.append(escaped)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (index + 4 > input.length) {
                                    fail("INVALID_STRING", "Оборванный unicode escape")
                                }
                                val hex = input.substring(index, index + 4)
                                if (!hex.all { it in "0123456789abcdefABCDEF" }) {
                                    fail("INVALID_STRING", "Недопустимый unicode escape")
                                }
                                out.append(hex.toInt(16).toChar())
                                index += 4
                            }
                            else -> fail("INVALID_STRING", "Недопустимый escape JSON")
                        }
                    }
                    in '\u0000'..'\u001F' ->
                        fail("INVALID_STRING", "Control character в JSON-строке")
                    else -> out.append(character)
                }
            }
            fail("UNTERMINATED_STRING", "Незакрытая JSON-строка")
        }

        private fun parseNumber(): String {
            val start = index
            if (consumeIf('-')) {
                if (index >= input.length) {
                    fail("INVALID_NUMBER", "Оборванное отрицательное число")
                }
            }
            if (consumeIf('0')) {
                if (index < input.length && input[index].isDigit()) {
                    fail("INVALID_NUMBER", "Leading zero запрещён")
                }
            } else {
                if (index >= input.length || input[index] !in '1'..'9') {
                    fail("INVALID_NUMBER", "Недопустимая целая часть числа")
                }
                while (index < input.length && input[index] in '0'..'9') index++
            }
            if (consumeIf('.')) {
                val fractionStart = index
                while (index < input.length && input[index] in '0'..'9') index++
                if (index == fractionStart) {
                    fail("INVALID_NUMBER", "Пустая дробная часть числа")
                }
            }
            if (index < input.length && (input[index] == 'e' || input[index] == 'E')) {
                index++
                consumeIf('+')
                consumeIf('-')
                val exponentStart = index
                while (index < input.length && input[index] in '0'..'9') index++
                if (index == exponentStart) {
                    fail("INVALID_NUMBER", "Пустая exponent-часть числа")
                }
            }
            val raw = input.substring(start, index)
            if (raw.length > MAX_NUMBER_CHARS) {
                fail("INVALID_NUMBER", "Число превышает лимит")
            }
            return try {
                val decimal = BigDecimal(raw)
                if (decimal.compareTo(BigDecimal.ZERO) == 0) {
                    "0"
                } else {
                    decimal.stripTrailingZeros().toPlainString()
                }.also {
                    if (it.length > MAX_NUMBER_CHARS) {
                        fail("INVALID_NUMBER", "Каноническое число превышает лимит")
                    }
                }
            } catch (_: NumberFormatException) {
                fail("INVALID_NUMBER", "Недопустимое число")
            }
        }

        private fun expectLiteral(value: String) {
            if (!input.startsWith(value, index)) {
                fail("INVALID_VALUE", "Недопустимый JSON literal")
            }
            index += value.length
        }

        private fun consume(expected: Char) {
            if (index >= input.length || input[index] != expected) {
                fail("INVALID_JSON", "Ожидался символ " + expected)
            }
            index++
        }

        private fun consumeIf(value: Char): Boolean {
            if (index < input.length && input[index] == value) {
                index++
                return true
            }
            return false
        }

        private fun skipWhitespace() {
            while (
                index < input.length &&
                (input[index] == ' ' ||
                    input[index] == '\t' ||
                    input[index] == '\n' ||
                    input[index] == '\r')
            ) {
                index++
            }
        }

        private fun fail(code: String, message: String): Nothing {
            throw CanonicalArgsException(code, message + " at " + index)
        }
    }
}
