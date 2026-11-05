package com.tensei.ytxtube

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchTrackingTest {

    @Test fun readsBothStatsUrls() {
        val json = JSONObject(
            """
            {"playbackTracking":{
              "videostatsPlaybackUrl":{"baseUrl":"https://s.youtube.com/api/stats/playback?docid=abc&ns=yt"},
              "videostatsWatchtimeUrl":{"baseUrl":"https://s.youtube.com/api/stats/watchtime?docid=abc&ns=yt"}
            }}
            """.trimIndent(),
        )
        val tracking = InnerTube.parseWatchTracking(json)!!
        assertEquals("https://s.youtube.com/api/stats/playback?docid=abc&ns=yt", tracking.playbackUrl)
        assertEquals("https://s.youtube.com/api/stats/watchtime?docid=abc&ns=yt", tracking.watchtimeUrl)
    }

    @Test fun playbackUrlIsOptional() {
        val json = JSONObject(
            """{"playbackTracking":{"videostatsWatchtimeUrl":{"baseUrl":"https://s.youtube.com/api/stats/watchtime?docid=abc"}}}""",
        )
        val tracking = InnerTube.parseWatchTracking(json)!!
        assertNull(tracking.playbackUrl)
        assertEquals("https://s.youtube.com/api/stats/watchtime?docid=abc", tracking.watchtimeUrl)
    }

    @Test fun readsVisitorDataFromPlayerResponse() {
        val json = JSONObject(
            """
            {"responseContext":{"visitorData":"visitor-1"},"playbackTracking":{
              "videostatsWatchtimeUrl":{"baseUrl":"https://www.youtube.com/api/stats/watchtime?docid=abc"}
            }}
            """.trimIndent(),
        )
        assertEquals("visitor-1", InnerTube.parseWatchTracking(json)!!.visitorData)
    }

    @Test fun missingTrackingMeansNoSession() {
        assertNull(InnerTube.parseWatchTracking(JSONObject("{}")))
        assertNull(InnerTube.parseWatchTracking(JSONObject("""{"playbackTracking":{}}""")))
        assertNull(
            InnerTube.parseWatchTracking(
                JSONObject("""{"playbackTracking":{"videostatsWatchtimeUrl":{"baseUrl":""}}}"""),
            ),
        )
    }
}
