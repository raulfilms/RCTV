package com.nuvio.tv.core.smartscraper
/** RCTV (owner's choice): the size limit grows with a long movie: 8 GB per 2 hours. */
class AutoStreamSizeLimitTest {
    @org.junit.Test fun `8 GB per 2 hours for a movie, the limit for an episode`() {
        val c = AutoStreamConfig()
        val gb = 1024.0 * 1024 * 1024
        org.junit.Assert.assertEquals(c.maxFileSizeBytes, c.maxFileSizeBytesFor(null, false))
        org.junit.Assert.assertEquals(c.maxFileSizeBytes, c.maxFileSizeBytesFor(95, false))
        org.junit.Assert.assertEquals(c.maxFileSizeBytes, c.maxFileSizeBytesFor(169, true))
        val long = c.maxFileSizeBytesFor(169, false) / gb
        org.junit.Assert.assertTrue("169 min → ${long} GB", long > 10.5 && long < 12.0)
    }
}
