package com.ruoyi.generator.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class LowCodeControllerSecurityContractTest
{
    private static final List<Class<?>> CONTROLLERS = List.of(
        LowCodeProjectController.class,
        LowCodeConnectorController.class,
        LowCodeRuntimeController.class);

    @Test
    void everyLowCodeEndpointUsesRuoYiPermissionService()
    {
        for (Class<?> controller : CONTROLLERS)
        {
            PreAuthorize classGuard = controller.getAnnotation(PreAuthorize.class);
            for (Method method : controller.getDeclaredMethods())
            {
                if (!isEndpoint(method)) continue;
                PreAuthorize methodGuard = method.getAnnotation(PreAuthorize.class);
                PreAuthorize guard = methodGuard == null ? classGuard : methodGuard;
                assertNotNull(guard, () -> controller.getSimpleName() + "#" + method.getName());
                assertTrue(guard.value().contains("@ss.hasPermi"), guard.value());
            }
        }
    }

    @Test
    void pipelineTestAndActivationHaveIndependentPermissions() throws Exception
    {
        assertEquals("@ss.hasPermi('tool:lowcode:test')",
            LowCodeProjectController.class.getMethod("pipelineTest", Long.class, java.util.Map.class)
                .getAnnotation(PreAuthorize.class).value());
        assertEquals("@ss.hasPermi('tool:lowcode:activate')",
            LowCodeProjectController.class.getMethod("pipelineActivate", Long.class)
                .getAnnotation(PreAuthorize.class).value());
        assertEquals("@ss.hasPermi('tool:lowcode:activate')",
            LowCodeProjectController.class.getMethod("pipelineDeactivate", Long.class)
                .getAnnotation(PreAuthorize.class).value());
    }

    @Test
    void createTableUsesMenuPermissionInsteadOfAdminRole() throws Exception
    {
        PreAuthorize guard = GenController.class
            .getMethod("createTableSave", String.class, String.class)
            .getAnnotation(PreAuthorize.class);
        assertNotNull(guard);
        assertEquals("@ss.hasPermi('tool:gen:edit')", guard.value());
    }

    private boolean isEndpoint(Method method)
    {
        return method.isAnnotationPresent(GetMapping.class)
            || method.isAnnotationPresent(PostMapping.class)
            || method.isAnnotationPresent(PutMapping.class)
            || method.isAnnotationPresent(PatchMapping.class)
            || method.isAnnotationPresent(DeleteMapping.class)
            || method.isAnnotationPresent(RequestMapping.class);
    }
}
