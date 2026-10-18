package agent.next.brain.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small dependency-free JSON reader/string writer for the model transport.
 * The model response is untrusted: parsing is bounded and trailing data is
 * rejected.
 */
final class Json {

    private static final int MAX_INPUT = 1_048_576;
    private static final int MAX_DEPTH = 64;

    private Json() {
    }

    static Object parse(String text) {
        if (text == null || text.length() > MAX_INPUT) {
            throw new IllegalArgumentException("invalid JSON input length");
        }
        Parser parser = new Parser(text);
        Object value = parser.value(0);
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw parser.error("trailing data");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("expected JSON object");
        }
        return (Map<String, Object>) value;
    }

    static String string(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("expected string field " + key);
        }
        return (String) value;
    }

    static String quote(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2);
        result.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 0x20) {
                        result.append(String.format("\\u%04x", (int) character));
                    } else {
                        result.append(character);
                    }
                }
            }
        }
        return result.append('"').toString();
    }

    private static final class Parser {

        private final String text;
        private int index;

        private Parser(String text) {
            this.text = text;
        }

        private Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("nesting too deep");
            }
            skipWhitespace();
            if (atEnd()) {
                throw error("unexpected end");
            }
            char character = text.charAt(index);
            return switch (character) {
                case '{' -> object(depth + 1);
                case '[' -> array(depth + 1);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> {
                    if (character == '-' || (character >= '0' && character <= '9')) {
                        yield number();
                    }
                    throw error("unexpected character");
                }
            };
        }

        private Map<String, Object> object(int depth) {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                index++;
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = string();
                skipWhitespace();
                expect(':');
                Object value = value(depth);
                if (result.containsKey(key)) {
                    throw error("duplicate object key");
                }
                result.put(key, value);
                skipWhitespace();
                if (peek('}')) {
                    index++;
                    return result;
                }
                expect(',');
            }
        }

        private List<Object> array(int depth) {
            expect('[');
            List<Object> result = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                index++;
                return result;
            }
            while (true) {
                result.add(value(depth));
                skipWhitespace();
                if (peek(']')) {
                    index++;
                    return result;
                }
                expect(',');
            }
        }

        private String string() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (!atEnd()) {
                char character = text.charAt(index++);
                if (character == '"') {
                    return result.toString();
                }
                if (character == '\\') {
                    if (atEnd()) {
                        throw error("unterminated escape");
                    }
                    char escaped = text.charAt(index++);
                    switch (escaped) {
                        case '"' -> result.append('"');
                        case '\\' -> result.append('\\');
                        case '/' -> result.append('/');
                        case 'b' -> result.append('\b');
                        case 'f' -> result.append('\f');
                        case 'n' -> result.append('\n');
                        case 'r' -> result.append('\r');
                        case 't' -> result.append('\t');
                        case 'u' -> result.append(unicode());
                        default -> throw error("invalid escape");
                    }
                } else if (character < 0x20) {
                    throw error("control character in string");
                } else {
                    result.append(character);
                }
            }
            throw error("unterminated string");
        }

        private char unicode() {
            if (index + 4 > text.length()) {
                throw error("truncated unicode escape");
            }
            int value = 0;
            for (int count = 0; count < 4; count++) {
                int digit = Character.digit(text.charAt(index++), 16);
                if (digit < 0) {
                    throw error("invalid unicode escape");
                }
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        private Object number() {
            int start = index;
            if (peek('-')) {
                index++;
            }
            if (atEnd()) {
                throw error("truncated number");
            }
            if (peek('0')) {
                index++;
            } else {
                digits();
            }
            if (peek('.')) {
                index++;
                digits();
            }
            if (peek('e') || peek('E')) {
                index++;
                if (peek('+') || peek('-')) {
                    index++;
                }
                digits();
            }
            try {
                return new BigDecimal(text.substring(start, index));
            } catch (NumberFormatException invalid) {
                throw error("invalid number");
            }
        }

        private void digits() {
            int start = index;
            while (!atEnd() && text.charAt(index) >= '0' && text.charAt(index) <= '9') {
                index++;
            }
            if (start == index) {
                throw error("expected digit");
            }
        }

        private Object literal(String expected, Object value) {
            if (!text.startsWith(expected, index)) {
                throw error("invalid literal");
            }
            index += expected.length();
            return value;
        }

        private void expect(char expected) {
            skipWhitespace();
            if (atEnd() || text.charAt(index) != expected) {
                throw error("expected " + expected);
            }
            index++;
        }

        private boolean peek(char expected) {
            return !atEnd() && text.charAt(index) == expected;
        }

        private void skipWhitespace() {
            while (!atEnd()) {
                char character = text.charAt(index);
                if (character == ' ' || character == '\n' || character == '\r'
                        || character == '\t') {
                    index++;
                } else {
                    return;
                }
            }
        }

        private boolean atEnd() {
            return index >= text.length();
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException("JSON " + message + " at " + index);
        }
    }
}
