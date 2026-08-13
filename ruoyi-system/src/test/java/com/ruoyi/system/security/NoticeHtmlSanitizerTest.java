package com.ruoyi.system.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class NoticeHtmlSanitizerTest
{
    private final NoticeHtmlSanitizer sanitizer = new NoticeHtmlSanitizer();

    @Test
    void removesScriptEventsAndDangerousProtocols()
    {
        String clean = sanitizer.sanitize("<p onclick=alert(1)>ok<script>alert(1)</script>"
                + "<a href='javascript:alert(2)'>bad</a><svg onload=alert(3)></svg></p>");
        assertTrue(clean.contains("<p>ok"));
        assertFalse(clean.contains("script"));
        assertFalse(clean.contains("onclick"));
        assertFalse(clean.contains("javascript:"));
        assertFalse(clean.contains("svg"));
    }

    @Test
    void keepsHttpsLinksAndControlledAttachmentImagesOnly()
    {
        String clean = sanitizer.sanitize("<a href='https://example.com/a'>safe</a>"
                + "<img src='/attachments/5af2b8bb-3861-47d8-a58e-66bb581578af/content'>"
                + "<img src='https://cdn.example.com/a.png'><img src='http://evil/a.png'>");
        assertTrue(clean.contains("https://example.com/a"));
        assertTrue(clean.contains("/attachments/5af2b8bb-3861-47d8-a58e-66bb581578af/content"));
        assertFalse(clean.contains("cdn.example.com"));
        assertFalse(clean.contains("http://evil"));
    }
}
