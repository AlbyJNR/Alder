package dev.alder

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Asks IMDb every list CAS shows, with the queries and the parsers the app itself uses, and fails when one comes back
 * wrong or empty. IMDb can change its answers without notice; this runs each week in CI (cas-sources.yml), so a row
 * that has stopped is known before it is missed on the TV. Runs only with CAS_LIVE=1: the ordinary tests stay offline.
 */
class LiveSourcesCheck {
    private fun ask(body: String, region: String): JSONObject {
        val c = java.net.URL("https://api.graphql.imdb.com/").openConnection() as java.net.HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15_000; c.readTimeout = 20_000
        c.doOutput = true
        c.setRequestProperty("User-Agent", "CAS")
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-imdb-client-name", "imdb-web-next-localized")
        c.setRequestProperty("x-imdb-user-country", region)
        c.outputStream.use { it.write(body.toByteArray()) }
        val text = (if (c.responseCode < 400) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
        check(c.responseCode < 400) { "HTTP ${c.responseCode}: ${text.take(200)}" }
        return JSONObject(text).also { j -> check(!j.has("errors")) { j.optJSONArray("errors").toString().take(300) } }
    }

    @Test fun everyImdbListAnswersWithTitles() {
        assumeTrue(System.getenv("CAS_LIVE") == "1")
        val today = java.time.LocalDate.now().toString()
        val wrong = ImdbList.entries.mapNotNull { list ->
            val region = list.region ?: "GB"
            runCatching { parseImdbList(list, ask(imdbListQuery(list, today, region), region)) }
                .fold({ picks -> "$list: no titles".takeIf { picks.isEmpty() } }, { "$list: $it" })
        } + listOfNotNull(runCatching { parseImdbSimilar(ask(imdbSimilarQuery("tt0944947", "GB"), "GB")) }
            .fold({ picks -> "similar titles: none".takeIf { picks.isEmpty() } }, { "similar titles: $it" }))
        assertTrue("IMDb lists that no longer answer:\n" + wrong.joinToString("\n"), wrong.isEmpty())
        // Metacritic's lists, by the app's own address and parser.
        McList.entries.forEach { l ->
            val c = java.net.URL(mcListUrl(l, java.time.LocalDate.now().year - 1)).openConnection() as java.net.HttpURLConnection
            c.setRequestProperty("User-Agent", "CAS"); c.connectTimeout = 15_000; c.readTimeout = 20_000
            assertTrue("Metacritic $l has no titles", parseMcList(JSONObject(c.inputStream.bufferedReader().use { it.readText() })).isNotEmpty())
        }
    }
}
