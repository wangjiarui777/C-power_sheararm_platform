package com.ruoyi.sensor.domain.constant;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhmAlarmSourceTest
{
    @Test
    void acceptsOnlyBusinessAndModelSources()
    {
        assertTrue(PhmAlarmSource.isValid("business"));
        assertTrue(PhmAlarmSource.isValid("MODEL"));
        assertFalse(PhmAlarmSource.isValid("diagnosis"));
        assertEquals(PhmAlarmSource.MODEL, PhmAlarmSource.normalize("model"));
    }
}
