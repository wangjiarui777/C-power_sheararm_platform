package com.ruoyi.generator.lowcode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Safe, non-script expression evaluator for published low-code rules. */
@Component
public class LowCodeRuleEngine
{
    public Object evaluate(Object expression, Map<String, Object> data)
    {
        if (!(expression instanceof Map<?, ?> node)) return expression;
        if (node.containsKey("field")) return data.get(String.valueOf(node.get("field")));
        if (node.containsKey("value")) return node.get("value");
        String op = String.valueOf(node.get("op")).toLowerCase();
        List<?> args = node.get("args") instanceof List<?> list ? list : List.of();
        return switch (op)
        {
            case "and" -> args.stream().allMatch(item -> truthy(evaluate(item, data)));
            case "or" -> args.stream().anyMatch(item -> truthy(evaluate(item, data)));
            case "not" -> !truthy(arg(args, 0, data));
            case "eq" -> Objects.equals(normalize(arg(args, 0, data)), normalize(arg(args, 1, data)));
            case "ne" -> !Objects.equals(normalize(arg(args, 0, data)), normalize(arg(args, 1, data)));
            case "gt" -> compare(arg(args, 0, data), arg(args, 1, data)) > 0;
            case "gte" -> compare(arg(args, 0, data), arg(args, 1, data)) >= 0;
            case "lt" -> compare(arg(args, 0, data), arg(args, 1, data)) < 0;
            case "lte" -> compare(arg(args, 0, data), arg(args, 1, data)) <= 0;
            case "in" -> arg(args, 1, data) instanceof Collection<?> values
                && values.stream().anyMatch(item -> Objects.equals(normalize(arg(args, 0, data)), normalize(item)));
            case "contains" -> String.valueOf(arg(args, 0, data)).contains(String.valueOf(arg(args, 1, data)));
            case "matches" -> safeMatches(arg(args, 0, data), arg(args, 1, data));
            case "empty" -> isEmpty(arg(args, 0, data));
            case "length" -> length(arg(args, 0, data));
            case "add" -> number(arg(args, 0, data)).add(number(arg(args, 1, data)));
            case "subtract" -> number(arg(args, 0, data)).subtract(number(arg(args, 1, data)));
            case "multiply" -> number(arg(args, 0, data)).multiply(number(arg(args, 1, data)));
            case "divide" -> number(arg(args, 0, data)).divide(number(arg(args, 1, data)), 8, java.math.RoundingMode.HALF_UP);
            case "now" -> Instant.now().toString();
            default -> throw new IllegalArgumentException("不支持的规则操作符: " + op);
        };
    }

    public boolean matches(Object expression, Map<String, Object> data)
    {
        return truthy(evaluate(expression, data));
    }

    private Object arg(List<?> args, int index, Map<String, Object> data)
    {
        if (index >= args.size()) throw new IllegalArgumentException("规则参数不足");
        return evaluate(args.get(index), data);
    }

    private boolean safeMatches(Object value, Object regex)
    {
        String pattern = String.valueOf(regex);
        if (pattern.length() > 256) throw new IllegalArgumentException("正则表达式过长");
        return Pattern.compile(pattern).matcher(String.valueOf(value)).matches();
    }

    private boolean truthy(Object value)
    {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.doubleValue() != 0;
        return value != null && !String.valueOf(value).isBlank();
    }

    private boolean isEmpty(Object value)
    {
        return value == null || value instanceof String text && text.isBlank()
            || value instanceof Collection<?> collection && collection.isEmpty()
            || value instanceof Map<?, ?> map && map.isEmpty();
    }

    private int length(Object value)
    {
        if (value instanceof Collection<?> collection) return collection.size();
        if (value instanceof Map<?, ?> map) return map.size();
        return value == null ? 0 : String.valueOf(value).length();
    }

    private int compare(Object left, Object right)
    {
        if (left instanceof Number || right instanceof Number) return number(left).compareTo(number(right));
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    private BigDecimal number(Object value)
    {
        try { return new BigDecimal(String.valueOf(value)); }
        catch (Exception ex) { throw new IllegalArgumentException("规则值不是数字: " + value); }
    }

    private Object normalize(Object value)
    {
        if (value instanceof Number) return number(value).stripTrailingZeros();
        return value;
    }
}
