package com.tensei.ytxtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThumbnailFallbackTest {
    private val id = "dQw4w9WgXcQ"
    private val base = "https://i.ytimg.com/vi/$id/"

    @Test fun signedHq720FallsBackToUnsignedCopy() {
        assertEquals(base + "hq720.jpg", thumbnailFallbackUrl(base + "hq720.jpg?sqp=abc&rs=def", id))
    }

    @Test fun ladderWalksDownToTheAlwaysAvailableDefault() {
        assertEquals(base + "hqdefault.jpg", thumbnailFallbackUrl(base + "hq720.jpg", id))
        assertEquals(base + "mqdefault.jpg", thumbnailFallbackUrl(base + "hqdefault.jpg", id))
        assertEquals(base + "default.jpg", thumbnailFallbackUrl(base + "mqdefault.jpg", id))
        assertNull(thumbnailFallbackUrl(base + "default.jpg", id))
    }

    @Test fun otherSizesStartAtTheCanonicalUrl() {
        assertEquals(base + "hq720.jpg", thumbnailFallbackUrl("https://yt3.ggpht.com/abc=s720", id))
        assertEquals(base + "hq720.jpg", thumbnailFallbackUrl(base + "maxresdefault.jpg", id))
    }

    @Test fun externalImagesOnlyFallBackWhenTheVideoIsKnown() {
        assertNull(thumbnailFallbackUrl("https://example.com/banner.jpg", null))
        assertEquals(base + "hq720.jpg", thumbnailFallbackUrl("https://example.com/banner.jpg", id))
    }

    @Test fun missingUrlStillResolvesForAKnownVideo() {
        assertEquals(base + "hq720.jpg", thumbnailFallbackUrl(null, id))
        assertNull(thumbnailFallbackUrl(null, null))
    }
}
