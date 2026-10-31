package cz.digitalnivedomi.diarium.core.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The subsample maths behind [PhotoCompressor]: every decode must land above the
 * 1600 px target (so the downscale keeps detail) while never allocating the full
 * frame (a 50 MP source would otherwise cost ~200 MB).
 */
class PhotoCompressorTest {

    @Test
    fun `a frame that already fits is decoded at full size`() {
        assertEquals(1, PhotoCompressor.sampleSize(1600, 1200))
        assertEquals(1, PhotoCompressor.sampleSize(800, 600))
    }

    @Test
    fun `a 12 MP frame is subsampled once and keeps detail above the target`() {
        val sample = PhotoCompressor.sampleSize(4000, 3000)
        assertEquals(2, sample)
        // 4000 / 2 = 2000 px on the long edge — still above 1600, so the
        // createScaledBitmap step does the final, pixel-exact reduction.
        assertEquals(2000, 4000 / sample)
    }

    @Test
    fun `a 50 MP frame is subsampled twice`() {
        val sample = PhotoCompressor.sampleSize(8160, 6120)
        assertEquals(4, sample)
        assertEquals(2040, 8160 / sample)
    }

    @Test
    fun `a portrait frame uses its long edge`() {
        assertEquals(PhotoCompressor.sampleSize(3000, 4000), PhotoCompressor.sampleSize(4000, 3000))
    }

    @Test
    fun `an invalid target falls back to no subsampling`() {
        assertEquals(1, PhotoCompressor.sampleSize(4000, 3000, 0))
    }
}
