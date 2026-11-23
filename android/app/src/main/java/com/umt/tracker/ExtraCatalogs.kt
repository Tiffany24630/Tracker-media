package com.umt.tracker

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Public catalog metadata only; each source can fail independently. */
object ExtraCatalogs {
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.setRequestProperty("User-Agent", "TrackerMedia/1.8 (Android)")
            connection.setRequestProperty("Accept", "application/json")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }

    fun games(query: String): JSONArray {
        val result = JSONArray()
        if (query.isBlank()) return result
        try {
            val data = JSONArray(get("https://www.cheapshark.com/api/1.0/games?title=${enc(query)}&limit=60"))
            val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
            val details = try {
                val futures = (0 until minOf(data.length(), 15)).map { index ->
                    executor.submit(java.util.concurrent.Callable {
                        val appId = data.getJSONObject(index).optString("steamAppID")
                        try {
                            if (appId.isBlank() || appId == "null") JSONObject() else
                                JSONObject(get("https://store.steampowered.com/api/appdetails?appids=${enc(appId)}&l=english"))
                                    .optJSONObject(appId)?.optJSONObject("data") ?: JSONObject()
                        } catch (e: Exception) { JSONObject() }
                    })
                }
                futures.map { it.get() }
            } finally { executor.shutdownNow() }
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val detail = details.getOrNull(i) ?: JSONObject()
                val release = detail.optJSONObject("release_date") ?: JSONObject()
                result.put(JSONObject().apply {
                    put("id", "cheapshark_${item.getString("gameID")}")
                    put("title", item.getString("external"))
                    put("category", "game")
                    put("image_url", item.optString("thumb"))
                    put("source", "cheapshark")
                    put("airing_status", if (release.optBoolean("coming_soon")) "upcoming" else "finished")
                    put("release_year", Regex("(?:19|20)\\d{2}").find(release.optString("date"))?.value?.toIntOrNull() ?: 0)
                    put("synopsis", detail.optString("short_description"))
                    val developers = detail.optJSONArray("developers") ?: JSONArray()
                    put("author", (0 until developers.length()).joinToString(", ") { developers.optString(it) })
                    put("genres", JSONArray().apply {
                        val genres = detail.optJSONArray("genres") ?: JSONArray()
                        for (j in 0 until genres.length()) put(genres.getJSONObject(j).optString("description"))
                    })
                })
            }
        } catch (e: Exception) { android.util.Log.w("CatalogSearch", "CheapShark unavailable", e) }
        return result
    }

    fun mangaDex(query: String, years: List<String> = emptyList()): JSONArray {
        val result = JSONArray()
        try {
            var url = "https://api.mangadex.org/manga?limit=60&includes%5B%5D=cover_art&includes%5B%5D=author&includes%5B%5D=artist"
            if (query.isNotBlank()) url += "&title=${enc(query)}"
            if (years.size == 1) url += "&year=${enc(years.first())}"
            val data = JSONObject(get(url)).optJSONArray("data") ?: return result
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val a = item.getJSONObject("attributes")
                val titles = a.optJSONObject("title") ?: JSONObject()
                val description = a.optJSONObject("description") ?: JSONObject()
                val relations = item.optJSONArray("relationships") ?: JSONArray()
                val creators = mutableSetOf<String>()
                var cover = ""
                for (j in 0 until relations.length()) {
                    val relation = relations.getJSONObject(j)
                    val attrs = relation.optJSONObject("attributes") ?: continue
                    when (relation.optString("type")) {
                        "author", "artist" -> attrs.optString("name").takeIf { it.isNotBlank() }?.let { creators.add(it) }
                        "cover_art" -> cover = "https://uploads.mangadex.org/covers/${item.getString("id")}/${attrs.optString("fileName")}.256.jpg"
                    }
                }
                result.put(JSONObject().apply {
                    put("id", "mangadex_${item.getString("id")}")
                    put("title", titles.optString("en").ifBlank { titles.keys().asSequence().firstOrNull()?.let { titles.optString(it) } ?: "Manga" })
                    put("category", "manga")
                    put("author", creators.joinToString(", "))
                    put("synopsis", description.optString("es").ifBlank { description.optString("en") })
                    put("image_url", cover)
                    put("release_year", a.optInt("year", 0))
                    put("airing_status", a.optString("status"))
                    put("age_rating", if (a.optString("contentRating") in listOf("erotica", "pornographic")) "adult" else "safe")
                    put("genres", JSONArray().apply {
                        val tags = a.optJSONArray("tags") ?: JSONArray()
                        for (j in 0 until tags.length()) put(tags.getJSONObject(j).getJSONObject("attributes").getJSONObject("name").optString("en"))
                    })
                    put("source", "mangadex")
                })
            }
        } catch (e: Exception) { android.util.Log.w("CatalogSearch", "MangaDex unavailable", e) }
        return result
    }

    fun kitsu(query: String): JSONArray {
        val result = JSONArray()
        try {
            val data = JSONObject(get("https://kitsu.io/api/edge/manga?filter%5Btext%5D=${enc(query)}&page%5Blimit%5D=20")).optJSONArray("data") ?: return result
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val a = item.getJSONObject("attributes")
                result.put(JSONObject().apply {
                    put("id", "kitsu_${item.getString("id")}")
                    put("title", a.optString("canonicalTitle"))
                    put("category", "manga")
                    put("synopsis", a.optString("synopsis"))
                    put("image_url", a.optJSONObject("posterImage")?.optString("medium", "") ?: "")
                    put("release_year", a.optString("startDate").take(4).toIntOrNull() ?: 0)
                    put("airing_status", a.optString("status"))
                    put("total_units", a.optInt("chapterCount", 0))
                    put("age_rating", if (a.optString("ageRating") == "R18") "adult" else "safe")
                    put("source", "kitsu")
                    put("genres", JSONArray())
                })
            }
        } catch (e: Exception) { android.util.Log.w("CatalogSearch", "Kitsu unavailable", e) }
        return result
    }
}
