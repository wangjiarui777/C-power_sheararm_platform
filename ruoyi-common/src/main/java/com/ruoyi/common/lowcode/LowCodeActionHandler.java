package com.ruoyi.common.lowcode;

import java.util.Map;

/**
 * A server-side, explicitly registered low-code action.
 *
 * <p>Implementations must use a stable code and must never evaluate user supplied
 * class names, scripts or URLs. The generator module discovers these handlers
 * through Spring and only invokes codes present in a published metadata version.</p>
 */
public interface LowCodeActionHandler
{
    String code();

    Map<String, Object> execute(Map<String, Object> input, LowCodeActionContext context) throws Exception;
}
