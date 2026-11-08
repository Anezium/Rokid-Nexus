package com.anezium.rokidbus.glasses.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageFitTest {
    @Test fun `a landscape picture takes the box width and is centred vertically`() {
        val fit = ImageFit.fit(512, 384, 444, 360)!!
        assertEquals(444, fit.width)
        assertEquals(333, fit.height)
        assertEquals(0, fit.left)
        assertEquals((360 - 333) / 2, fit.top)
    }

    @Test fun `a portrait picture takes the box height and is centred horizontally`() {
        val fit = ImageFit.fit(384, 512, 444, 360)!!
        assertEquals(360, fit.height)
        assertEquals(270, fit.width)
        assertEquals((444 - 270) / 2, fit.left)
        assertEquals(0, fit.top)
    }

    @Test fun `a small JPEG is scaled up to the box and keeps its aspect`() {
        val fit = ImageFit.fit(240, 180, 444, 360)!!
        assertEquals(444, fit.width)
        assertEquals(333, fit.height)
    }

    @Test fun `one-bit art is only scaled up by whole factors`() {
        val fit = ImageFit.fit(128, 96, 444, 360, wholeFactors = true)!!
        assertEquals(384, fit.width)
        assertEquals(288, fit.height)
        assertEquals(30, fit.left)
        assertEquals(36, fit.top)
        // Larger than the box it still shrinks by any factor: it must fit before it is square.
        val shrunk = ImageFit.fit(512, 512, 444, 360, wholeFactors = true)!!
        assertEquals(360, shrunk.width)
        assertEquals(360, shrunk.height)
    }

    @Test fun `an empty box or picture fits nothing`() {
        assertNull(ImageFit.fit(512, 384, 0, 360))
        assertNull(ImageFit.fit(0, 384, 444, 360))
    }
}
