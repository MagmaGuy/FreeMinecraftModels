package com.magmaguy.freeminecraftmodels.particles.molang;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.random.RandomGenerator;

/**
 * Compiles the Molang subset used by Snowstorm particle files: arithmetic, comparisons, logic,
 * conditionals, null coalescing, variable assignment, {@code return} and Bedrock's {@code math.*}
 * functions (trigonometry in degrees). A program with {@code ;} separators returns the value of
 * its {@code return} statement, or 0 without one, as on Bedrock.
 */
public final class MolangParser {
    private static final String[] OPERATORS = {
            "??", "&&", "||", "==", "!=", "<=", ">=", "->",
            "<", ">", "+", "-", "*", "/", "!", "?", ":", "(", ")", ",", ";", "=", "{", "}", "[", "]"};

    private enum Kind {NUMBER, STRING, NAME, OPERATOR, END}

    private record Token(Kind kind, String text, int offset) {
    }

    private sealed interface Statement permits ReturnStatement, AssignStatement, ExpressionStatement {
    }

    private record ReturnStatement(MolangExpression value) implements Statement {
    }

    private record AssignStatement(String name, MolangExpression value) implements Statement {
    }

    private record ExpressionStatement(MolangExpression value) implements Statement {
    }

    private final String source;
    private final List<Token> tokens;
    private int index;

    private MolangParser(String source) {
        this.source = source;
        this.tokens = tokenize(source);
    }

    public static MolangExpression parse(String source) {
        if (source == null || source.isBlank()) return MolangExpression.ZERO;
        return new MolangParser(source).program();
    }

    private MolangExpression program() {
        List<Statement> statements = new ArrayList<>();
        boolean complex = false;
        while (peek().kind != Kind.END) {
            if (matchOperator(";")) {
                complex = true;
                continue;
            }
            statements.add(statement());
            if (peek().kind != Kind.END) {
                expectOperator(";");
                complex = true;
            }
        }
        if (!complex && statements.size() == 1 && statements.getFirst() instanceof ExpressionStatement expression)
            return expression.value();
        Statement[] program = statements.toArray(Statement[]::new);
        return context -> {
            for (Statement statement : program) {
                switch (statement) {
                    case ReturnStatement returnStatement -> {
                        return returnStatement.value().evaluate(context);
                    }
                    case AssignStatement assign -> context.setCanonical(assign.name(), assign.value().evaluate(context));
                    case ExpressionStatement expression -> expression.value().evaluate(context);
                }
            }
            return 0;
        };
    }

    private Statement statement() {
        Token token = peek();
        if (token.kind == Kind.NAME && token.text.equalsIgnoreCase("return")) {
            index++;
            return new ReturnStatement(expression());
        }
        if (token.kind == Kind.NAME && tokens.get(index + 1).kind == Kind.OPERATOR
                && tokens.get(index + 1).text.equals("=")) {
            String name = MolangContext.canonical(token.text);
            if (!name.startsWith("variable.") && !name.startsWith("temp."))
                throw new MolangParseException("Only variable. and temp. names can be assigned", source, token.offset);
            index += 2;
            return new AssignStatement(name, expression());
        }
        return new ExpressionStatement(expression());
    }

    private MolangExpression expression() {
        MolangExpression condition = binary(0);
        if (!matchOperator("?")) return condition;
        MolangExpression whenTrue = expression();
        MolangExpression whenFalse = matchOperator(":") ? expression() : MolangExpression.ZERO;
        return context -> condition.evaluate(context) != 0
                ? whenTrue.evaluate(context) : whenFalse.evaluate(context);
    }

    private static final String[][] LEVELS = {
            {"??"}, {"||"}, {"&&"}, {"==", "!="}, {"<", "<=", ">", ">="}, {"+", "-"}, {"*", "/"}};

    private MolangExpression binary(int level) {
        if (level == LEVELS.length) return unary();
        MolangExpression left = binary(level + 1);
        while (true) {
            Token token = peek();
            String operator = token.kind == Kind.OPERATOR ? matchingOperator(token.text, LEVELS[level]) : null;
            if (operator == null) return left;
            index++;
            MolangExpression right = binary(level + 1);
            left = combine(operator, left, right);
        }
    }

    private static String matchingOperator(String text, String[] candidates) {
        for (String candidate : candidates) if (candidate.equals(text)) return candidate;
        return null;
    }

    private MolangExpression combine(String operator, MolangExpression left, MolangExpression right) {
        return switch (operator) {
            case "??" -> left instanceof VariableRead read
                    ? context -> context.hasCanonical(read.name()) ? read.evaluate(context) : right.evaluate(context)
                    : left;
            case "||" -> context -> left.evaluate(context) != 0 || right.evaluate(context) != 0 ? 1 : 0;
            case "&&" -> context -> left.evaluate(context) != 0 && right.evaluate(context) != 0 ? 1 : 0;
            case "==" -> context -> left.evaluate(context) == right.evaluate(context) ? 1 : 0;
            case "!=" -> context -> left.evaluate(context) != right.evaluate(context) ? 1 : 0;
            case "<" -> context -> left.evaluate(context) < right.evaluate(context) ? 1 : 0;
            case "<=" -> context -> left.evaluate(context) <= right.evaluate(context) ? 1 : 0;
            case ">" -> context -> left.evaluate(context) > right.evaluate(context) ? 1 : 0;
            case ">=" -> context -> left.evaluate(context) >= right.evaluate(context) ? 1 : 0;
            case "+" -> context -> left.evaluate(context) + right.evaluate(context);
            case "-" -> context -> left.evaluate(context) - right.evaluate(context);
            case "*" -> context -> left.evaluate(context) * right.evaluate(context);
            case "/" -> context -> divide(left.evaluate(context), right.evaluate(context));
            default -> throw new IllegalStateException(operator);
        };
    }

    private MolangExpression unary() {
        if (matchOperator("-")) {
            MolangExpression operand = unary();
            return context -> -operand.evaluate(context);
        }
        if (matchOperator("!")) {
            MolangExpression operand = unary();
            return context -> operand.evaluate(context) == 0 ? 1 : 0;
        }
        if (matchOperator("+")) return unary();
        return primary();
    }

    private record VariableRead(String name) implements MolangExpression {
        @Override
        public double evaluate(MolangContext context) {
            return context.getCanonical(name);
        }
    }

    private MolangExpression primary() {
        Token token = peek();
        switch (token.kind) {
            case NUMBER -> {
                index++;
                double value = Double.parseDouble(token.text);
                return MolangExpression.constant(value);
            }
            case STRING -> {
                index++;
                return MolangExpression.ZERO;
            }
            case OPERATOR -> {
                if (matchOperator("(")) {
                    MolangExpression inner = expression();
                    expectOperator(")");
                    return inner;
                }
                throw new MolangParseException("Unexpected '" + token.text + "'", source, token.offset);
            }
            case NAME -> {
                index++;
                String name = MolangContext.canonical(token.text);
                if (name.equals("true")) return MolangExpression.constant(1);
                if (name.equals("false")) return MolangExpression.constant(0);
                List<MolangExpression> arguments = null;
                if (matchOperator("(")) {
                    arguments = new ArrayList<>();
                    if (!matchOperator(")")) {
                        do arguments.add(expression());
                        while (matchOperator(","));
                        expectOperator(")");
                    }
                }
                if (name.startsWith("math.")) return function(name.substring(5), arguments, token);
                // Queries and context values FMM does not provide read as 0, as Bedrock does for
                // unknown variables; arguments to unknown queries are ignored.
                return new VariableRead(name);
            }
            default -> throw new MolangParseException("Unexpected end of expression", source, token.offset);
        }
    }

    private MolangExpression function(String name, List<MolangExpression> arguments, Token token) {
        MolangExpression[] args = arguments == null ? new MolangExpression[0] : arguments.toArray(MolangExpression[]::new);
        if (name.equals("pi")) return MolangExpression.constant(Math.PI);
        int arity = switch (name) {
            case "sin", "cos", "asin", "acos", "atan", "abs", "ceil", "floor", "round", "trunc", "sqrt", "exp", "ln",
                 "hermite_blend" -> 1;
            case "atan2", "pow", "mod", "min", "max", "random", "random_integer" -> 2;
            case "clamp", "lerp", "lerprotate", "die_roll", "die_roll_integer" -> 3;
            default -> throw new MolangParseException("Unsupported function math." + name, source, token.offset);
        };
        if (args.length != arity)
            throw new MolangParseException("math." + name + " expects " + arity + " arguments", source, token.offset);
        return switch (name) {
            case "sin" -> context -> Math.sin(Math.toRadians(args[0].evaluate(context)));
            case "cos" -> context -> Math.cos(Math.toRadians(args[0].evaluate(context)));
            case "asin" -> context -> Math.toDegrees(Math.asin(args[0].evaluate(context)));
            case "acos" -> context -> Math.toDegrees(Math.acos(args[0].evaluate(context)));
            case "atan" -> context -> Math.toDegrees(Math.atan(args[0].evaluate(context)));
            case "atan2" -> context -> Math.toDegrees(Math.atan2(args[0].evaluate(context), args[1].evaluate(context)));
            case "abs" -> context -> Math.abs(args[0].evaluate(context));
            case "ceil" -> context -> Math.ceil(args[0].evaluate(context));
            case "floor" -> context -> Math.floor(args[0].evaluate(context));
            case "round" -> context -> Math.round(args[0].evaluate(context));
            case "trunc" -> context -> (double) (long) args[0].evaluate(context);
            case "sqrt" -> context -> Math.sqrt(args[0].evaluate(context));
            case "exp" -> context -> Math.exp(args[0].evaluate(context));
            case "ln" -> context -> Math.log(args[0].evaluate(context));
            case "hermite_blend" -> context -> {
                double t = args[0].evaluate(context);
                return 3 * t * t - 2 * t * t * t;
            };
            case "pow" -> context -> Math.pow(args[0].evaluate(context), args[1].evaluate(context));
            case "mod" -> context -> {
                double divisor = args[1].evaluate(context);
                return divisor == 0 ? 0 : args[0].evaluate(context) % divisor;
            };
            case "min" -> context -> Math.min(args[0].evaluate(context), args[1].evaluate(context));
            case "max" -> context -> Math.max(args[0].evaluate(context), args[1].evaluate(context));
            case "random" -> context -> random(context.random(), args[0].evaluate(context), args[1].evaluate(context));
            case "random_integer" -> context -> randomInteger(context.random(),
                    args[0].evaluate(context), args[1].evaluate(context));
            case "clamp" -> context -> Math.max(args[1].evaluate(context),
                    Math.min(args[2].evaluate(context), args[0].evaluate(context)));
            case "lerp" -> context -> {
                double start = args[0].evaluate(context);
                return start + (args[1].evaluate(context) - start) * args[2].evaluate(context);
            };
            case "lerprotate" -> context -> {
                double start = args[0].evaluate(context);
                double delta = ((args[1].evaluate(context) - start) % 360 + 540) % 360 - 180;
                return start + delta * args[2].evaluate(context);
            };
            case "die_roll" -> context -> {
                long count = (long) args[0].evaluate(context);
                double low = args[1].evaluate(context), high = args[2].evaluate(context), sum = 0;
                for (long i = 0; i < count; i++) sum += random(context.random(), low, high);
                return sum;
            };
            case "die_roll_integer" -> context -> {
                long count = (long) args[0].evaluate(context);
                double low = args[1].evaluate(context), high = args[2].evaluate(context), sum = 0;
                for (long i = 0; i < count; i++) sum += randomInteger(context.random(), low, high);
                return sum;
            };
            default -> throw new IllegalStateException(name);
        };
    }

    private static double divide(double dividend, double divisor) {
        return divisor == 0 ? 0 : dividend / divisor;
    }

    private static double random(RandomGenerator random, double low, double high) {
        return low + random.nextDouble() * (high - low);
    }

    private static double randomInteger(RandomGenerator random, double low, double high) {
        long min = Math.round(Math.min(low, high));
        long max = Math.round(Math.max(low, high));
        return min + random.nextLong(max - min + 1);
    }

    private Token peek() {
        return tokens.get(index);
    }

    private boolean matchOperator(String operator) {
        Token token = peek();
        if (token.kind == Kind.OPERATOR && token.text.equals(operator)) {
            index++;
            return true;
        }
        return false;
    }

    private void expectOperator(String operator) {
        Token token = peek();
        if (!matchOperator(operator))
            throw new MolangParseException("Expected '" + operator + "'", source, token.offset);
    }

    private List<Token> tokenize(String text) {
        List<Token> result = new ArrayList<>();
        int position = 0;
        while (position < text.length()) {
            char character = text.charAt(position);
            if (Character.isWhitespace(character)) {
                position++;
                continue;
            }
            int start = position;
            if (Character.isDigit(character) || (character == '.' && position + 1 < text.length()
                    && Character.isDigit(text.charAt(position + 1)))) {
                while (position < text.length()
                        && (Character.isDigit(text.charAt(position)) || text.charAt(position) == '.')) position++;
                String number = text.substring(start, position);
                if (position < text.length() && Character.toLowerCase(text.charAt(position)) == 'f') position++;
                result.add(new Token(Kind.NUMBER, number, start));
                continue;
            }
            if (Character.isLetter(character) || character == '_') {
                while (position < text.length() && (Character.isLetterOrDigit(text.charAt(position))
                        || text.charAt(position) == '_' || text.charAt(position) == '.')) position++;
                result.add(new Token(Kind.NAME, text.substring(start, position).toLowerCase(Locale.ROOT), start));
                continue;
            }
            if (character == '\'') {
                int end = text.indexOf('\'', position + 1);
                if (end < 0) throw new MolangParseException("Unterminated string", text, start);
                result.add(new Token(Kind.STRING, text.substring(start + 1, end), start));
                position = end + 1;
                continue;
            }
            String operator = null;
            for (String candidate : OPERATORS) {
                if (text.startsWith(candidate, position)) {
                    operator = candidate;
                    break;
                }
            }
            if (operator == null) throw new MolangParseException("Unexpected character '" + character + "'", text, start);
            result.add(new Token(Kind.OPERATOR, operator, start));
            position += operator.length();
        }
        result.add(new Token(Kind.END, "", text.length()));
        return result;
    }
}
