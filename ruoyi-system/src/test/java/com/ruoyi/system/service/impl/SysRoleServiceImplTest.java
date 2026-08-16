package com.ruoyi.system.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.system.domain.SysRoleMenu;
import com.ruoyi.system.mapper.SysRoleMapper;
import com.ruoyi.system.mapper.SysRoleMenuMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class SysRoleServiceImplTest
{
    private SysRoleMapper roleMapper;
    private SysRoleMenuMapper roleMenuMapper;
    private SysRoleServiceImpl service;

    @BeforeEach
    void setUp()
    {
        roleMapper = mock(SysRoleMapper.class);
        roleMenuMapper = mock(SysRoleMenuMapper.class);
        service = new SysRoleServiceImpl();
        ReflectionTestUtils.setField(service, "roleMapper", roleMapper);
        ReflectionTestUtils.setField(service, "roleMenuMapper", roleMenuMapper);
        when(roleMenuMapper.batchRoleMenu(anyList())).thenReturn(3);
    }

    @Test
    void insertPersistsExactlySubmittedMenuIds()
    {
        SysRole role = role(8L, 11L, 22L, 33L);

        service.insertRole(role);

        assertSubmittedMenus(8L, List.of(11L, 22L, 33L));
    }

    @Test
    void updateReplacesRelationsWithExactlySubmittedMenuIds()
    {
        SysRole role = role(8L, 41L, 42L);

        service.updateRole(role);

        verify(roleMapper).updateRole(role);
        verify(roleMenuMapper).deleteRoleMenuByRoleId(8L);
        assertSubmittedMenus(8L, List.of(41L, 42L));
    }

    @Test
    void emptySelectionDoesNotRestoreAnyImplicitPermission()
    {
        SysRole role = role(8L);

        assertThat(service.updateRole(role)).isEqualTo(1);

        verify(roleMenuMapper).deleteRoleMenuByRoleId(8L);
        verify(roleMenuMapper, org.mockito.Mockito.never()).batchRoleMenu(anyList());
    }

    private SysRole role(Long roleId, Long... menuIds)
    {
        SysRole role = new SysRole();
        role.setRoleId(roleId);
        role.setMenuIds(menuIds);
        return role;
    }

    private void assertSubmittedMenus(Long roleId, List<Long> expected)
    {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SysRoleMenu>> captor = ArgumentCaptor.forClass(List.class);
        verify(roleMenuMapper).batchRoleMenu(captor.capture());
        assertThat(captor.getValue()).extracting(SysRoleMenu::getRoleId).containsOnly(roleId);
        assertThat(captor.getValue()).extracting(SysRoleMenu::getMenuId).containsExactlyElementsOf(expected);
    }
}
