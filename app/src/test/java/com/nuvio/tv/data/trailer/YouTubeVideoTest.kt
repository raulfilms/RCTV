package com.nuvio.tv.data.trailer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeVideoTest {

    @Test
    fun `every YouTube address gives its video id`() {
        val id = "dQw4w9WgXcQ"
        listOf(
            id,
            "https://www.youtube.com/watch?v=$id",
            "https://youtube.com/watch?feature=share&v=$id&t=10",
            "https://m.youtube.com/watch?v=$id",
            "https://youtu.be/$id?si=abc",
            "https://www.youtube.com/embed/$id",
            "https://www.youtube-nocookie.com/embed/$id",
            "https://www.youtube.com/shorts/$id",
            "https://www.youtube.com/live/$id?feature=share",
            "  https://www.youtube.com/watch?v=$id  "
        ).forEach { address -> assertEquals(address, id, YouTubeVideo.idOf(address)) }
    }

    @Test
    fun `anything that isn't a YouTube video is not one`() {
        listOf(
            null,
            "",
            "https://example.com/watch?v=dQw4w9WgXcQ",
            "https://rr1---sn-abc.googlevideo.com/videoplayback?id=1",
            "https://www.youtube.com/watch?v=short",
            "https://www.youtube.com/channel/UC123",
            "https://cdn.example.com/trailer.mp4",
            "dQw4w9WgXc!"
        ).forEach { address -> assertNull(address, YouTubeVideo.idOf(address)) }
    }

    @Test
    fun `the watch address opens the video`() {
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", YouTubeVideo.watchUrl("dQw4w9WgXcQ"))
    }
}
