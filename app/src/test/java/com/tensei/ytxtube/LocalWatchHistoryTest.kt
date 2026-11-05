package com.tensei.ytxtube

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalWatchHistoryTest {

    private fun video(id: String, title: String) = VideoItem(
        id = id,
        title = title,
        channel = "chan",
        channelUrl = null,
        thumbnail = null,
        avatar = null,
        durationSec = 42,
        views = -1,
        uploaded = null,
    )

    @Test fun localWatchesComeFirstWithoutDuplicates() {
        LocalWatchHistory.record("lh-a", "Local A", "chan", null, 42)
        LocalWatchHistory.record("lh-b", "Local B", "chan", null, 42)
        LocalWatchHistory.record("lh-a", "Local A2", "chan", null, 42)

        val merged = LocalWatchHistory.merge(listOf(video("lh-a", "Server A"), video("lh-c", "Server C")))

        assertEquals(listOf("lh-a", "lh-b", "lh-c"), merged.map { it.id })
        assertEquals("Local A2", merged.first().title)
    }
}
