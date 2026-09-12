package com.autism.seedcracker.chatgames;

/**
 * A tiny safe arithmetic evaluator for chat-game math questions ("5+3*2", "12/4-1", "(7+3)*2").
 * Supports + - * / % ^ parentheses, decimals, and unary minus. No eval / no arbitrary code.
 */
public final class MathSolver {
    private MathSolver() {}

    /** Solve an arithmetic expression, or null if it can't be parsed/evaluated. */
    public static Double solve(String expr) {
        if (expr == null) return null;
        String s = expr.trim()
            .replace('×', '*').replace('x', '*').replace('X', '*')
            .replace('÷', '/').replace(",", "");
        if (s.isEmpty()) return null;
        try {
            double v = new Parser(s).parse();
            if (Double.isNaN(v) || Double.isInfinite(v)) return null;
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    /** Format a result: whole numbers without a decimal point, else up to 4 decimals. */
    public static String format(double v) {
        if (v == Math.floor(v) && Math.abs(v) < 1e15) {
            return String.valueOf((long) v);
        }
        String s = String.format(java.util.Locale.ROOT, "%.4f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    // ======================================================================
    // Risky / advanced: word-problem solving.
    // ======================================================================

    /**
     * Solve a natural-language math word problem ("what is 5 plus 3 times 2", "12 divided by 4
     * minus 1", "half of 20", "5 squared", "10% of 200"). Converts it to an expression and solves.
     * Returns null if it can't be understood. This is heuristic and can misread - hence "risky".
     */
    public static Double solveWordProblem(String text) {
        if (text == null) return null;
        String s = text.toLowerCase(java.util.Locale.ROOT).trim();
        s = s.replace(",", "").replace("?", "").replace("=", " ");

        // Special forms first.
        Double special = specialForms(s);
        if (special != null) return special;

        // Tokenise words + numbers, mapping word-operators to symbols.
        String[] toks = s.split("[^a-z0-9.]+");
        StringBuilder expr = new StringBuilder();
        boolean any = false;
        for (String t : toks) {
            if (t.isEmpty()) continue;
            String mapped = mapToken(t);
            if (mapped != null) {
                expr.append(mapped).append(' ');
                any = true;
            }
        }
        if (!any) return null;
        return solve(expr.toString());
    }

    private static Double specialForms(String s) {
        java.util.regex.Matcher m;
        // "half of X"
        m = java.util.regex.Pattern.compile("half of ([0-9.]+)").matcher(s);
        if (m.find()) return solve(m.group(1)) != null ? solve(m.group(1)) / 2 : null;
        // "X squared" / "X cubed"
        m = java.util.regex.Pattern.compile("([0-9.]+) squared").matcher(s);
        if (m.find()) { Double v = solve(m.group(1)); return v == null ? null : v * v; }
        m = java.util.regex.Pattern.compile("([0-9.]+) cubed").matcher(s);
        if (m.find()) { Double v = solve(m.group(1)); return v == null ? null : v * v * v; }
        // "X to the power of Y" / "X ^ Y"
        m = java.util.regex.Pattern.compile("([0-9.]+) to the power of ([0-9.]+)").matcher(s);
        if (m.find()) return Math.pow(num(m.group(1)), num(m.group(2)));
        // "N% of X"
        m = java.util.regex.Pattern.compile("([0-9.]+)% of ([0-9.]+)").matcher(s);
        if (m.find()) return num(m.group(1)) / 100.0 * num(m.group(2));
        // "square root of X" / "sqrt X"
        m = java.util.regex.Pattern.compile("(?:square root of|sqrt) ?([0-9.]+)").matcher(s);
        if (m.find()) return Math.sqrt(num(m.group(1)));
        return null;
    }

    private static double num(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return 0; }
    }

    private static String mapToken(String t) {
        if (t.matches("[0-9.]+")) return t;
        // number words 0-20
        Integer nw = numberWord(t);
        if (nw != null) return String.valueOf(nw);
        return switch (t) {
            case "plus", "add", "added", "and" -> "+";
            case "minus", "subtract", "subtracted", "less" -> "-";
            case "times", "multiply", "multiplied", "x" -> "*";
            case "divide", "divided", "over" -> "/";
            case "mod", "modulo", "remainder" -> "%";
            case "power" -> "^";
            default -> null;
        };
    }

    private static Integer numberWord(String t) {
        return switch (t) {
            case "zero" -> 0; case "one" -> 1; case "two" -> 2; case "three" -> 3; case "four" -> 4;
            case "five" -> 5; case "six" -> 6; case "seven" -> 7; case "eight" -> 8; case "nine" -> 9;
            case "ten" -> 10; case "eleven" -> 11; case "twelve" -> 12; case "thirteen" -> 13;
            case "fourteen" -> 14; case "fifteen" -> 15; case "sixteen" -> 16; case "seventeen" -> 17;
            case "eighteen" -> 18; case "nineteen" -> 19; case "twenty" -> 20;
            default -> null;
        };
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) { this.s = s; }

        double parse() {
            double v = parseExpr();
            skipWs();
            if (i != s.length()) throw new IllegalArgumentException("trailing");
            return v;
        }

        private void skipWs() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        private double parseExpr() {
            double v = parseTerm();
            while (true) {
                skipWs();
                if (match('+')) v += parseTerm();
                else if (match('-')) v -= parseTerm();
                else return v;
            }
        }

        private double parseTerm() {
            double v = parseFactor();
            while (true) {
                skipWs();
                if (match('*')) v *= parseFactor();
                else if (match('/')) v /= parseFactor();
                else if (match('%')) v %= parseFactor();
                else return v;
            }
        }

        private double parseFactor() {
            double base = parseUnary();
            skipWs();
            if (match('^')) {
                double exp = parseFactor();
                return Math.pow(base, exp);
            }
            return base;
        }

        private double parseUnary() {
            skipWs();
            if (match('+')) return parseUnary();
            if (match('-')) return -parseUnary();
            return parsePrimary();
        }

        private double parsePrimary() {
            skipWs();
            if (match('(')) {
                double v = parseExpr();
                if (!match(')')) throw new IllegalArgumentException("missing )");
                return v;
            }
            return parseNumber();
        }

        private double parseNumber() {
            skipWs();
            int start = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
            if (start == i) throw new IllegalArgumentException("number expected");
            return Double.parseDouble(s.substring(start, i));
        }

        private boolean match(char c) {
            if (i < s.length() && s.charAt(i) == c) { i++; return true; }
            return false;
        }
    }
}
