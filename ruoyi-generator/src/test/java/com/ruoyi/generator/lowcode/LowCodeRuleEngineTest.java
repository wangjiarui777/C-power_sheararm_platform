package com.ruoyi.generator.lowcode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LowCodeRuleEngineTest
{
    private final LowCodeRuleEngine engine = new LowCodeRuleEngine();

    @Test
    void evaluatesNestedLogicAndFieldReferences()
    {
        Map<String, Object> expression = Map.of("op", "and", "args", List.of(
            Map.of("op", "gte", "args", List.of(Map.of("field", "confidence"), 90)),
            Map.of("op", "in", "args", List.of(Map.of("field", "risk"), List.of("中", "高")))));
        assertTrue(engine.matches(expression, Map.of("confidence", 92.5, "risk", "高")));
        assertFalse(engine.matches(expression, Map.of("confidence", 88, "risk", "高")));
    }

    @Test
    void calculatesNumbersWithoutRunningScripts()
    {
        Object result = engine.evaluate(Map.of("op", "multiply", "args", List.of(
            Map.of("field", "scale"), Map.of("op", "add", "args", List.of(Map.of("field", "value"), 2)))),
            Map.of("scale", 1.5, "value", 4));
        assertEquals(new BigDecimal("9.0"), result);
        assertThrows(IllegalArgumentException.class,
            () -> engine.evaluate(Map.of("op", "script", "args", List.of("System.exit(0)")), Map.of()));
    }

    @Test
    void boundsRegexLength()
    {
        String tooLong = "a".repeat(257);
        assertThrows(IllegalArgumentException.class,
            () -> engine.matches(Map.of("op", "matches", "args", List.of("abc", tooLong)), Map.of()));
    }
}
