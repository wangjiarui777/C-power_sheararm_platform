package com.ruoyi.system.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.ruoyi.system.domain.SysUserDevice;
import com.ruoyi.system.mapper.SysUserDeviceMapper;

@ExtendWith(MockitoExtension.class)
class SysUserDeviceServiceImplTest
{
    @Mock
    private SysUserDeviceMapper mapper;

    @InjectMocks
    private SysUserDeviceServiceImpl service;

    @Test
    void replaceDeduplicatesAndReplacesRelations()
    {
        service.replaceUserDevices(7L, new Long[] { 21L, 22L, 21L, null }, "admin");

        verify(mapper).deleteByUserId(7L);
        ArgumentCaptor<List<SysUserDevice>> captor = ArgumentCaptor.forClass(List.class);
        verify(mapper).batchInsert(captor.capture());
        List<SysUserDevice> relations = captor.getValue();
        assertEquals(Arrays.asList(21L, 22L), relations.stream().map(SysUserDevice::getDeviceId).toList());
        assertEquals("admin", relations.get(0).getCreateBy());
    }

    @Test
    void replaceWithEmptyArrayClearsRelations()
    {
        service.replaceUserDevices(7L, new Long[0], "admin");

        verify(mapper).deleteByUserId(7L);
        verify(mapper, never()).batchInsert(anyList());
    }

    @Test
    void deleteByUserIdsDelegatesCleanup()
    {
        service.deleteByUserIds(new Long[] { 7L, 8L });

        ArgumentCaptor<Long[]> captor = ArgumentCaptor.forClass(Long[].class);
        verify(mapper).deleteByUserIds(captor.capture());
        assertArrayEquals(new Long[] { 7L, 8L }, captor.getValue());
    }
}
