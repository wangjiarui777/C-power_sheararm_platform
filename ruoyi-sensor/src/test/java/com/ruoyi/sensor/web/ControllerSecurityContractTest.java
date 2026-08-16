package com.ruoyi.sensor.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class ControllerSecurityContractTest
{
    private static final List<Class<?>> CONTROLLERS = List.of(
        IndustrialMonitoringController.class,
        MonitoringController.class,
        DeviceVibrationDataController.class,
        DeviceTemperatureDataController.class,
        VibrationAnalysisController.class,
        VibrationBatchController.class,
        VibrationDiagnosisController.class,
        PhmController.class,
        WebSocketTicketController.class,
        ModelReleaseController.class,
        PhmAcquisitionChannelController.class,
        SensorIngestFileController.class
    );

    @Test
    void everyBusinessEndpointRequiresAuthorization()
    {
        for (Class<?> controller : CONTROLLERS)
        {
            PreAuthorize classGuard = controller.getAnnotation(PreAuthorize.class);
            for (Method method : controller.getDeclaredMethods())
            {
                if (!isEndpoint(method))
                {
                    continue;
                }
                assertTrue(classGuard != null || method.isAnnotationPresent(PreAuthorize.class),
                    () -> controller.getSimpleName() + "#" + method.getName() + " 缺少 @PreAuthorize");
                assertFalse(hasAnnotationNamed(method, "Anonymous"),
                    () -> controller.getSimpleName() + "#" + method.getName() + " 不得匿名开放");
            }
        }
    }

    @Test
    void interactiveEndpointsUseRuoYiPermissionExpressions()
    {
        for (Class<?> controller : CONTROLLERS)
        {
            PreAuthorize classGuard = controller.getAnnotation(PreAuthorize.class);
            for (Method method : controller.getDeclaredMethods())
            {
                if (!isEndpoint(method)) continue;
                PreAuthorize methodGuard = method.getAnnotation(PreAuthorize.class);
                String expression = methodGuard == null ? classGuard.value() : methodGuard.value();
                String endpoint = controller.getSimpleName() + "#" + method.getName();
                assertTrue(expression.contains("@ss.has"), () -> endpoint + " 未使用若依权限表达式");
            }
        }
    }

    private boolean isEndpoint(Method method)
    {
        return method.isAnnotationPresent(GetMapping.class)
            || method.isAnnotationPresent(PostMapping.class)
            || method.isAnnotationPresent(PutMapping.class)
            || method.isAnnotationPresent(DeleteMapping.class)
            || method.isAnnotationPresent(RequestMapping.class);
    }

    private boolean hasAnnotationNamed(Method method, String simpleName)
    {
        for (Annotation annotation : method.getAnnotations())
        {
            if (annotation.annotationType().getSimpleName().equals(simpleName))
            {
                return true;
            }
        }
        return false;
    }
}
