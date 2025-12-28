package examples.fuzzapps;

import java.util.ArrayDeque;
import java.util.Deque;

public final class SimpleCalculator {
    private SimpleCalculator() {}

    // Evaluate an expression with + - * /, parentheses, and unary minus.
    public static int eval(String expr) {
        if (expr == null) throw new IllegalArgumentException("null expr");
        Deque<Integer> nums = new ArrayDeque<>();
        Deque<Character> ops = new ArrayDeque<>();
        int n = expr.length();
        int i = 0;
        boolean expectUnary = true; // at start or after '('
        while (i < n) {
            char c = expr.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (Character.isDigit(c) || (c == '-' && expectUnary && i + 1 < n && Character.isDigit(expr.charAt(i + 1)))) {
                int val = 0;
                boolean neg = false;
                if (c == '-') { neg = true; i++; }
                while (i < n && Character.isDigit(expr.charAt(i))) {
                    val = val * 10 + (expr.charAt(i) - '0');
                    i++;
                }
                nums.push(neg ? -val : val);
                expectUnary = false;
                continue;
            }
            // Handle unary minus before '(': treat as 0 - ( ... )
            if (c == '-' && expectUnary && i + 1 < n && expr.charAt(i + 1) == '(') {
                nums.push(0);
                while (!ops.isEmpty() && precedence(ops.peek()) >= precedence('-')) {
                    apply(nums, ops.pop());
                }
                ops.push('-');
                i++;
                expectUnary = true;
                continue;
            }
            if (c == '(') {
                ops.push(c);
                i++;
                expectUnary = true;
                continue;
            }
            if (c == ')') {
                while (!ops.isEmpty() && ops.peek() != '(') {
                    apply(nums, ops.pop());
                }
                if (ops.isEmpty() || ops.pop() != '(') {
                    throw new IllegalStateException("mismatched parenthesis");
                }
                i++;
                expectUnary = false;
                continue;
            }
            if (c == '+' || c == '-' || c == '*' || c == '/') {
                while (!ops.isEmpty() && precedence(ops.peek()) >= precedence(c)) {
                    apply(nums, ops.pop());
                }
                ops.push(c);
                i++;
                expectUnary = true;
                continue;
            }
            throw new IllegalArgumentException("bad char: " + c);
        }
        while (!ops.isEmpty()) {
            char op = ops.pop();
            if (op == '(') throw new IllegalStateException("mismatched parenthesis");
            apply(nums, op);
        }
        if (nums.size() != 1) throw new IllegalStateException("bad expr");
        return nums.pop();
    }

    private static int precedence(char c) {
        if (c == '+' || c == '-') return 1;
        if (c == '*' || c == '/') return 2;
        return 0; // '(' and others
    }

    private static void apply(Deque<Integer> nums, char op) {
        int b = nums.pop();
        int a = nums.pop();
        switch (op) {
            case '+': nums.push(a + b); break;
            case '-': nums.push(a - b); break;
            case '*': nums.push(a * b); break;
            case '/': nums.push(a / b); break; // may throw ArithmeticException
            default: throw new IllegalArgumentException("op");
        }
    }
}
