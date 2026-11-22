package com.livecast

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Polls YouTube Live Chat or Facebook Live comments every 5s and sends "Name: message" lines. */
class ChatPoller(
    private val src: String, private val id: String, private val token: String,
    private val onLine: (String) -> Unit
) : Thread() {
    @Volatile private var go = true
    var onStatus: (Boolean) -> Unit = {}
    private val seen = HashSet<String>()
    fun stopPolling() { go = false }

    override fun run() {
        while (go) {
            try {
                val u = if (src == "yt")
                    "https://www.googleapis.com/youtube/v3/liveChat/messages?liveChatId=$id&part=snippet,authorDetails"
                else
                    "https://graph.facebook.com/v19.0/$id/comments?fields=from,message&access_token=$token"
                val c = URL(u).openConnection() as HttpURLConnection
                if (src == "yt") c.setRequestProperty("Authorization", "Bearer $token")
                val code = c.responseCode
                if (code !in 200..299) {
                    onStatus(false)
                    try { sleep(5000) } catch (_: InterruptedException) {}
                    continue
                }
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                onStatus(true)
                if (src == "yt") {
                    val a = j.getJSONArray("items")
                    for (k in 0 until a.length()) {
                        val o = a.getJSONObject(k)
                        if (seen.add(o.getString("id")))
                            onLine(o.getJSONObject("authorDetails").getString("displayName") + ": " +
                                o.getJSONObject("snippet").optString("displayMessage"))
                    }
                } else {
                    val a = j.getJSONArray("data")
                    for (k in 0 until a.length()) {
                        val o = a.getJSONObject(k)
                        if (seen.add(o.getString("id")))
                            onLine(o.optJSONObject("from")?.optString("name", "User") + ": " + o.optString("message"))
                    }
                }
            } catch (_: Exception) {
                onStatus(false)
            }
            try { sleep(5000) } catch (_: InterruptedException) {}
        }
    }
}
