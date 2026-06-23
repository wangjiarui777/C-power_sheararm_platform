package com.ruoyi.mock;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.ruoyi.mock.VibrationSimulatorApplication.BufferedFrame;
import com.ruoyi.mock.VibrationSimulatorApplication.DiskFrameBuffer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiskFrameBufferTest
{
    @TempDir
    Path tempDir;

    @Test
    void persistsInSequenceOrderAndDeletesOnlyAfterAcknowledgement() throws Exception
    {
        DiskFrameBuffer buffer = new DiskFrameBuffer(tempDir, 1024 * 1024);
        Path second = buffer.store(new BufferedFrame("frame-2", 2, 2000, "payload-2"));
        Path first = buffer.store(new BufferedFrame("frame-1", 1, 1000, "payload-1"));

        assertEquals("frame-1", buffer.read(buffer.pending().get(0)).frameId());
        assertEquals(2L, buffer.latestSequence());
        assertTrue(second.toFile().isFile());

        buffer.acknowledge(first);

        assertFalse(first.toFile().exists());
        assertTrue(second.toFile().exists());
    }
}
