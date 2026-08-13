package com.ruoyi.common.lowcode;

import java.util.Map;

/** Immutable invocation context passed to registered actions. */
public record LowCodeActionContext(
    String appCode,
    String actionCode,
    String event,
    String username,
    Long userId,
    Long deptId,
    String idempotencyKey,
    Map<String, Object> metadata)
{
}
