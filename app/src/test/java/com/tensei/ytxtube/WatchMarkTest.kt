package com.tensei.ytxtube

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchMarkTest {
    private fun url(watchtime: Boolean) = InnerTube.markUrl(
        videoId = "abc",
        cpn = "cpn123",
        lengthSec = 214.0,
        eventId = "ei123",
        visitorData = "vm123",
        ofParam = "of123",
        watchtime = watchtime,
    )!!

    @Test fun watchtimeMarkMatchesSmartTubeShortForm() {
        val u = url(watchtime = true)

        assertTrue(u.startsWith("https://www.youtube.com/api/stats/watchtime?"))
        assertTrue(u.contains("ns=yt"))
        assertTrue(u.contains("ver=2"))
        assertTrue(u.contains("final=1"))
        assertTrue(u.contains("docid=abc"))
        assertTrue(u.contains("cpn=cpn123"))
        assertTrue(u.contains("ei=ei123"))
        assertTrue(u.contains("vm=vm123"))
        assertTrue(u.contains("of=of123"))
        assertTrue(u.contains("st=214.000"))
        assertTrue(u.contains("et=214.000"))
        assertFalse(u.contains("cmt="))
        assertFalse(u.contains("len="))
        assertFalse(u.contains("&c="))
        assertFalse(u.contains("cver="))
    }

    @Test fun playbackMarkHasNoWatchRange() {
        val u = url(watchtime = false)

        assertTrue(u.startsWith("https://www.youtube.com/api/stats/playback?"))
        assertTrue(u.contains("final=1"))
        assertTrue(u.contains("ei=ei123"))
        assertFalse(u.contains("st="))
        assertFalse(u.contains("et="))
    }
}
