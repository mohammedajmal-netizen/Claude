package app.truelog.camera;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.nio.ByteBuffer;

public class CopyPlaneTest {

    @Test public void honoursDestinationStride() {
        ByteBuffer src = ByteBuffer.allocateDirect(12);
        for (int i = 0; i < 12; i++) src.put(i, (byte) (i + 1));
        ByteBuffer dst = ByteBuffer.allocateDirect(24);
        AvRecorder.copyPlane(src, 4, 3, dst, 8);
        assertEquals(1, dst.get(0));
        assertEquals(4, dst.get(3));
        assertEquals(0, dst.get(4));
        assertEquals(5, dst.get(8));
        assertEquals(9, dst.get(16));
        assertEquals(12, dst.get(19));
    }

    @Test public void shortFinalRowDoesNotOverrun() {
        // The U plane of a semi-planar image can end one sample before a full row.
        ByteBuffer src = ByteBuffer.allocateDirect(12);
        for (int i = 0; i < 12; i++) src.put(i, (byte) (i + 1));
        ByteBuffer dst = ByteBuffer.allocateDirect(10);
        AvRecorder.copyPlane(src, 4, 3, dst, 4);
        assertEquals(10, dst.get(9));
        assertEquals("source must be reusable afterwards", 12, src.remaining());
    }
}
