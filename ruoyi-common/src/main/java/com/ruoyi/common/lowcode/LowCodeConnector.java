package com.ruoyi.common.lowcode;

import java.util.Map;

/** SPI for controlled connectors; implementations are registered by stable type. */
public interface LowCodeConnector
{
    String type();

    Map<String, Object> invoke(Map<String, Object> connectorConfig, Map<String, Object> request) throws Exception;
}
