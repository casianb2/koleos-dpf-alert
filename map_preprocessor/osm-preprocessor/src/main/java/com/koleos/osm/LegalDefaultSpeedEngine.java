package com.koleos.osm;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

public class LegalDefaultSpeedEngine {
    private final LegalDefaultSpeeds data;
    private final Map<String, Expr> compiledFilters = new HashMap<>();
    private final Map<String, Expr> compiledFuzzyFilters = new HashMap<>();
    private final Map<String, Expr> compiledRelationFilters = new HashMap<>();

    public LegalDefaultSpeedEngine(File jsonFile) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        this.data = mapper.readValue(jsonFile, LegalDefaultSpeeds.class);

        for (Map.Entry<String, RoadType> e : data.roadTypesByName.entrySet()) {
            String name = e.getKey();
            RoadType rt = e.getValue();

            if (rt.filter != null && !rt.filter.isBlank()) {
                compiledFilters.put(name, new FilterParser(rt.filter).parse());
            }

            if (rt.fuzzyFilter != null && !rt.fuzzyFilter.isBlank()) {
                compiledFuzzyFilters.put(name, new FilterParser(rt.fuzzyFilter).parse());
            }

            if (rt.relationFilter != null && !rt.relationFilter.isBlank()) {
                compiledRelationFilters.put(name, new FilterParser(rt.relationFilter).parse());
            }
        }
    }

    public Integer computeMaxspeedKmh(
            String countryCode,
            Map<String, String> wayTags,
            List<Map<String, String>> relationTags
    ) {
        List<SpeedRule> rules = findCountryRules(countryCode);
        if (rules == null) return null;

        SpeedRule fallback = null;

        for (SpeedRule rule : rules) {
            if (rule.tags == null || !rule.tags.containsKey("maxspeed")) continue;

            if (rule.name == null || rule.name.isBlank()) {
                fallback = rule;
                continue;
            }

            if (matchesRoadType(rule.name, wayTags, relationTags, MatchMode.EXACT)) {
                return parseMaxspeedKmh(rule.tags.get("maxspeed"));
            }
        }

        for (SpeedRule rule : rules) {
            if (rule.tags == null || !rule.tags.containsKey("maxspeed")) continue;
            if (rule.name == null || rule.name.isBlank()) continue;

            if (matchesRoadType(rule.name, wayTags, relationTags, MatchMode.FUZZY)) {
                return parseMaxspeedKmh(rule.tags.get("maxspeed"));
            }
        }

        if (fallback != null) {
            return parseMaxspeedKmh(fallback.tags.get("maxspeed"));
        }

        return null;
    }

    private List<SpeedRule> findCountryRules(String countryCode) {
        String code = countryCode.toUpperCase(Locale.ROOT);

        List<SpeedRule> exact = data.speedLimitsByCountryCode.get(code);
        if (exact != null) return exact;

        int dash = code.indexOf('-');
        if (dash > 0) {
            return data.speedLimitsByCountryCode.get(code.substring(0, dash));
        }

        return null;
    }

    private boolean matchesRoadType(
            String roadTypeName,
            Map<String, String> wayTags,
            List<Map<String, String>> relationTags,
            MatchMode mode
    ) {
        Set<String> stack = new HashSet<>();
        return matchesRoadTypeInternal(roadTypeName, wayTags, relationTags, mode, stack);
    }

    private boolean matchesRoadTypeInternal(
            String roadTypeName,
            Map<String, String> wayTags,
            List<Map<String, String>> relationTags,
            MatchMode mode,
            Set<String> stack
    ) {
        String stackKey = mode + ":" + roadTypeName;
        if (!stack.add(stackKey)) return false;

        Expr exact = compiledFilters.get(roadTypeName);
        if (exact != null && exact.eval(new EvalContext(wayTags, relationTags, mode, stack))) {
            stack.remove(stackKey);
            return true;
        }

        Expr relation = compiledRelationFilters.get(roadTypeName);
        if (relation != null && relationTags != null) {
            for (Map<String, String> relTags : relationTags) {
                if (relation.eval(new EvalContext(relTags, Collections.emptyList(), mode, stack))) {
                    stack.remove(stackKey);
                    return true;
                }
            }
        }

        if (mode == MatchMode.FUZZY) {
            Expr fuzzy = compiledFuzzyFilters.get(roadTypeName);
            if (fuzzy != null && fuzzy.eval(new EvalContext(wayTags, relationTags, mode, stack))) {
                stack.remove(stackKey);
                return true;
            }
        }

        stack.remove(stackKey);
        return false;
    }

    public static Integer parseMaxspeedKmh(String value) {
        if (value == null) return null;

        String s = value.trim().toLowerCase(Locale.ROOT);

        if (s.isEmpty()) return null;
        if (s.equals("walk")) return 5;
        if (s.equals("signals")) return null;
        if (s.equals("none")) return null;
        if (s.equals("variable")) return null;

        java.util.regex.Matcher m = Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(s);
        if (!m.find()) return null;

        double v = Double.parseDouble(m.group(1));

        if (s.contains("mph")) {
            v *= 1.609344;
        }

        return (int) Math.round(v);
    }

    private enum MatchMode {
        EXACT,
        FUZZY
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LegalDefaultSpeeds {
        public Map<String, RoadType> roadTypesByName = new HashMap<>();
        public Map<String, List<SpeedRule>> speedLimitsByCountryCode = new HashMap<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RoadType {
        public String filter;
        public String fuzzyFilter;
        public String relationFilter;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SpeedRule {
        public String name;
        public Map<String, String> tags = new HashMap<>();
    }

    private class EvalContext {
        final Map<String, String> tags;
        final List<Map<String, String>> relationTags;
        final MatchMode mode;
        final Set<String> stack;

        EvalContext(
                Map<String, String> tags,
                List<Map<String, String>> relationTags,
                MatchMode mode,
                Set<String> stack
        ) {
            this.tags = tags;
            this.relationTags = relationTags;
            this.mode = mode;
            this.stack = stack;
        }

        boolean roadType(String name) {
            return matchesRoadTypeInternal(name, tags, relationTags, mode, stack);
        }
    }

    private interface Expr {
        boolean eval(EvalContext ctx);
    }

    private static class BoolExpr implements Expr {
        final boolean value;

        BoolExpr(boolean value) {
            this.value = value;
        }

        @Override
        public boolean eval(EvalContext ctx) {
            return value;
        }
    }

    private static class RefExpr implements Expr {
        final String name;

        RefExpr(String name) {
            this.name = name;
        }

        @Override
        public boolean eval(EvalContext ctx) {
            return ctx.roadType(name);
        }
    }

    private static class PresentExpr implements Expr {
        final String key;

        PresentExpr(String key) {
            this.key = key;
        }

        @Override
        public boolean eval(EvalContext ctx) {
            String v = ctx.tags.get(key);
            return v != null && !v.isBlank() && !"no".equalsIgnoreCase(v);
        }
    }

    private static class NotExpr implements Expr {
        final Expr child;

        NotExpr(Expr child) {
            this.child = child;
        }

        @Override
        public boolean eval(EvalContext ctx) {
            return !child.eval(ctx);
        }
    }

    private static class AndExpr implements Expr {
        final Expr left;
        final Expr right;

        AndExpr(Expr left, Expr right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public boolean eval(EvalContext ctx) {
            return left.eval(ctx) && right.eval(ctx);
        }
    }

    private static class OrExpr implements Expr {
        final Expr left;
        final Expr right;

        OrExpr(Expr left, Expr right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public boolean eval(EvalContext ctx) {
            return left.eval(ctx) || right.eval(ctx);
        }
    }

    private static class CompareExpr implements Expr {
        final String key;
        final String op;
        final String expected;

        CompareExpr(String key, String op, String expected) {
            this.key = key;
            this.op = op;
            this.expected = expected;
        }

        @Override
        public boolean eval(EvalContext ctx) {
            String actual = ctx.tags.get(key);

            switch (op) {
                case "=":
                    return actual != null && actual.equals(expected);

                case "!=":
                    return actual == null || !actual.equals(expected);

                case "~":
                    return actual != null && Pattern.compile(expected).matcher(actual).matches();

                case "!~":
                    return actual == null || !Pattern.compile(expected).matcher(actual).matches();

                case ">":
                case ">=":
                case "<":
                case "<=":
                    if (actual == null) return false;

                    Double a = parseLeadingDouble(actual);
                    Double b = parseLeadingDouble(expected);

                    if (a == null || b == null) return false;

                    switch (op) {
                        case ">": return a > b;
                        case ">=": return a >= b;
                        case "<": return a < b;
                        case "<=": return a <= b;
                    }
            }

            return false;
        }

        private static Double parseLeadingDouble(String s) {
            if (s == null) return null;

            java.util.regex.Matcher m = Pattern.compile("-?\\d+(?:\\.\\d+)?").matcher(s.trim());
            if (!m.find()) return null;

            return Double.parseDouble(m.group());
        }
    }

    private static class FilterParser {
        private final List<Token> tokens;
        private int pos = 0;

        FilterParser(String input) {
            this.tokens = tokenize(input.replace('\u00A0', ' '));
        }

        Expr parse() {
            Expr expr = parseOr();
            expect(TokenType.EOF);
            return expr;
        }

        private Expr parseOr() {
            Expr left = parseAnd();

            while (matchWord("or")) {
                Expr right = parseAnd();
                left = new OrExpr(left, right);
            }

            return left;
        }

        private Expr parseAnd() {
            Expr left = parseUnary();

            while (matchWord("and")) {
                Expr right = parseUnary();
                left = new AndExpr(left, right);
            }

            return left;
        }

        private Expr parseUnary() {
            if (match(TokenType.NOT)) {
                return new NotExpr(parseUnary());
            }

            return parsePrimary();
        }

        private Expr parsePrimary() {
            if (match(TokenType.LPAREN)) {
                Expr e = parseOr();
                expect(TokenType.RPAREN);
                return e;
            }

            if (match(TokenType.LBRACE)) {
                StringBuilder name = new StringBuilder();

                while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
                    if (name.length() > 0) name.append(" ");
                    name.append(advance().text);
                }

                expect(TokenType.RBRACE);
                return new RefExpr(name.toString().trim());
            }

            Token key = expect(TokenType.WORD);

            if (check(TokenType.OP)) {
                String op = advance().text;
                Token value = expectAny(TokenType.WORD, TokenType.STRING, TokenType.NUMBER);
                return new CompareExpr(key.text, op, value.text);
            }

            return new PresentExpr(key.text);
        }

        private boolean matchWord(String word) {
            if (check(TokenType.WORD) && peek().text.equalsIgnoreCase(word)) {
                advance();
                return true;
            }

            return false;
        }

        private boolean match(TokenType type) {
            if (check(type)) {
                advance();
                return true;
            }

            return false;
        }

        private boolean check(TokenType type) {
            return peek().type == type;
        }

        private Token peek() {
            return tokens.get(pos);
        }

        private Token advance() {
            return tokens.get(pos++);
        }

        private Token expect(TokenType type) {
            if (!check(type)) {
                throw new IllegalArgumentException("Expected " + type + " but got " + peek());
            }

            return advance();
        }

        private Token expectAny(TokenType... types) {
            for (TokenType type : types) {
                if (check(type)) return advance();
            }

            throw new IllegalArgumentException("Unexpected token: " + peek());
        }

        private static List<Token> tokenize(String input) {
            List<Token> out = new ArrayList<>();
            int i = 0;

            while (i < input.length()) {
                char c = input.charAt(i);

                if (Character.isWhitespace(c)) {
                    i++;
                    continue;
                }

                if (c == '(') {
                    out.add(new Token(TokenType.LPAREN, "("));
                    i++;
                    continue;
                }

                if (c == ')') {
                    out.add(new Token(TokenType.RPAREN, ")"));
                    i++;
                    continue;
                }

                if (c == '{') {
                    out.add(new Token(TokenType.LBRACE, "{"));
                    i++;
                    continue;
                }

                if (c == '}') {
                    out.add(new Token(TokenType.RBRACE, "}"));
                    i++;
                    continue;
                }

                if (c == '!') {
                    if (i + 1 < input.length() && input.charAt(i + 1) == '=') {
                        out.add(new Token(TokenType.OP, "!="));
                        i += 2;
                    } else if (i + 1 < input.length() && input.charAt(i + 1) == '~') {
                        out.add(new Token(TokenType.OP, "!~"));
                        i += 2;
                    } else {
                        out.add(new Token(TokenType.NOT, "!"));
                        i++;
                    }
                    continue;
                }

                if (c == '=' || c == '~' || c == '<' || c == '>') {
                    if ((c == '<' || c == '>') && i + 1 < input.length() && input.charAt(i + 1) == '=') {
                        out.add(new Token(TokenType.OP, input.substring(i, i + 2)));
                        i += 2;
                    } else {
                        out.add(new Token(TokenType.OP, String.valueOf(c)));
                        i++;
                    }
                    continue;
                }

                if (c == '"') {
                    int j = i + 1;
                    StringBuilder sb = new StringBuilder();

                    while (j < input.length()) {
                        char d = input.charAt(j);

                        if (d == '\\' && j + 1 < input.length()) {
                            sb.append(input.charAt(j + 1));
                            j += 2;
                            continue;
                        }

                        if (d == '"') break;

                        sb.append(d);
                        j++;
                    }

                    if (j >= input.length()) {
                        throw new IllegalArgumentException("Unterminated string in filter: " + input);
                    }

                    out.add(new Token(TokenType.STRING, sb.toString()));
                    i = j + 1;
                    continue;
                }

                int j = i;
                while (j < input.length()) {
                    char d = input.charAt(j);

                    if (
                            Character.isWhitespace(d) ||
                            d == '(' || d == ')' ||
                            d == '{' || d == '}' ||
                            d == '=' || d == '~' ||
                            d == '<' || d == '>' ||
                            d == '!' || d == '"'
                    ) {
                        break;
                    }

                    j++;
                }

                String text = input.substring(i, j);
                TokenType type = text.matches("-?\\d+(?:\\.\\d+)?") ? TokenType.NUMBER : TokenType.WORD;
                out.add(new Token(type, text));
                i = j;
            }

            out.add(new Token(TokenType.EOF, ""));
            return out;
        }

        private enum TokenType {
            WORD,
            STRING,
            NUMBER,
            OP,
            NOT,
            LPAREN,
            RPAREN,
            LBRACE,
            RBRACE,
            EOF
        }

        private static class Token {
            final TokenType type;
            final String text;

            Token(TokenType type, String text) {
                this.type = type;
                this.text = text;
            }

            @Override
            public String toString() {
                return type + "(" + text + ")";
            }
        }
    }
}