package com.ruoyi.lowcode.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

class OutboundTargetValidatorTest
{
    private final OutboundTargetValidator validator = new OutboundTargetValidator();

    @Test
    void rejectsNonHttpsLocalPrivateAndMetadataDestinations()
    {
        assertThrows(IllegalArgumentException.class, () -> validator.requirePublicHttps("http://example.com"));
        assertThrows(IllegalArgumentException.class, () -> validator.requirePublicHttps("https://127.0.0.1"));
        assertThrows(IllegalArgumentException.class, () -> validator.requirePublicHttps("https://10.0.0.1"));
        assertThrows(IllegalArgumentException.class, () -> validator.requirePublicHttps("https://169.254.169.254"));
        assertThrows(IllegalArgumentException.class, () -> validator.requirePublicHttps("https://[::1]"));
    }

    @Test
    void acceptsPublicHttpsRoot()
    {
        assertEquals("https", validator.requirePublicHttps("https://example.com").getScheme());
    }
}
