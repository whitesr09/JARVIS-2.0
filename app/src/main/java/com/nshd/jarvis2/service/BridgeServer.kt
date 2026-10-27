package com.nshd.jarvis2.service

import android.content.Context
import android.content.pm.PackageManager
import com.nshd.jarvis2.brain.AgentPipeline
import com.nshd.jarvis2.brain.TaskQueue
import com.nshd.jarvis2.brain.tools.SendAsk
import com.nshd.jarvis2.core.AppLog
import com.nshd.jarvis2.core.SettingsLoader
import com.nshd.jarvis2.hands.ScreenReader
import com.nshd.jarvis2.memory.MemoryDb
import com.nshd.jarvis2.brain.ProviderStore
import com.nshd.jarvis2.voice.VoiceSessionManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.BindException
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * v2.0.4 "Live Bridge" — a minimal hand-written HTTP/1.1 debug server
 * (Android ships no HTTP server, and the task forbids new dependencies).
 * It exists so the owner's assistant (Muse, on a remote VM) can reach
 * the app directly over the owner's Tailscale tailnet with the
 * `jarvis-cli` tool — no more screenshot relay for QA.
 *
 * SECURITY (all three layers are mandatory, none is optional):
 *  1. Source guard: only loopback and the Tailscale range 100.64/10
 *     are served; anything else gets 403 with an empty body.
 *  2. Bearer token auth on every request (constant-time compare);
 *     missing/wrong token gets 401 with an empty body.
 *  3. Remote send confirmation is a separate opt-in toggle: POST
 *     /answer only resolves a pending SendAsk when the user enabled
 *     "Allow remote send confirmations" in Settings. Off by default.
 *
 * Logging discipline: only method + path + status are logged (tag
 * "Bridge"). The token, Authorization headers, and request bodies are
 * NEVER logged (AppLog.sanitize is also applied to any message that
 * could carry credentials).
 *
 * Threading: the accept loop runs on its own daemon thread; each
 * connection gets a daemon thread; /command turns run on an IO scope
 * and are awaited with a timeout that does NOT cancel the turn (the
 * turn finishes in-app even after the CLI gets its "still running"
 * note). Nothing here ever touches the main thread.
 */
class BridgeServer(private val appContext: Context) {

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var boundPort: Int = 0
        private set

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var tokenProvider: () -> String = { "" }
    private var remoteConfirmAllowed: () -> Boolean = { false }
    private var startMs: Long = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Start listening on all interfaces (required so the tailnet IP
     * is reachable — the source guard, not the bind address, is the
     * access control). Returns false on bind failure (port in use);
     * [lastError] then carries the reason and nothing crashes.
     */
    @Synchronized
    fun start(
        port: Int,
        token: () -> String,
        remoteConfirm: () -> Boolean
    ): Boolean {
        if (running) return true
        tokenProvider = token
        remoteConfirmAllowed = remoteConfirm
        lastError = null
        return try {
            val socket = ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"))
            serverSocket = socket
            boundPort = socket.localPort
            startMs = System.currentTimeMillis()
            running = true
            acceptThread = Thread({
                while (running) {
                    try {
                        val client = socket.accept()
                        Thread({ handle(client) }, "bridge-conn").apply {
                            isDaemon = true
                            start()
                        }
                    } catch (e: Exception) {
                        if (running) {
                            AppLog.w("Bridge", "accept failed: ${e.message ?: "?"}")
                        }
                    }
                }
            }, "bridge-accept").apply {
                isDaemon = true
                start()
            }
            AppLog.i("Bridge", "listening on port $boundPort")
            true
        } catch (e: BindException) {
            lastError = "Port $port is already in use"
            AppLog.w("Bridge", "bind failed: ${e.message ?: "?"}")
            false
        } catch (e: Exception) {
            lastError = e.message ?: "failed to start"
            AppLog.w("Bridge", "start failed: ${e.message ?: "?"}")
            false
        }
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope.cancel()
        AppLog.i("Bridge", "stopped")
    }

    // ── Connection handling ──────────────────────────────────────────

    private fun handle(client: Socket) {
        runCatching { client.soTimeout = 130_000 }
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val out = client.getOutputStream()
        try {
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                send(out, 400, JSONObject().put("error", "bad request").toString())
                return
            }
            val method = parts[0].uppercase()
            val rawTarget = parts[1]
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] =
                        line.substring(idx + 1).trim()
                }
            }
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (contentLength > 0 && contentLength <= 1_048_576) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read)
            } else {
                ""
            }
            val path = rawTarget.substringBefore("?")
            val query = parseQuery(rawTarget.substringAfter("?", ""))
            dispatch(method, path, query, headers, body, client, out)
        } catch (e: Exception) {
            runCatching {
                AppLog.w("Bridge", "handler error: ${e.message ?: "?"}")
            }
        } finally {
            runCatching { client.close() }
        }
    }

    private fun dispatch(
        method: String,
        path: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        body: String,
        client: Socket,
        out: OutputStream
    ) {
        // Layer 1 — source guard (loopback or tailnet only).
        if (!isAllowedSource(client.inetAddress)) {
            sendEmpty(out, 403)
            AppLog.w("Bridge", "$method $path -> 403 (source not allowed)")
            return
        }
        // Layer 2 — bearer token (constant-time compare).
        val presented = headers["authorization"] ?: ""
        val expected = "Bearer " + tokenProvider()
        if (expected == "Bearer " || !constantTimeEquals(presented, expected)) {
            sendEmpty(out, 401)
            AppLog.w("Bridge", "$method $path -> 401")
            return
        }
        var code = 0
        val payload: String = try {
            when {
                method == "GET" && path == "/health" -> {
                    code = 200
                    JSONObject()
                        .put("ok", true)
                        .put("version", appVersionName())
                        .toString()
                }
                method == "GET" && path == "/status" -> {
                    code = 200
                    statusJson().toString()
                }
                method == "GET" && path == "/logs" -> {
                    code = 200
                    logsJson(
                        tag = query["tag"],
                        n = query["n"]?.toIntOrNull() ?: 100
                    ).toString()
                }
                method == "GET" && path == "/tasks" -> {
                    code = 200
                    tasksJson().toString()
                }
                method == "GET" && path == "/screen" -> {
                    code = 200
                    screenJson().toString()
                }
                method == "POST" && path == "/command" -> {
                    val result = commandJson(body)
                    code = result.first
                    result.second
                }
                method == "POST" && path == "/answer" -> {
                    val result = answerJson(body)
                    code = result.first
                    result.second
                }
                else -> {
                    code = 404
                    JSONObject().put("error", "unknown route").toString()
                }
            }
        } catch (e: Exception) {
            code = 500
            JSONObject()
                .put("error", "bridge error: ${e.message ?: "?"}")
                .toString()
        }
        send(out, code, payload)
        // Log discipline: method + path + status only. Never bodies,
        // never headers, never the token.
        AppLog.i("Bridge", "$method $path -> $code")
    }

    // ── Endpoints ────────────────────────────────────────────────────

    private fun appVersionName(): String = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
    }.getOrDefault("?") ?: "?"

    private fun appVersionCode(): Long = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).longVersionCode
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionCode.toLong()
        }
    }.getOrDefault(0L)

    private fun statusJson(): JSONObject {
        val settings = runCatching { SettingsLoader.load(appContext) }.getOrNull()
        val voiceStatus = runCatching {
            VoiceSessionManager.uiState.value.status.name
        }.getOrDefault("UNKNOWN")
        val uptimeSec = if (startMs > 0) {
            (System.currentTimeMillis() - startMs) / 1000
        } else {
            0
        }
        return JSONObject()
            .put("version", appVersionName())
            .put("versionCode", appVersionCode())
            .put("voiceEngine", settings?.voiceEngine ?: "?")
            .put("voiceStatus", voiceStatus)
            .put("overlayRunning", FloatingOverlayService.isRunning())
            .put("accessibilityOn", JarvisAccessibilityService.instance != null)
            .put("bridgeUptimeSeconds", uptimeSec)
            .put("bridgePort", boundPort)
            .put("tailnetIp", tailnetIp() ?: JSONObject.NULL)
    }

    private fun logsJson(tag: String?, n: Int): JSONObject {
        val count = n.coerceIn(1, 500)
        val lines = AppLog.entries.value
            .let { all -> if (tag.isNullOrBlank()) all else all.filter { it.tag == tag } }
            .takeLast(count)
            .map { entry ->
                JSONObject()
                    .put("timeMs", entry.timeMs)
                    .put("level", entry.level.label)
                    .put("tag", entry.tag)
                    // AppLog sanitizes on the way in; re-sanitize here
                    // so a bridge response can never carry a credential.
                    .put("message", AppLog.sanitize(entry.message))
            }
        return JSONObject().put("lines", JSONArray(lines))
    }

    private fun tasksJson(): JSONObject {
        fun itemJson(item: com.nshd.jarvis2.brain.TaskItem): JSONObject =
            JSONObject()
                .put("id", item.id)
                .put("title", item.title)
                .put("status", item.status.name)
                .put("step", item.step)
                .put("resultLine", item.resultLine)
                .put("progress", TaskQueue.planPercent(item))
        return JSONObject()
            .put("current", JSONArray(TaskQueue.tasks.value.map { itemJson(it) }))
            .put("history", JSONArray(TaskQueue.history.value.map { itemJson(it) }))
    }

    private fun screenJson(): JSONObject {
        val service = JarvisAccessibilityService.instance
            ?: return JSONObject()
                .put("available", false)
                .put("reason", "accessibility service is off")
        return runCatching {
            val text = ScreenReader(service).serializeCompact()
            JSONObject().put("available", true).put("elements", text)
        }.getOrElse {
            JSONObject()
                .put("available", false)
                .put("reason", it.message ?: "screen read failed")
        }
    }

    /**
     * Runs [body]'s "text" through the same AgentPipeline chat path a
     * typed chat message uses. The wait is capped at 120 s; a timeout
     * does NOT cancel the turn — it keeps running in-app and the CLI
     * gets {"accepted": true} so it can poll /tasks + /logs.
     */
    private fun commandJson(body: String): Pair<Int, String> {
        val text = runCatching { JSONObject(body).optString("text", "") }.getOrDefault("")
        if (text.isBlank()) {
            return 400 to JSONObject().put("error", "missing text").toString()
        }
        val deferred = CompletableDeferred<String>()
        scope.launch {
            try {
                val settings = SettingsLoader.load(appContext)
                val reply = AgentPipeline.handleUserText(
                    context = appContext,
                    text = text,
                    settings = settings,
                    memoryDb = MemoryDb(appContext),
                    providerStore = ProviderStore(appContext),
                    codeMode = false,
                    onEvent = {},
                    onAssistantDelta = {}
                )
                deferred.complete(reply)
            } catch (e: Exception) {
                deferred.completeExceptionally(e)
            }
        }
        // runBlocking is safe here: this handler thread is a bridge
        // worker thread, never the main thread.
        val reply = kotlinx.coroutines.runBlocking {
            withTimeoutOrNull(120_000L) {
                runCatching { deferred.await() }.getOrNull()
            }
        }
        return if (reply != null) {
            val tasks = tasksJson()
            200 to JSONObject()
                .put("reply", reply)
                .put("tasks", tasks.getJSONArray("current"))
                .toString()
        } else {
            202 to JSONObject()
                .put("accepted", true)
                .put("note", "still running — poll /tasks and /logs")
                .toString()
        }
    }

    /**
     * Layer 3 — remote send confirmation. Only resolves a pending
     * SendAsk when the user explicitly enabled it; otherwise 403.
     * A "yes" completes the ask exactly like an on-device yes (the
     * SendPermit path downstream is unchanged).
     */
    private fun answerJson(body: String): Pair<Int, String> {
        if (!remoteConfirmAllowed()) {
            return 403 to JSONObject()
                .put("error", "remote send confirmation is off — confirm on the phone")
                .toString()
        }
        val answer = runCatching {
            JSONObject(body).optString("answer", "")
        }.getOrDefault("").trim().lowercase()
        if (answer != "yes" && answer != "no") {
            return 400 to JSONObject().put("error", "answer must be yes or no").toString()
        }
        if (SendAsk.pending.value == null) {
            return 409 to JSONObject().put("error", "no pending send ask").toString()
        }
        SendAsk.answer(answer)
        return 200 to JSONObject()
            .put("answered", true)
            .put("answer", answer)
            .toString()
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val out = mutableMapOf<String, String>()
        for (pair in raw.split("&")) {
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val key = urlDecode(pair.substring(0, idx))
                val value = urlDecode(pair.substring(idx + 1))
                out[key] = value
            }
        }
        return out
    }

    private fun urlDecode(s: String): String = runCatching {
        java.net.URLDecoder.decode(s, "UTF-8")
    }.getOrDefault(s)

    private fun isAllowedSource(address: InetAddress): Boolean {
        if (address.isLoopbackAddress) return true
        val bytes = address.address
        // Tailscale CGNAT range 100.64.0.0/10: first octet 100, second
        // octet's top two bits 01 (64–127).
        return bytes.size == 4 &&
            (bytes[0].toInt() and 0xFF) == 100 &&
            (bytes[1].toInt() and 0xC0) == 0x40
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(
            a.toByteArray(Charsets.UTF_8),
            b.toByteArray(Charsets.UTF_8)
        )

    private fun send(out: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code ${reason(code)}\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        runCatching {
            out.write(header.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        }
    }

    private fun sendEmpty(out: OutputStream, code: Int) {
        val header = "HTTP/1.1 $code ${reason(code)}\r\n" +
            "Content-Length: 0\r\n" +
            "Connection: close\r\n\r\n"
        runCatching {
            out.write(header.toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        202 -> "Accepted"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        409 -> "Conflict"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    companion object {
        /** Random 32-char token (letters + digits) for bridge auth. */
        fun generateToken(): String {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            val random = SecureRandom()
            return buildString {
                repeat(32) { append(alphabet[random.nextInt(alphabet.length)]) }
            }
        }

        /** First 100.64/10 address on this device, or null when the
         * device is not on a tailnet. Used by Settings + /status. */
        fun tailnetIp(): String? {
            return runCatching {
                NetworkInterface.getNetworkInterfaces()?.toList()
                    ?.flatMap { iface -> iface.inetAddresses.toList() }
                    ?.firstOrNull { addr ->
                        val bytes = addr.address
                        bytes.size == 4 &&
                            (bytes[0].toInt() and 0xFF) == 100 &&
                            (bytes[1].toInt() and 0xC0) == 0x40
                    }?.hostAddress
            }.getOrNull()
        }
    }
}
