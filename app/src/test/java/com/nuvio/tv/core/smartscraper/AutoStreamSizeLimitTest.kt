package com.nuvio.tv.core.smartscraper
/** RCTV (owner's choice): the size limit grows with a long movie: 8 GB per 2 hours, then 20, 30 or 40 GB. */
class AutoStreamSizeLimitTest {
    @org.junit.Test fun `8 GB per 2 hours for a movie, the limit for an episode`() {
        val c = AutoStreamConfig()
        val gb = 1024.0 * 1024 * 1024
        org.junit.Assert.assertEquals(c.maxFileSizeBytes, c.maxFileSizeBytesFor(null, false))
        org.junit.Assert.assertEquals(c.maxFileSizeBytes, c.maxFileSizeBytesFor(95, false))
        org.junit.Assert.assertEquals(c.maxFileSizeBytes, c.maxFileSizeBytesFor(169, true))
        val longer = c.maxFileSizeBytesFor(140, false) / gb
        org.junit.Assert.assertTrue("140 min → ${longer} GB", longer > 9.0 && longer < 9.5)
        // RCTV (owner's rule): past 2 h 30 min, 20 GB; past 3 h, 30 GB; past 3 h 30 min, 40 GB.
        org.junit.Assert.assertEquals(20.0, c.maxFileSizeBytesFor(169, false) / gb, 0.01)
        org.junit.Assert.assertEquals(30.0, c.maxFileSizeBytesFor(200, false) / gb, 0.01)
        org.junit.Assert.assertEquals(40.0, c.maxFileSizeBytesFor(225, false) / gb, 0.01)
    }
}
