package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenDesignTest {
    @Test fun posterGlowUsesArtAndCapsBrightPosters() {
        assertEquals(0x8CFF0000.toInt(), filmGlow(intArrayOf(0xFFFF0000.toInt())))
        assertEquals(0x8CB2B2B2.toInt(), filmGlow(intArrayOf(0xFFFFFFFF.toInt())))
    }

    @Test fun filmTintIsTheArtsHueAtWallDepth() {
        val red = filmTint(intArrayOf(0xFFFF2020.toInt(), 0xFFC01010.toInt()))
        val r = red ushr 16 and 255; val g = red ushr 8 and 255; val b = red and 255
        assert(r > g && r > b) { "a red film tints red: ${Integer.toHexString(red)}" }
        assert(r <= 0x40) { "deep, a wall not a colour: ${Integer.toHexString(red)}" }
        assertEquals(0xFF, red ushr 24)
        // A grey picture gives a near-neutral tint, close to the base near-black: no colour is invented.
        val grey = filmTint(intArrayOf(0xFF808080.toInt(), 0xFF404040.toInt()))
        val channels = listOf(grey ushr 16 and 255, grey ushr 8 and 255, grey and 255)
        assert(channels.max() - channels.min() <= 2) { Integer.toHexString(grey) }
        // Colourful pixels decide the hue even in a mostly grey picture.
        val mostlyGrey = filmTint(IntArray(20) { 0xFF505050.toInt() } + intArrayOf(0xFF2040FF.toInt()))
        assert((mostlyGrey and 255) > (mostlyGrey ushr 16 and 255)) { Integer.toHexString(mostlyGrey) }
    }

    @Test fun hslRoundTripsThePrimaries() {
        assertEquals(0xFFFF0000.toInt(), hsl(0.0, 1.0, 0.5))
        assertEquals(0xFF00FF00.toInt(), hsl(120.0, 1.0, 0.5))
        assertEquals(0xFF0000FF.toInt(), hsl(240.0, 1.0, 0.5))
        assertEquals(0xFF808080.toInt(), hsl(0.0, 0.0, 0.5))
        assertEquals(0x80123456.toInt(), withAlpha(0xFF123456.toInt(), 0x80))
        assertEquals(0xFF804020.toInt(), mix(0xFFFF8040.toInt(), 0xFF000000.toInt(), 0.5))
    }

    @Test fun shadeRespondsToBrightnessOnTheTextSide() {
        assertEquals(true, brightLeft(intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 2))
        assertEquals(false, brightLeft(intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 2))
    }

    @Test fun homeSynopsisEndsAtACompleteSentenceWhenClipped() {
        assertEquals("First sentence.", synopsisAtSentence("First sentence. The second is longer.", 23))
        assertEquals("No…", synopsisAtSentence("No punctuation here", 7))
        val city = "Sgt. Wayne Jenkins leads a unit. It goes wrong."
        assertEquals("Sgt. Wayne Jenkins…", synopsisAtSentence(city, 20))
        assertEquals("Sgt. Wayne Jenkins leads a unit.", synopsisAtSentence(city, 40))
        assertEquals("Agents in the U.S. hunt a spy.", synopsisAtSentence("Agents in the U.S. hunt a spy. Then more.", 35))
        assertEquals("Find the remaining Horcruxes…", synopsisAtSentence("Find the remaining Horcruxes, and return.", 30))
    }

    @Test fun languageCodesFromEverySourceMatch() {
        listOf("es", "spa", "spl", "es-MX", "Spanish").forEach { assertEquals("es", Languages.code(it)) }
        assertEquals("de", Languages.code("ger"))
        assertEquals("fr", Languages.code("fre"))
        assertEquals("nl", Languages.code("dut"))
        listOf("pob", "pt-BR", "pt_br", "por-BR", "Portuguese (Brazil)").forEach { assertEquals("pt-BR", Languages.code(it)) }
        listOf("pt", "por", "pom", "pt-PT", "Portuguese").forEach { assertEquals("pt", Languages.code(it)) }
        assertEquals(2, Languages.match("pob", "pt-BR"))
        assertEquals(1, Languages.match("por", "pt-BR"))
        assertEquals(0, Languages.match("spa", "pt-BR"))
        assertEquals("Portuguese (Brazil)", Languages.name("pob"))
        assertEquals("he", Languages.code("heb"))
        assertEquals(null, Languages.code("und"))
        assertEquals(true, Languages.same("eng", "en"))
        assertEquals(false, Languages.same("und", "und"))
        assertEquals(listOf("en", "es"), Languages.all.take(2))
    }

    @Test fun audioLanguagesFromFlagsNamesAndTags() {
        assertEquals(setOf("en", "it"), audioLanguagesIn("👤 52 💾 4.2 GB ⚙️ ThePirateBay 🇬🇧 / 🇮🇹"))
        assertEquals(setOf("en", "es"), audioLanguagesIn("🇺🇸🇪🇸")) // not also Sweden from the middle letters
        assertEquals(setOf("en", "hi"), audioLanguagesIn("🌐 English | Hindi"))
        assertEquals(setOf("it"), audioLanguagesIn("Movie.2024.1080p.BluRay.ITA.x264"))
        assertEquals(setOf("fr"), audioLanguagesIn("Movie 2024 TRUEFRENCH 2160p WEB"))
        assertEquals(emptySet<String>(), audioLanguagesIn("Movie.2024.2160p.WEB-DL.DV.HDR.H265"))
    }

    @Test fun whatsNewReadsOneVersionsEntries() {
        val log = "# Changelog\n\n- a rule, not a change\n\n## Unreleased\n\n- Next\n\n## 1.2\n\n- **One.** First\n- Two\n\n## 1.1\n\n- Old\n"
        assertEquals(listOf("**One.** First", "Two"), changesFor(log, "1.2"))
        assertEquals(listOf("Next"), changesFor(log, "Unreleased"))
        assertEquals(emptyList<String>(), changesFor(log, "1.3"))
    }

    @Test fun debridServiceFromTagsAndLinks() {
        fun of(name: String, url: String = "https://example.com/v.mkv") = debridOf(name, url)?.let { "${it.short} ${it.cached}" }
        assertEquals("RD true", of("[RD+] Torrentio\n4k"))
        assertEquals("RD false", of("[RD download] Torrentio\n1080p"))
        assertEquals("TB true", of("[TB⚡] Comet 2160p"))
        assertEquals("AD false", of("[AD⏳] AIOStreams"))
        assertEquals("RD true", of("MediaFusion ⚡RD 4K"))
        assertEquals("PM null", of("Torrentio\n4k", "https://torrentio.strem.fun/resolve/premiumize/key/hash/null/0/v.mkv"))
        assertEquals("RD null", of("Direct", "https://abc.download.real-debrid.com/d/X/v.mkv"))
        assertEquals(null, of("Movie.2024.1080p.WEB-DL.DDP5.1")) // DL and DD aren't services without a tag
        assertEquals(null, of("[HDR] 4K"))
    }

    @Test fun homeRecommendationRowHasTheShortLabel() {
        assertEquals("More like Dune", homeRowName("Because you watched Dune"))
        assertEquals("More like Crayon Shin-chan", homeRowName("Because you watched Crayon Shin-chan: Pursuit"))
        assertEquals("Popular", homeRowName("Popular"))
    }

    @Test fun seasonCountIsSingularForOne() {
        assertEquals("1 season", seasons(1))
        assertEquals("5 seasons", seasons(5))
    }

    @Test fun catalogGenresUseAvailableOptionsInScreenOrder() {
        assertEquals(listOf("Drama", "Comedy", "Sci-Fi", "Western"),
            catalogGenres(listOf("Western", "Comedy", "All", "Drama", "Sci-Fi", "drama")))
    }

    @Test fun genreStripWaitsForAllRowsAndKeepsOnlyRowsWithTitles() {
        val names = listOf("Drama", "Comedy", "Sci-Fi")
        assertEquals(null, settledGenres(names, mapOf("Drama" to true, "Comedy" to false)))
        assertEquals(listOf("Drama", "Sci-Fi"), settledGenres(names,
            mapOf("Drama" to true, "Comedy" to false, "Sci-Fi" to true)))
        assertEquals(emptyList<String>(), settledGenres(names, names.associateWith { false }))
    }

    @Test fun catalogRowsHaveShortNames() {
        assertEquals("For you", shortRowName("Top Picks for You"))
        assertEquals("Popular", shortRowName("Trending Now"))
        assertEquals("Drama", shortRowName("Drama"))
        assertEquals("Recommended in India", shortRowName("Recommended in India"))
        assertEquals("Comedy Sci-Fi", shortRowName("Comedy Science Fiction"))
        assertEquals("Adventure", shortRowName("Short & Sweet Adventure", listOf("Drama", "Adventure")))
    }

    @Test fun runtimeReadsInHoursAndMinutes() {
        assertEquals("2h 27m", runtime("147 min"))
        assertEquals("1h 05m", runtime("65 min"))
        assertEquals("45 min", runtime("45 min"))
        assertEquals("1h 30m", runtime("1h 30min"))
        assertEquals("2h 35m", runtime("2h35min"))
    }

    @Test fun addonStatusIsNotATitle() {
        assertEquals(true, isAddonMessage("Trakt Authentication Required"))
        assertEquals(false, isAddonMessage("The Authentication"))
    }

    @Test fun myListKeepsTheLastPartialRow() {
        assertEquals(0, listRowCount(0, 6))
        assertEquals(1, listRowCount(6, 6))
        assertEquals(2, listRowCount(7, 6))
        assertEquals(4, listRowCount(24, 6))
    }

    @Test fun catalogsContinueAfterTheFirstTwentyInSmallBatches() {
        assertEquals(4, nextCatalogSection(37, 0))
        assertEquals(8, nextCatalogSection(37, 4))
        assertEquals(37, nextCatalogSection(37, 36))
    }

    @Test fun catalogAnswersAreNotReusedAfterTheirSourceChanges() {
        val first = catalogAnswerKey("Shows", "Popular", null, listOf("addon-a/series/popular"))
        assertEquals(first, catalogAnswerKey("Shows", "Popular", null, listOf("addon-a/series/popular")))
        assertEquals(false, first == catalogAnswerKey("Shows", "Popular", null, listOf("addon-b/series/popular")))
        assertEquals(false, first == catalogAnswerKey("Shows", "Popular", "Drama", listOf("addon-a/series/popular")))
    }

    @Test fun shortEpisodePlaceholdersCannotAdvanceTheSeries() {
        assertEquals(false, plausibleEpisode(30_000L))
        assertEquals(false, plausibleEpisode(119_999L))
        assertEquals(true, plausibleEpisode(120_000L))
    }

    @Test fun settingsChoicesWrapAndCustomStartsAtTheEndForLeft() {
        assertEquals(0, nextSettingIndex(-1, 4, 1))
        assertEquals(3, nextSettingIndex(-1, 4, -1))
        assertEquals(0, nextSettingIndex(3, 4, 1))
        assertEquals(3, nextSettingIndex(0, 4, -1))
    }

    @Test fun searchRanksTitleMatchesAndKeepsTiesInAddonOrder() {
        val titles = listOf("Dune Part Two", "Arrival", " DUNE ", "Dune Messiah", "dune")
        assertEquals(listOf(" DUNE ", "dune", "Dune Part Two", "Dune Messiah", "Arrival"),
            rankSearchTitles(titles, "Dune") { it })
        assertEquals(listOf("Amelie", "Amélie Returns", "Arrival"),
            rankSearchTitles(listOf("Arrival", "Amélie Returns", "Amelie"), "Amélie") { it })
    }

    @Test fun partialSearchAnswersKeepAddonOrder() {
        val answers = arrayOf<List<String>?>(null, listOf("later"))
        assertEquals(listOf("later"), searchAnswersInAddonOrder(answers))
        answers[0] = listOf("first")
        assertEquals(listOf("first", "later"), searchAnswersInAddonOrder(answers))
    }

    @Test fun episodeDateUsesOnlyValidAvailableDates() {
        assertEquals("25 Sep 2013", episodeDate("2013-09-25T00:00:00.000Z"))
        assertEquals(null, episodeDate("2013-02-30"))
        assertEquals(null, episodeDate(null))
    }

    @Test fun repeatedBufferingOffersOneRealQualityStep() {
        assertEquals(1080, lowerSourceHeight(2160))
        assertEquals(720, lowerSourceHeight(1080))
        assertEquals(null, lowerSourceHeight(480))
    }

    @Test fun upNextNeverInterruptsMoreThanTheFinalMinute() {
        assertEquals(30_000L, upNextWindow(10 * 60_000L))
        assertEquals(60_000L, upNextWindow(60 * 60_000L))
    }

    @Test fun sourceRowsUseFileFactsWithoutShowingAddonDescriptions() {
        val stream = Stream("Torrentio (1080p)", "extra text", "https://example.invalid/video", emptyMap(),
            405_000_000L, "First.Days.1080p.BluRay.HEVC.5.1.mkv", null)
        assertEquals(1080, stream.height)
        assertEquals("BluRay · HEVC · 5.1", sourceSummary(stream))
        assertEquals("405 MB", sourceSize(stream.size))
        assertEquals(480, Stream("480p", "", "https://example.invalid/low", emptyMap(), 175_000_000L, null, null).height)
    }

    @Test fun subtitlesFollowTheSoundWhenItIsNotTheViewersLanguage() {
        // The player's rule: a track tagged in another language turns subtitles on; the same language (from another
        // country too) leaves them off; an untagged track decides nothing.
        assertEquals(0, Languages.match("ita", "en"))
        assertEquals(2, Languages.match("eng", "en"))
        assertEquals(1, Languages.match("pt", "pt-BR"))
        assertEquals(0, Languages.match("und", "en"))
        assertEquals("it", Languages.code("Italian"))
        assertEquals(null, Languages.code("Neapolitan"))
    }

    @Test fun everySourceSaysWhyItStandsWhereItDoes() {
        val tv = Device(hevc = true, av1 = false, dolbyVision = false, hdr = false, height = 1080)
        val tastes = Tastes(limit = 1080, maxGb = 10, hdrOn = true, release = "web", hdrPref = "", dub = "", language = "en", receiver = false)
        val big = Stream("[RD+] Torrentio (2160p)", "", "https://example.invalid/a", emptyMap(), 20_000_000_000L, "Film.2160p.WEB-DL.DV.HEVC.mkv", null)
        // Its limits first (why it was set aside), then what lifts it, then this TV, then Settings' tastes.
        assertEquals(listOf("above your 1080p limit", "over your 10 GB limit", "cached on Real-Debrid", "Dolby Vision this TV may not show",
            "above this TV's 1080p", "HDR on a screen without it", "a WEB release, as you prefer"),
            sourceReasons(big, tv, tastes, playedLast = false, sameRelease = false))
        val plain = Stream("x", "", "https://example.invalid/b", emptyMap(), 0, "Film.1080p.BluRay.x264.mkv", null)
        assertEquals(listOf("played last time"), sourceReasons(plain, tv, tastes, playedLast = true, sameRelease = false))
        assertEquals(listOf("the release the last episode used"), sourceReasons(plain, tv, tastes, playedLast = false, sameRelease = true))
        // Nothing tells it apart: the add-on's order, and the list says so rather than inventing a reason.
        assertEquals(emptyList<String>(), sourceReasons(plain, tv, tastes, playedLast = false, sameRelease = false))
    }

    @Test fun sourceSelectionKeepsAddonOrderUntilItsShortWaitEnds() {
        val first = Stream("first", "", "https://example.invalid/first", emptyMap(), 0, null, null)
        val later = Stream("later", "", "https://example.invalid/later", emptyMap(), 0, null, null)
        val answers = arrayOf<List<Stream>?>(null, listOf(later))
        assertEquals(emptyList<Stream>(), readyStreams(answers, false))
        assertEquals(listOf(later), readyStreams(answers, true))
        answers[0] = listOf(first)
        assertEquals(listOf(first, later), readyStreams(answers, false))
    }

    @Test fun addonRowsUseShortNamesAndActualResources() {
        assertEquals("Watchly", addonShortName("Watchly | ElfHosted"))
        assertEquals("OpenSubtitles", addonShortName("OpenSubtitles v3"))
        assertEquals("Local Files", addonShortName("Local Files (without catalog support)"))
        assertEquals("Syncribullet", addonShortName("syncribullet"))
        assertEquals("Files on this network", addonRole("Local Files", "org.stremio.local", setOf("stream", "meta")))
        assertEquals("third", ordinal(3))
        assertEquals("11th", ordinal(11))
        assertEquals("22nd", ordinal(22))
        assertEquals("Recommendations · Top Picks on Home", addonRole("Watchly", "com.watchly", setOf("catalog")))
        assertEquals("Subtitles", addonRole("OpenSubtitles", "org.opensubtitles", setOf("subtitles")))
        assertEquals("Streams · Catalogs", addonRole("Example", "org.example", setOf("stream", "catalog")))
        assertEquals("Titles and details", addonRole("Cinemeta", "com.cinemeta", setOf("catalog", "meta")))
    }
}
