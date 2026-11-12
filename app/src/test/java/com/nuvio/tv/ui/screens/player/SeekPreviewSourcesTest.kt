package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** RCTV (seek preview): which sources give pictures, how moments are grouped, and the time shown. */
class SeekPreviewSourcesTest {

    private fun can(url: String, mime: String? = null, sdk: Int = 28, videoMime: String? = null, codecs: String? = null) =
        SeekPreviewSources.canPreview(url, mime, sdk, videoMime, codecs)

    @Test
    fun `debrid and addon files give pictures`() {
        assertTrue(can("https://download.example-debrid.com/d/ABC123/Movie.2019.2160p.mkv"))
        assertTrue(can("https://cdn.example.org/files/abc?token=x", mime = "video/x-matroska"))
        assertTrue(can("http://198.51.100.7:8080/stream/movie.mp4", videoMime = "video/hevc", codecs = "hvc1.2.4.L153"))
    }

    @Test
    fun `live, segmented, local torrent and IPTV sources don't`() {
        assertFalse(can("http://127.0.0.1:8090/stream/0"))
        assertFalse(can("http://localhost:6878/ace/getstream?id=1"))
        assertFalse(can("http://[::1]:8090/file"))
        assertFalse(can("https://cdn.example.org/master.m3u8"))
        assertFalse(can("https://cdn.example.org/play", mime = "application/x-mpegURL"))
        assertFalse(can("https://cdn.example.org/manifest.mpd"))
        assertFalse(can("http://iptv.example.net:8080/movie/user/pass/12345.mkv"))
        assertFalse(can("http://iptv.example.net:8080/series/user/pass/678.mp4"))
        assertFalse(can("magnet:?xt=urn:btih:abc"))
    }

    @Test
    fun `Dolby Vision and old Android show only the time`() {
        assertFalse(can("https://x.example.com/a.mkv", videoMime = "video/dolby-vision"))
        assertFalse(can("https://x.example.com/a.mkv", codecs = "dvhe.05.06"))
        assertFalse(can("https://x.example.com/a.mkv", sdk = 26))
    }

    @Test
    fun `moments are read every five seconds`() {
        assertEquals(0L, SeekPreviewSources.bucketOf(-3_000L))
        assertEquals(0L, SeekPreviewSources.bucketOf(2_499L))
        assertEquals(5_000L, SeekPreviewSources.bucketOf(2_500L))
        assertEquals(675_000L, SeekPreviewSources.bucketOf(673_400L))
    }

    @Test
    fun `the time reads as the player's clock`() {
        assertEquals("0:00", SeekPreviewSources.timeLabel(0L))
        assertEquals("11:13", SeekPreviewSources.timeLabel(673_400L))
        assertEquals("1:02:45", SeekPreviewSources.timeLabel(3_765_000L))
    }
}
