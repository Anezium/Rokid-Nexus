package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSurfaceStateTest {
    @Test
    fun `older decode finishing late is rejected after update`() {
        val state = ImageDecodeCoordinator<String>()
        val first = ImageDecodeKey("feed:main", 10, "photo-a")
        val second = ImageDecodeKey("feed:main", 11, "photo-b")

        assertNull(state.begin(first))
        assertNull(state.begin(second))
        assertTrue(state.complete(first, "bitmap-a") is ImageDecodeCompletion.Rejected)
        assertTrue(state.complete(second, "bitmap-b") is ImageDecodeCompletion.Accepted)
    }

    @Test
    fun `replacement detaches published bitmap`() {
        val state = ImageDecodeCoordinator<String>()
        val first = ImageDecodeKey("feed:main", 10, "photo-a")
        val second = ImageDecodeKey("feed:main", 11, "photo-b")
        state.begin(first)
        state.complete(first, "bitmap-a")

        assertEquals("bitmap-a", state.begin(second))
    }

    @Test
    fun `hide invalidates pending decode and detaches current bitmap`() {
        val state = ImageDecodeCoordinator<String>()
        val shown = ImageDecodeKey("feed:main", 10, "photo-a")
        state.begin(shown)
        state.complete(shown, "bitmap-a")
        assertEquals("bitmap-a", state.invalidate("feed:main"))
        assertTrue(state.complete(shown, "late") is ImageDecodeCompletion.Rejected)
    }

    @Test
    fun `failed decode cancels only its own pending key`() {
        val state = ImageDecodeCoordinator<String>()
        val failed = ImageDecodeKey("feed:main", 10, "photo-a")
        val newer = ImageDecodeKey("feed:main", 11, "photo-b")
        state.begin(failed)
        state.begin(newer)
        assertTrue(!state.cancel(failed))
        assertTrue(state.complete(newer, "bitmap-b") is ImageDecodeCompletion.Accepted)
    }
}
