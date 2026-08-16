package com.ruoyi.web.controller.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class SysNoticePermissionContractTest
{
    @Test
    void noticeDetailRequiresQueryPermission() throws Exception
    {
        PreAuthorize guard = SysNoticeController.class.getMethod("getInfo", Long.class)
            .getAnnotation(PreAuthorize.class);
        assertNotNull(guard);
        assertEquals("@ss.hasPermi('system:notice:query')", guard.value());
    }
}
