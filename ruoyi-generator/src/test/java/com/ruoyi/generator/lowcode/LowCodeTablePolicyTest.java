package com.ruoyi.generator.lowcode;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class LowCodeTablePolicyTest
{
    private final LowCodeTablePolicy policy = new LowCodeTablePolicy(new JdbcTemplate());

    @Test
    void hardDeniesSystemCredentialAndMetadataTables()
    {
        assertTrue(policy.isHardDenied("sys_user"));
        assertTrue(policy.isHardDenied("QRTZ_JOB_DETAILS"));
        assertTrue(policy.isHardDenied("lc_project"));
        assertTrue(policy.isHardDenied("credentials"));
        assertFalse(policy.isHardDenied("phm_diagnosis_binding"));
    }
}
