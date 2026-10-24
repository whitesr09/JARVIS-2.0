package com.nshd.jarvis2.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.provider.CalendarContract
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.PersonalVideo
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nshd.jarvis2.JarvisSettings
import com.nshd.jarvis2.brain.PersonaPrompts
import com.nshd.jarvis2.voice.VoiceSessionManager
import com.nshd.jarvis2.voice.VoiceStatus
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

@Composable
fun HomeScreen(
    settings: JarvisSettings,
    listening: Boolean,
    onOpenPermissions: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenVoice: () -> Unit,
    onSettingsChange: (JarvisSettings) -> Unit = {},
    onOpenChat: () -> Unit = {}
) {
    // The Home orb + status pill mirror the live voice session state.
    val voiceState by VoiceSessionManager.uiState.collectAsState()
    // F7: the Home orb is a LIVE orb — amplitude from the process-wide
    // session (a background/overlay session listening shows here too)
    // and a RUNNING task shows THINKING via orbStateFor.
    val voiceAmplitude by VoiceSessionManager.amplitude.collectAsState()
    val queueTasks by com.nshd.jarvis2.brain.TaskQueue.tasks.collectAsState()
    val taskRunning = queueTasks.any {
        it.status == com.nshd.jarvis2.brain.TaskStatus.RUNNING
    }
    var showLanguageDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = JarvisDimens.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(JarvisDimens.spaceMd))

        // ── Header: chat shortcut · gradient wordmark + greeting · settings ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onOpenChat) {
                Icon(
                    imageVector = Icons.Filled.Chat,
                    contentDescription = "Open chat",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = settings.assistantName,
                    style = TextStyle(
                        brush = Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.secondary,
                                MaterialTheme.colorScheme.tertiary
                            )
                        ),
                        fontFamily = MaterialTheme.typography.titleLarge.fontFamily,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                )
                val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
                val dayPart = when {
                    hour < 12 -> "morning"
                    hour < 17 -> "afternoon"
                    else -> "evening"
                }
                Text(
                    text = "Good $dayPart, ${settings.callsign}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Open settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(JarvisDimens.spaceMd))
        A11yWarningBanner(onFix = onOpenSettings)
        Spacer(modifier = Modifier.height(JarvisDimens.spaceLg))

        // F4: the Canvas orb (orb.html system) at hero size — state
        // mapped from the live voice session via toOrbState()
        // (CONNECTING renders as THINKING;
        // LISTENING); the legacy `listening` flag still forces LISTENING.
        // F7: BIGGER (min(screenWidth × 0.92, 420dp)) and state via
        // the single orbStateFor helper — task activity shows as
        // THINKING, background listening/speaking pulse with the live
        // amplitude, exactly like the Voice screen.
        androidx.compose.foundation.layout.BoxWithConstraints {
            val orbSize = minOf(maxWidth * 0.92f, 420.dp)
            JarvisOrb(
                state = if (listening) {
                    OrbState.LISTENING
                } else {
                    orbStateFor(voiceState.status, taskRunning)
                },
                amplitude = voiceAmplitude,
                liveAudio = voiceState.status != VoiceStatus.IDLE,
                // #91: orb colour follows persona — Girlfriend Mode gets
                // a warm pink CUSTOM palette (JarvisOrb derives its orb
                // palette from theme + customHex); any other persona
                // keeps the user's own theme / custom hex untouched.
                // Home orb only — no other screen's orb changes.
                theme = if (settings.persona == PersonaPrompts.PERSONA_GIRLFRIEND) {
                    AppTheme.CUSTOM
                } else {
                    settings.theme
                },
                customHex = if (settings.persona == PersonaPrompts.PERSONA_GIRLFRIEND) {
                    "#FF7BAC"
                } else {
                    settings.customBgHex
                },
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(orbSize)
                    .clickable(onClick = onOpenVoice)
            )
        }

        Spacer(modifier = Modifier.height(JarvisDimens.spaceLg))
        StatusPill(
            status = voiceState.status,
            assistantName = settings.assistantName,
            onClick = onOpenVoice
        )
        if (voiceState.status == VoiceStatus.IDLE) {
            Spacer(modifier = Modifier.height(JarvisDimens.spaceSm))
            Text(
                text = "Tap mic or orb to power on",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(JarvisDimens.space2xl))

        WeatherCard()
        Spacer(modifier = Modifier.height(JarvisDimens.spaceMd))

        // #90: next calendar event + live gold price, display-only.
        HomeInfoRow()
        Spacer(modifier = Modifier.height(JarvisDimens.spaceLg))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(JarvisDimens.spaceMd)
        ) {
            QuickTile(
                icon = Icons.Filled.PersonalVideo,
                title = "Screen",
                subtitle = "Share screen",
                modifier = Modifier.weight(1f)
            ) { onOpenVoice() }
            QuickTile(
                icon = Icons.Filled.Layers,
                title = "Memories",
                subtitle = "Recall Core",
                modifier = Modifier.weight(1f)
            ) { onOpenMemory() }
            QuickTile(
                icon = Icons.Filled.Language,
                title = "Language",
                subtitle = settings.language,
                modifier = Modifier.weight(1f)
            ) { showLanguageDialog = true }
        }
        // Bottom clearance so content never ends behind the floating nav (F2/F4).
        Spacer(modifier = Modifier.height(120.dp))
    }

    if (showLanguageDialog) {
        // The dialog reports picks immediately and stays open until
        // Done / outside tap (its onDismiss) — the tile subtitle and
        // the radio selection update live from the new settings.
        LanguagePickerDialog(
            current = settings.language,
            onDismiss = { showLanguageDialog = false },
            onPick = { picked ->
                onSettingsChange(settings.copy(language = picked))
            }
        )
    }
}

/**
 * F4 status pill: glass surface + hairline border, a state-coloured
 * dot (pulsing while a session is live) and a letterspaced 11sp bold
 * label. Tap = open Voice (start when off, retry on error).
 */
@Composable
private fun StatusPill(
    status: VoiceStatus,
    assistantName: String,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val dotColor: Color
    val label: String
    val pulsing: Boolean
    when (status) {
        VoiceStatus.IDLE -> {
            dotColor = scheme.onSurfaceVariant
            label = "${assistantName.uppercase()} OFF • TAP TO START"
            pulsing = false
        }
        VoiceStatus.CONNECTING -> {
            dotColor = scheme.primary
            label = "CONNECTING…"
            pulsing = true
        }
        VoiceStatus.LISTENING -> {
            dotColor = scheme.primary
            label = "LISTENING"
            pulsing = true
        }
        VoiceStatus.THINKING -> {
            dotColor = scheme.tertiary
            label = "THINKING…"
            pulsing = false
        }
        VoiceStatus.SPEAKING -> {
            dotColor = scheme.secondary
            label = "SPEAKING"
            pulsing = true
        }
        VoiceStatus.ERROR -> {
            dotColor = scheme.error
            label = "SOMETHING'S WRONG — TAP TO RETRY"
            pulsing = false
        }
    }
    val pulse = rememberInfiniteTransition(label = "statusDotPulse")
    val dotAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "statusDotAlpha"
    )
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = dotColor.copy(alpha = if (pulsing) dotAlpha else 1f),
                        shape = CircleShape
                    )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = TextStyle(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// ── #89/#90 Home live data (cache, HTTP, location, calendar) ─────────

private const val HOME_CACHE_PREFS = "home_cache"
private const val STALE_MS = 2L * 60L * 60L * 1000L
private const val KOCHI_LAT = 10.0159
private const val KOCHI_LON = 76.3419
private const val GRAM_PER_TROY_OUNCE = 31.1035

private val homeHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
}

/** Blocking GET (call on Dispatchers.IO); null on any failure. */
private fun httpGetText(url: String): String? = runCatching {
    val request = Request.Builder()
        .url(url)
        .header("User-Agent", "Jarvis/1.8")
        .build()
    homeHttpClient.newCall(request).execute().use { response ->
        if (response.isSuccessful) response.body?.string() else null
    }
}.getOrNull()

private data class WeatherData(
    val temp: Double,
    val label: String,
    val emoji: String,
    val min: Double,
    val max: Double,
    val city: String,
    val fetchedAt: Long
)

private fun weatherCodeToLabel(code: Int): Pair<String, String> = when (code) {
    0 -> "Clear sky" to "☀️"
    1 -> "Mainly clear" to "🌤️"
    2 -> "Partly cloudy" to "⛅"
    3 -> "Overcast" to "☁️"
    45, 48 -> "Foggy" to "🌫️"
    51, 53, 55 -> "Drizzle" to "🌦️"
    56, 57 -> "Freezing drizzle" to "🌧️"
    61, 63, 65 -> "Rain" to "🌧️"
    66, 67 -> "Freezing rain" to "🌧️"
    71, 73, 75, 77 -> "Snow" to "❄️"
    80, 81, 82 -> "Rain showers" to "🌦️"
    85, 86 -> "Snow showers" to "❄️"
    95 -> "Thunderstorm" to "⛈️"
    96, 99 -> "Thunderstorm with hail" to "⛈️"
    else -> "Weather" to "🌡️"
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) ==
        PackageManager.PERMISSION_GRANTED

/** (lat, lon, city label): last-known location when permitted, else Kochi. */
private fun resolveHomeLocation(context: Context): Triple<Double, Double, String> {
    val granted = hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ||
        hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    if (granted) {
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE)
                as? LocationManager
            val location = lm?.let { manager ->
                listOf(
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.GPS_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER
                ).firstNotNullOfOrNull { provider ->
                    runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
                }
            }
            if (location != null) {
                return Triple(location.latitude, location.longitude, "Current location")
            }
        } catch (e: SecurityException) {
            // Fall through to the Kochi default below.
        }
    }
    return Triple(KOCHI_LAT, KOCHI_LON, "Kochi")
}

private fun readWeatherCache(context: Context): WeatherData? {
    val prefs = context.getSharedPreferences(HOME_CACHE_PREFS, Context.MODE_PRIVATE)
    val fetchedAt = prefs.getLong("weather_fetchedAt", 0L)
    if (fetchedAt == 0L || !prefs.contains("weather_temp")) return null
    return WeatherData(
        temp = prefs.getFloat("weather_temp", 0f).toDouble(),
        label = prefs.getString("weather_label", "Weather") ?: "Weather",
        emoji = prefs.getString("weather_emoji", "🌡️") ?: "🌡️",
        min = prefs.getFloat("weather_min", 0f).toDouble(),
        max = prefs.getFloat("weather_max", 0f).toDouble(),
        city = prefs.getString("weather_city", "Kochi") ?: "Kochi",
        fetchedAt = fetchedAt
    )
}

private fun writeWeatherCache(context: Context, data: WeatherData) {
    context.getSharedPreferences(HOME_CACHE_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putFloat("weather_temp", data.temp.toFloat())
        .putString("weather_label", data.label)
        .putString("weather_emoji", data.emoji)
        .putFloat("weather_min", data.min.toFloat())
        .putFloat("weather_max", data.max.toFloat())
        .putString("weather_city", data.city)
        .putLong("weather_fetchedAt", data.fetchedAt)
        .apply()
}

/** Blocking live-weather fetch (call on Dispatchers.IO); null on failure. */
private fun fetchWeather(context: Context): WeatherData? = runCatching {
    val (lat, lon, city) = resolveHomeLocation(context)
    val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
        "&current=temperature_2m,weather_code" +
        "&daily=temperature_2m_max,temperature_2m_min&timezone=auto"
    val body = httpGetText(url) ?: return null
    val json = JSONObject(body)
    val current = json.getJSONObject("current")
    val temp = current.getDouble("temperature_2m")
    val code = current.optInt("weather_code", -1)
    val (label, emoji) = weatherCodeToLabel(code)
    val daily = json.optJSONObject("daily")
    val max = daily?.optJSONArray("temperature_2m_max")
        ?.optDouble(0, temp) ?: temp
    val min = daily?.optJSONArray("temperature_2m_min")
        ?.optDouble(0, temp) ?: temp
    WeatherData(
        temp = temp,
        label = label,
        emoji = emoji,
        min = min,
        max = max,
        city = city,
        fetchedAt = System.currentTimeMillis()
    )
}.getOrNull()

/**
 * #89 — live weather card (Open-Meteo, no API key). Shows the last
 * successful result from the "home_cache" prefs instantly, refreshes
 * in the background, and falls back to that cache (with an "offline"
 * hint once it is stale > 2h) when the fetch fails. Never blank:
 * with no cache and no network it shows a plain unavailable line.
 */
@Composable
private fun WeatherCard() {
    val context = LocalContext.current
    var weather by remember { mutableStateOf(readWeatherCache(context)) }
    var loading by remember { mutableStateOf(weather == null) }
    var fetchFailed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val fresh = withContext(Dispatchers.IO) { fetchWeather(context) }
        if (fresh != null) {
            weather = fresh
            fetchFailed = false
            withContext(Dispatchers.IO) { writeWeatherCache(context, fresh) }
        } else {
            fetchFailed = true
        }
        loading = false
    }

    val data = weather
    val stale = data != null &&
        (System.currentTimeMillis() - data.fetchedAt) > STALE_MS
    val offlineSuffix = if (fetchFailed && stale) " • offline" else ""
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(JarvisDimens.spaceXl),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (data != null) "${data.temp.toInt()}°" else "--°",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(JarvisDimens.spaceLg))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        data != null -> data.label
                        loading -> "Loading weather…"
                        else -> "Weather unavailable"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = data?.city ?: "Kochi",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = when {
                        data != null ->
                            "H ${data.max.toInt()}°  L ${data.min.toInt()}°" +
                                offlineSuffix
                        fetchFailed -> "Offline — will retry next visit"
                        else -> "Fetching live weather…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (loading && data == null) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            } else {
                Text(text = data?.emoji ?: "🌡️", fontSize = 34.sp)
            }
        }
    }
}

// ── #90: calendar + gold small cards ─────────────────────────────────

private sealed interface NextEventResult {
    data object NoPermission : NextEventResult
    data object None : NextEventResult
    data class Found(val title: String, val beginMs: Long) : NextEventResult
}

/** Blocking calendar query (call on Dispatchers.IO); never throws. */
private fun queryNextEvent(context: Context): NextEventResult {
    if (!hasPermission(context, Manifest.permission.READ_CALENDAR)) {
        return NextEventResult.NoPermission
    }
    return try {
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(now.toString())
            .appendPath((now + 7L * 24L * 60L * 60L * 1000L).toString())
            .build()
        context.contentResolver.query(
            uri,
            arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN),
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val title = cursor.getString(0)?.takeIf { it.isNotBlank() }
                    ?: "Untitled event"
                NextEventResult.Found(title, cursor.getLong(1))
            } else {
                NextEventResult.None
            }
        } ?: NextEventResult.None
    } catch (e: SecurityException) {
        NextEventResult.NoPermission
    } catch (e: Exception) {
        NextEventResult.None
    }
}

/** "Today 5:00 PM" / "Tomorrow …" / "Mon 27 Oct 5:00 PM". */
private fun formatEventWhen(beginMs: Long): String {
    val time = SimpleDateFormat("h:mm a", Locale.US).format(Date(beginMs))
    val eventDay = Calendar.getInstance().apply { timeInMillis = beginMs }
    val today = Calendar.getInstance()
    val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
    fun sameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    return when {
        sameDay(eventDay, today) -> "Today $time"
        sameDay(eventDay, tomorrow) -> "Tomorrow $time"
        else -> SimpleDateFormat("EEE d MMM", Locale.US).format(Date(beginMs)) +
            " $time"
    }
}

/** Blocking gold fetch (call on Dispatchers.IO): INR, else USD × FX. */
private fun fetchGoldPerGram(): Double? {
    runCatching {
        val body = httpGetText("https://data-asg.goldprice.org/dbXRates/INR")
        val perOz = body?.let {
            JSONObject(it).getJSONArray("items").getJSONObject(0)
                .getDouble("xauPrice")
        }
        if (perOz != null && perOz > 0) return perOz / GRAM_PER_TROY_OUNCE
    }
    return runCatching {
        val usdBody = httpGetText("https://data-asg.goldprice.org/dbXRates/USD")
            ?: return null
        val usdPerOz = JSONObject(usdBody).getJSONArray("items")
            .getJSONObject(0).getDouble("xauPrice")
        val fxBody = httpGetText("https://open.er-api.com/v6/latest/USD")
            ?: return null
        val inrRate = JSONObject(fxBody).getJSONObject("rates").getDouble("INR")
        if (usdPerOz > 0 && inrRate > 0) {
            usdPerOz * inrRate / GRAM_PER_TROY_OUNCE
        } else {
            null
        }
    }.getOrNull()
}

@Composable
private fun HomeInfoRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(JarvisDimens.spaceMd)
    ) {
        CalendarCard(modifier = Modifier.weight(1f))
        GoldCard(modifier = Modifier.weight(1f))
    }
}

/** Shared shell for the two #90 small cards (display-only, no tap). */
@Composable
private fun SmallInfoCard(
    icon: ImageVector,
    title: String,
    line1: String,
    line2: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = line1,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = line2,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CalendarCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var result by remember { mutableStateOf<NextEventResult?>(null) }
    LaunchedEffect(Unit) {
        result = withContext(Dispatchers.IO) { queryNextEvent(context) }
    }
    val (line1, line2) = when (val r = result) {
        null -> "Checking…" to ""
        NextEventResult.NoPermission ->
            "Calendar — grant access in Permissions" to ""
        NextEventResult.None -> "Nothing coming up" to "Next 7 days are clear"
        is NextEventResult.Found ->
            "${formatEventWhen(r.beginMs)} · ${r.title}" to "Next event"
    }
    SmallInfoCard(
        icon = Icons.Filled.Event,
        title = "Calendar",
        line1 = line1,
        line2 = line2,
        modifier = modifier
    )
}

@Composable
private fun GoldCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var perGram by remember {
        mutableStateOf<Double?>(
            context.getSharedPreferences(HOME_CACHE_PREFS, Context.MODE_PRIVATE)
                .let { prefs ->
                    if (prefs.contains("gold_perGram")) {
                        prefs.getFloat("gold_perGram", 0f).toDouble()
                            .takeIf { it > 0 }
                    } else {
                        null
                    }
                }
        )
    }
    var fetchFailed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val fresh = withContext(Dispatchers.IO) { fetchGoldPerGram() }
        if (fresh != null) {
            perGram = fresh
            fetchFailed = false
            withContext(Dispatchers.IO) {
                context.getSharedPreferences(HOME_CACHE_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putFloat("gold_perGram", fresh.toFloat())
                    .putLong("gold_fetchedAt", System.currentTimeMillis())
                    .apply()
            }
        } else {
            fetchFailed = true
        }
    }
    val price = perGram
    val (line1, line2) = when {
        price != null -> {
            val formatted = String.format(Locale.US, "%,.0f", price)
            "Gold ₹$formatted/g" to if (fetchFailed) "Last saved price" else "Live • per gram"
        }
        fetchFailed -> "Gold" to "Gold — unavailable offline"
        else -> "Gold" to "Fetching price…"
    }
    SmallInfoCard(
        icon = Icons.Filled.MonetizationOn,
        title = "Gold price",
        line1 = line1,
        line2 = line2,
        modifier = modifier
    )
}

@Composable
private fun QuickTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
