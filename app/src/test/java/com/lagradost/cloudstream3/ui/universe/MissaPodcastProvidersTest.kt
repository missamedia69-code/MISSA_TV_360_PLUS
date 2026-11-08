package com.lagradost.cloudstream3.ui.universe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests JVM des sources de l'univers Podcasts (PODCASTS-01).
 *
 * Le réseau n'est pas joignable depuis un test : on vérifie donc les **analyseurs** sur des
 * réponses réelles de l'annuaire Apple et de flux RSS, ainsi que l'assemblage du catalogue
 * (dédoublonnage, Afrique d'abord). Ce sont les seules parties où une erreur serait invisible
 * à l'écran sans pourrir tout le catalogue.
 */
class MissaPodcastProvidersTest {

    // --- Annuaire Apple ---------------------------------------------------------------

    private val itunesJson = """
        {
          "resultCount": 4,
          "results": [
            {
              "collectionId": 1234567,
              "collectionName": "  Afrique   Hebdo  ",
              "artistName": "RFI",
              "feedUrl": "https://feeds.example.org/afrique-hebdo.xml",
              "artworkUrl600": "https://art.example.org/600.jpg",
              "artworkUrl100": "https://art.example.org/100.jpg",
              "primaryGenreName": "News",
              "trackCount": 42,
              "genres": ["Podcasts", "News", "Africa"]
            },
            {
              "collectionId": 7654321,
              "collectionName": "Tech en Afrique",
              "artistName": "Studio Kalakuta",
              "feedUrl": "https://podcast.example.net/tech.xml",
              "artworkUrl100": "https://art.example.org/tech100.jpg",
              "primaryGenreName": "Technology",
              "trackCount": 8
            },
            {
              "collectionId": 999,
              "collectionName": "Émission sans flux",
              "artistName": "Quelqu'un",
              "feedUrl": "",
              "artworkUrl600": "https://art.example.org/nope.jpg"
            },
            {
              "collectionId": 111,
              "collectionName": "   ",
              "artistName": "Sans titre",
              "feedUrl": "https://feeds.example.org/vide.xml"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `annuaire garde seulement les emissions qui ont un flux RSS`() {
        val shows = MissaPodcastProviders.parseItunesSearchJson(
            itunesJson,
            PodcastRegion.AFRICA
        )
        // L'émission sans flux et celle sans titre sont écartées : sans flux, aucun épisode.
        assertEquals(2, shows.size)
        assertEquals("Afrique Hebdo", shows[0].title)
        assertEquals("RFI", shows[0].author)
        assertEquals("https://feeds.example.org/afrique-hebdo.xml", shows[0].feedUrl)
        assertEquals(42, shows[0].episodeCount)
        assertEquals("News", shows[0].genre)
        assertTrue(shows[0].isAfrica)
    }

    @Test
    fun `annuaire prefere la pochette 600 px puis celle en 100 px`() {
        val shows = MissaPodcastProviders.parseItunesSearchJson(itunesJson)
        assertEquals("https://art.example.org/600.jpg", shows[0].artworkUrl)
        assertEquals("https://art.example.org/tech100.jpg", shows[1].artworkUrl)
    }

    @Test
    fun `annuaire ignore une reponse invalide`() {
        assertTrue(MissaPodcastProviders.parseItunesSearchJson("pas du json").isEmpty())
        assertTrue(MissaPodcastProviders.parseItunesSearchJson("").isEmpty())
        assertTrue(MissaPodcastProviders.parseItunesSearchJson("{\"resultCount\":0}").isEmpty())
    }

    // --- Flux RSS --------------------------------------------------------------------

    private val rssXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"
             xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"
             xmlns:media="http://search.yahoo.com/mrss/">
          <channel>
            <title>Afrique Hebdo</title>
            <itunes:image href="https://art.example.org/channel.jpg"/>
            <item>
              <title>Les élections au Cameroun</title>
              <itunes:author>RFI</itunes:author>
              <itunes:duration>42:15</itunes:duration>
              <pubDate>Mon, 02 Nov 2026 06:00:00 +0000</pubDate>
              <description><![CDATA[<p>Un épisode sur le Cameroun.</p>]]></description>
              <enclosure url="https://audio.example.org/ep1.mp3" type="audio/mpeg" length="12345"/>
              <itunes:image href="https://art.example.org/ep1.jpg"/>
            </item>
            <item>
              <title>Épisode annoncé sans fichier</title>
              <itunes:duration>1:02:33</itunes:duration>
              <description>Le fichier n'est pas encore publié.</description>
            </item>
            <item>
              <title>Épisode ancien</title>
              <itunes:duration>2533</itunes:duration>
              <pubDate>Fri, 24 Oct 2026 05:00:00 +0000</pubDate>
              <enclosure url="https://audio.example.org/ep3.m4a" type="audio/mp4"/>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    private val sampleShow = PodcastShowPreset(
        id = "apple_1234567",
        title = "Afrique Hebdo",
        author = "RFI",
        artworkUrl = "https://art.example.org/show.jpg",
        feedUrl = "https://feeds.example.org/afrique-hebdo.xml",
        region = PodcastRegion.AFRICA,
        episodeCount = 42
    )

    @Test
    fun `flux RSS lit les episodes dans l ordre du flux et saute ceux sans audio`() {
        val episodes = MissaPodcastProviders.parsePodcastRss(rssXml, sampleShow)
        assertEquals(2, episodes.size)

        val first = episodes[0]
        assertEquals("Les élections au Cameroun", first.title)
        assertEquals("RFI", first.author)
        assertEquals("https://audio.example.org/ep1.mp3", first.audioUrl)
        assertEquals(42 * 60 + 15, first.durationSeconds)
        assertEquals("https://art.example.org/ep1.jpg", first.artworkUrl)
        assertEquals("Afrique Hebdo", first.showTitle)
        assertTrue(first.publishedAtMillis > 0L)

        // Pas de fichier audio : l'épisode annoncé ne doit pas apparaître comme écoutable.
        assertTrue(episodes.none { it.title.contains("sans fichier") })
    }

    @Test
    fun `flux RSS retombe sur la pochette de l emission`() {
        val episodes = MissaPodcastProviders.parsePodcastRss(rssXml, sampleShow)
        val old = episodes.first { it.title == "Épisode ancien" }
        assertEquals("https://art.example.org/show.jpg", old.artworkUrl)
        assertEquals(2533, old.durationSeconds)
    }

    @Test
    fun `flux RSS retombe sur la pochette du canal`() {
        val bareShow = sampleShow.copy(artworkUrl = null)
        val episodes = MissaPodcastProviders.parsePodcastRss(rssXml, bareShow)
        val old = episodes.first { it.title == "Épisode ancien" }
        assertEquals("https://art.example.org/channel.jpg", old.artworkUrl)
    }

    @Test
    fun `flux RSS range les episodes du plus recent au plus ancien`() {
        val episodes = MissaPodcastProviders.parsePodcastRss(rssXml, sampleShow)
        assertTrue(episodes[0].publishedAtMillis > episodes[1].publishedAtMillis)
    }

    @Test
    fun `flux RSS comprend aussi une entree Atom avec lien enclosure`() {
        val atom = """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <title>Épisode Atom</title>
                <published>2026-10-01T08:00:00Z</published>
                <link rel="enclosure" type="audio/mpeg" href="https://audio.example.org/atom.mp3"/>
              </entry>
            </feed>
        """.trimIndent()
        val episodes = MissaPodcastProviders.parsePodcastRss(atom, sampleShow)
        assertEquals(1, episodes.size)
        assertEquals("https://audio.example.org/atom.mp3", episodes[0].audioUrl)
    }

    @Test
    fun `flux RSS ignore un flux vide ou invalide`() {
        assertTrue(MissaPodcastProviders.parsePodcastRss("", sampleShow).isEmpty())
        assertTrue(MissaPodcastProviders.parsePodcastRss("<<< pas du xml", sampleShow).isEmpty())
        assertTrue(
            MissaPodcastProviders.parsePodcastRss(
                "<rss><channel><item><title>Sans audio</title></item></channel></rss>",
                sampleShow
            ).isEmpty()
        )
    }

    // --- Durées & dates ---------------------------------------------------------------

    @Test
    fun `les durees de flux sont comprises dans les trois formats`() {
        assertEquals(2535, MissaPodcastProviders.parseDurationSeconds("42:15"))
        assertEquals(3753, MissaPodcastProviders.parseDurationSeconds("1:02:33"))
        assertEquals(2533, MissaPodcastProviders.parseDurationSeconds("2533"))
        assertEquals(0, MissaPodcastProviders.parseDurationSeconds(""))
        assertEquals(0, MissaPodcastProviders.parseDurationSeconds("indisponible"))
    }

    @Test
    fun `les dates de flux sont lues quand elles sont lisibles, abandonnees sinon`() {
        assertTrue(
            MissaPodcastProviders.parseFeedDateMillis("Mon, 02 Nov 2026 06:00:00 +0000") > 0L
        )
        assertTrue(MissaPodcastProviders.parseFeedDateMillis("2026-11-02") > 0L)
        assertEquals(0L, MissaPodcastProviders.parseFeedDateMillis(""))
        assertEquals(0L, MissaPodcastProviders.parseFeedDateMillis("hier matin"))
    }

    // --- Assemblage du catalogue ------------------------------------------------------

    @Test
    fun `le catalogue est dedoublonne et l Afrique garde la tete`() {
        val africaShow = PodcastShowPreset(
            id = "apple_1",
            title = "Afrique Hebdo",
            feedUrl = "https://feeds.example.org/afrique-hebdo.xml",
            region = PodcastRegion.AFRICA,
            episodeCount = 10
        )
        val sameShowFromWorldQuery = PodcastShowPreset(
            id = "apple_2",
            title = "Afrique Hebdo (réédition)",
            feedUrl = "https://feeds.example.org/afrique-hebdo.xml?utm=itunes",
            region = PodcastRegion.WORLD,
            episodeCount = 99
        )
        val worldShow = PodcastShowPreset(
            id = "apple_3",
            title = "World Tech",
            feedUrl = "https://podcast.example.net/tech.xml",
            region = PodcastRegion.WORLD,
            episodeCount = 99
        )
        val noFeed = PodcastShowPreset(id = "apple_4", title = "Sans flux", feedUrl = "")

        val merged = MissaPodcastProviders.mergeShowCatalogues(
            listOf(worldShow, sameShowFromWorldQuery, noFeed, africaShow)
        )

        assertEquals(2, merged.size)
        assertTrue(merged[0].isAfrica)
        // La variante repérée comme africaine garde la tête, même avec moins d'épisodes.
        assertEquals("Afrique Hebdo", merged[0].title)
        assertEquals("World Tech", merged[1].title)
    }

    @Test
    fun `deux flux du meme chemin comptent pour une seule emission`() {
        val a = PodcastShowPreset(id = "a", title = "Douala Actu", feedUrl = "https://f.example/douala.xml")
        val b = PodcastShowPreset(id = "b", title = "Douala Actu", feedUrl = "https://f.example/douala.xml?src=rss")
        assertEquals(
            MissaPodcastProviders.normalizeShowKey(a),
            MissaPodcastProviders.normalizeShowKey(b)
        )
        val c = PodcastShowPreset(id = "c", title = "Autre", feedUrl = "https://f.example/autre.xml")
        assertFalse(
            MissaPodcastProviders.normalizeShowKey(a) == MissaPodcastProviders.normalizeShowKey(c)
        )
    }

    @Test
    fun `la recherche locale ignore les accents et la casse`() {
        val show = PodcastShowPreset(
            id = "apple_9",
            title = "Émission Café",
            author = "Radio Douala",
            genre = "Société"
        )
        assertTrue(MissaPodcastProviders.matchesShowQuery(show, "emission"))
        assertTrue(MissaPodcastProviders.matchesShowQuery(show, "CAFÉ"))
        assertTrue(MissaPodcastProviders.matchesShowQuery(show, "radio douala"))
        assertTrue(MissaPodcastProviders.matchesShowQuery(show, "societe"))
        assertFalse(MissaPodcastProviders.matchesShowQuery(show, "sport"))
    }

    // --- Les cartes affichées ---------------------------------------------------------

    @Test
    fun `une carte d emission annonce son nombre d episodes`() {
        val card = PodcastShowPreset(
            id = "apple_1",
            title = "Afrique Hebdo",
            feedUrl = "https://feeds.example.org/afrique-hebdo.xml",
            episodeCount = 12
        ).toSearchResponse()
        assertTrue(card.isShow)
        assertEquals("ÉMISSION • 12", card.formattedDuration())
        assertTrue(card.url.startsWith(PODCAST_SHOW_URL_PREFIX))
    }

    @Test
    fun `une carte d episode porte le fichier audio et se transforme en piste`() {
        val episode = PodcastEpisode(
            id = "ep_1",
            showId = "apple_1",
            showTitle = "Afrique Hebdo",
            author = "RFI",
            title = "Les élections au Cameroun",
            audioUrl = "https://audio.example.org/ep1.mp3",
            durationSeconds = 754
        )
        val card = episode.toSearchResponse()
        assertFalse(card.isShow)
        assertEquals("https://audio.example.org/ep1.mp3", card.url)
        assertEquals("12:34", card.formattedDuration())

        val preset = episode.toMusicTrackPreset()
        assertEquals("https://audio.example.org/ep1.mp3", preset.streamUrl)
        assertEquals("RFI", preset.artist)
        assertEquals("Afrique Hebdo", preset.album)
        assertEquals(MusicItemKind.PODCAST, preset.itemKind)
        // Rien à extraire : l'audio est déjà un fichier direct.
        assertFalse(preset.isPipedLazy)
        assertFalse(preset.isAlbumOrPlaylist())
    }

    @Test
    fun `un episode sans duree n affiche pas de duree`() {
        val card = PodcastEpisode(id = "ep_2", title = "Sans durée", audioUrl = "https://a.b/c.mp3")
            .toSearchResponse()
        assertNull(card.formattedDuration())
    }
}
