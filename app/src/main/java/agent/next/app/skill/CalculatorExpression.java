package agent.next.app.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal calculator expression parser for visual tap sequences. */
public final class CalculatorExpression {

    private static final Pattern EXPRESSION = Pattern.compile(
            "(\\d{1,9})\\s*(乘以|乘|加|减|除以|[×xX*+\\-÷/])\\s*(\\d{1,9})");

    public record Calculation(String expression, long result, List<String> buttons) {
    }

    private CalculatorExpression() {
    }

    public static Optional<Calculation> parse(String goal, String intent) {
        String text = (goal == null ? "" : goal) + " " + (intent == null ? "" : intent);
        Matcher matcher = EXPRESSION.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        long left = Long.parseLong(matcher.group(1));
        long right = Long.parseLong(matcher.group(3));
        String operator = switch (matcher.group(2)) {
            case "乘", "×", "x", "X", "*" -> "×";
            case "加", "+" -> "+";
            case "减", "-" -> "-";
            case "除以", "÷", "/" -> "÷";
            default -> "";
        };
        if (operator.isEmpty()) {
            return Optional.empty();
        }
        long result;
        try {
            result = switch (operator) {
                case "×" -> Math.multiplyExact(left, right);
                case "+" -> Math.addExact(left, right);
                case "-" -> Math.subtractExact(left, right);
                default -> {
                    if (right == 0) {
                        yield Long.MIN_VALUE;
                    }
                    yield left / right;
                }
            };
        } catch (ArithmeticException overflow) {
            return Optional.empty();
        }
        if (result == Long.MIN_VALUE || operator.equals("÷") && left % right != 0) {
            return Optional.empty();
        }
        String expression = left + operator + right;
        List<String> buttons = new ArrayList<>();
        buttons.add("C（清除）");
        for (int index = 0; index < expression.length(); index++) {
            buttons.add(Character.toString(expression.charAt(index)));
        }
        buttons.add("=");
        return Optional.of(new Calculation(expression, result, List.copyOf(buttons)));
    }
}
