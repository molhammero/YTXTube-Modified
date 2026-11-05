package com.tensei.ytxtube

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchFiltersTest {

    @Test fun noFiltersEncodeToNull() {
        assertNull(SearchFilters().params())
    }

    @Test fun typeOnly() {
        assertEquals("EgIQAQ", SearchFilters(type = SearchType.Video).params())
        assertEquals("EgIQAg", SearchFilters(type = SearchType.Channel).params())
        assertEquals("EgIQAw", SearchFilters(type = SearchType.Playlist).params())
    }

    @Test fun uploadDateWithVideoType() {
        assertEquals("EgQIAxAB", SearchFilters(SearchType.Video, SearchDate.ThisWeek).params())
        assertEquals("EgQIBBAB", SearchFilters(SearchType.Video, SearchDate.ThisMonth).params())
        assertEquals("EgIIBQ", SearchFilters(type = SearchType.All, date = SearchDate.ThisYear).params())
    }

    @Test fun uploadDateIsIgnoredForChannelsAndPlaylists() {
        assertEquals("EgIQAg", SearchFilters(SearchType.Channel, SearchDate.ThisMonth).params())
        assertEquals("EgIQAw", SearchFilters(SearchType.Playlist, SearchDate.ThisWeek).params())
    }

    @Test fun mixedResultsAreParsedInOrder() {
        val json = JSONObject(
            """
            {"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[
              {"itemSectionRenderer":{"contents":[
                {"channelRenderer":{
                  "channelId":"UC123",
                  "title":{"simpleText":"Lofi Girl"},
                  "thumbnail":{"thumbnails":[{"url":"https://yt3.ggpht.com/abc=s88-c-k-c0x00ffffff-no-rj","width":88}]},
                  "subscriberCountText":{"simpleText":"15.8M subscribers"}
                }},
                {"lockupViewModel":{
                  "contentId":"PL123",
                  "contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST",
                  "contentImage":{"collectionThumbnailViewModel":{"primaryThumbnail":{"thumbnailViewModel":{
                    "image":{"sources":[{"url":"https://i.ytimg.com/vi/abc/hq720.jpg","width":720,"height":404}]},
                    "overlays":[{"thumbnailOverlayBadgeViewModel":{"thumbnailBadges":[{"thumbnailBadgeViewModel":{"text":"54 videos"}}]}}]
                  }}}},
                  "metadata":{"lockupMetadataViewModel":{
                    "title":{"content":"Morning Coffee"},
                    "metadata":{"contentMetadataViewModel":{"metadataRows":[
                      {"metadataParts":[{"text":{"content":"Soul Lo Fi"}},{"text":{"content":"Playlist"}}]},
                      {"metadataParts":[{"text":{"content":"Some song - 2:01:06"}}]}
                    ]}}
                  }}
                }}
              ]}},
              {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"TOKEN123"}}}}
            ]}}}}}
            """.trimIndent(),
        )

        val page = InnerTube.parseSearch(json)
        assertEquals(2, page.items.size)
        assertEquals("TOKEN123", page.next)

        val channel = page.items[0] as SearchItem.Channel
        assertEquals("UC123", channel.id)
        assertEquals("Lofi Girl", channel.name)
        assertEquals("15.8M subscribers", channel.subscribers)

        val playlist = page.items[1] as SearchItem.Playlist
        assertEquals("PL123", playlist.id)
        assertEquals("Morning Coffee", playlist.title)
        assertEquals("Soul Lo Fi", playlist.channel)
        assertTrue(playlist.videoCount == "54 videos")
        assertTrue(playlist.thumbnail!!.endsWith("hq720.jpg"))
    }
}
