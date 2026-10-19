// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan.fused;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Splits a fused GLSL library into functions and top-level statements, the way the drift and constants tests
 * read both {@code fused_lib.glsl} and {@code fused_lib_ieee.glsl}. A function starts at a header at column
 * zero ({@code [precise] type name(parameters) {}) and ends at its matching brace; {@code //} comments are
 * skipped while matching, so a brace in a comment cannot end a function early.
 */
final class FusedLibraryText {
    private static final Pattern HEADER = Pattern.compile(
            "(?m)^(?:precise\\s+)?[A-Za-z_][A-Za-z0-9_]*\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\([^;{}]*\\)\\s*\\{");

    /** A decimal floating literal of GLSL with an optional suffix, or an integer with a double suffix. */
    private static final String FLOATING_LITERAL =
            "(?<![A-Za-z0-9_.])(?:\\d+\\.\\d*(?:[eE][+-]?\\d+)?|\\.\\d+(?:[eE][+-]?\\d+)?|\\d+[eE][+-]?\\d+)(?:lf|LF|f|F)?(?![A-Za-z0-9_.])"
                    + "|(?<![A-Za-z0-9_.])\\d+(?:lf|LF)(?![A-Za-z0-9_.])";

    /** Floating types, {@code precise}, floating literals and the builtins that only exist for floating values. */
    private static final Pattern FLOATING = Pattern.compile(
            "\\b(?:float|double|precise|[fd]?vec[234]|[fd]?mat[234](?:x[234])?)\\b"
                    + "|\\b(?:isnan|isinf|floor|ceil|sqrt|fma|frexp|ldexp|packDouble2x32|unpackDouble2x32)\\b"
                    + "|\\b[A-Za-z0-9]*BitsTo[A-Za-z0-9]*\\b|" + FLOATING_LITERAL);

    private FusedLibraryText() {}

    /** True when comment-free GLSL text contains any floating-point token. */
    static boolean hasFloatingToken(String commentFree) {
        return FLOATING.matcher(hexLiteralsBlanked(commentFree)).find();
    }

    /** Hex literals can contain the letter e and so look like exponents; blank them before scanning for literals. */
    static String hexLiteralsBlanked(String text) {
        return text.replaceAll("\\b0[xX][0-9a-fA-F]+[uUlL]*\\b", "0");
    }

    /** A function with its exact text, header to closing brace. */
    record Function(String name, int start, int end, String text) {}

    static List<Function> functions(String source) {
        var result = new ArrayList<Function>();
        var header = HEADER.matcher(source);
        int from = 0;
        while (header.find(from)) {
            int end = closingBrace(source, header.end());
            result.add(new Function(header.group(1), header.start(), end, source.substring(header.start(), end)));
            from = end;
        }
        return result;
    }

    static Map<String, Function> functionsByName(String source) {
        var result = new LinkedHashMap<String, Function>();
        for (Function function : functions(source)) {
            if (result.put(function.name(), function) != null) throw new IllegalStateException("duplicate function " + function.name());
        }
        return result;
    }

    /** Index just past the brace that closes the block opened immediately before {@code from}. */
    private static int closingBrace(String source, int from) {
        int depth = 1;
        int index = from;
        while (index < source.length() && depth > 0) {
            char value = source.charAt(index);
            if (value == '/' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
                while (index < source.length() && source.charAt(index) != '\n') index++;
                continue;
            }
            if (value == '{') depth++;
            else if (value == '}') depth--;
            index++;
        }
        if (depth != 0) throw new IllegalStateException("unterminated function at offset " + from);
        return index;
    }

    /** The text without {@code //} comments (the line ends stay). */
    static String withoutComments(String source) {
        return source.replaceAll("//[^\n]*", "");
    }

    /** The statements outside every function, comments removed and whitespace collapsed. */
    static List<String> topLevelStatements(String source) {
        var outside = new StringBuilder();
        int last = 0;
        for (Function function : functions(source)) {
            outside.append(source, last, function.start());
            last = function.end();
        }
        outside.append(source.substring(last));
        String text = withoutComments(outside.toString());
        var statements = new ArrayList<String>();
        var current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (value == '{') depth++;
            else if (value == '}') depth--;
            current.append(value);
            if (value == ';' && depth == 0) {
                String statement = current.toString().replaceAll("\\s+", " ").trim();
                if (!statement.equals(";")) statements.add(statement);
                current.setLength(0);
            }
        }
        if (!current.toString().isBlank()) throw new IllegalStateException("text after the last statement: " + current);
        return statements;
    }
}
