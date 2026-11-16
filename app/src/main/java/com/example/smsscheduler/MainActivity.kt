package com.example.smsscheduler

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt

// ---------- Helpers ----------
fun toFa(v: Any): String =
    v.toString().map { if (it in '0'..'9') '۰' + (it - '0') else it }.joinToString("")

fun granted(ctx: Context, p: String) =
    ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

data class Parsed(val valid: List<String>, val bad: Int)

fun parseNumbers(text: String): Parsed {
    val en = buildString {
        text.forEach { c ->
            append(
                when (c) {
                    in '۰'..'۹' -> '0' + (c - '۰')
                    in '٠'..'٩' -> '0' + (c - '٠')
                    else -> c
                }
            )
        }
    }
    val tokens = en.split(Regex("[\\n,;،\\s]+")).filter { it.isNotBlank() }
    val seen = LinkedHashSet<String>()
    var bad = 0
    for (t in tokens) {
        var d = t.filter { it in '0'..'9' }
        d = when {
            d.startsWith("0098") -> "0" + d.drop(4)
            d.startsWith("98") && d.length == 12 -> "0" + d.drop(2)
            d.length == 10 && d.startsWith("9") -> "0$d"
            else -> d
        }
        if (Regex("^09\\d{9}$").matches(d)) seen.add(d) else bad++
    }
    return Parsed(seen.toList(), bad)
}

data class Sim(val subId: Int, val label: String)

@SuppressLint("MissingPermission")
fun loadSims(ctx: Context): List<Sim> {
    val def = Sim(-1, "پیش‌فرض")
    if (!granted(ctx, Manifest.permission.READ_PHONE_STATE)) return listOf(def)
    return try {
        val sm = ctx.getSystemService(SubscriptionManager::class.java)
        val l = sm.activeSubscriptionInfoList.orEmpty()
        if (l.isEmpty()) listOf(def)
        else l.sortedBy { it.simSlotIndex }
            .map { Sim(it.subscriptionId, "سیم‌کارت ${toFa(it.simSlotIndex + 1)} (${it.displayName})") }
    } catch (e: Exception) {
        listOf(def)
    }
}

// ---------- Theme ----------
private val LightScheme = lightColorScheme(
    primary = Color(0xFF0E6B66), onPrimary = Color.White,
    primaryContainer = Color(0xFFCFEDEA), onPrimaryContainer = Color(0xFF00201E),
    background = Color(0xFFF7FAF9), surface = Color(0xFFF7FAF9),
    onSurface = Color(0xFF14201F), onSurfaceVariant = Color(0xFF5B6B69),
    outline = Color(0xFFD3DEDC), error = Color(0xFFB3261E)
)
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF6FD6CD), onPrimary = Color(0xFF00201E),
    primaryContainer = Color(0xFF14403D), onPrimaryContainer = Color(0xFFCFEDEA),
    background = Color(0xFF101A19), surface = Color(0xFF101A19),
    onSurface = Color(0xFFE4EEEC), onSurfaceVariant = Color(0xFF93A6A3),
    outline = Color(0xFF2A3C3A), error = Color(0xFFFFB4AB)
)

@Composable fun cardColor() = if (isSystemInDarkTheme()) Color(0xFF172423) else Color.White
@Composable fun okColor() = if (isSystemInDarkTheme()) Color(0xFF6BD99B) else Color(0xFF1B7F4B)
@Composable fun warnFg() = if (isSystemInDarkTheme()) Color(0xFFF2B35C) else Color(0xFF9A5B00)
@Composable fun warnBg() = if (isSystemInDarkTheme()) Color(0xFF3B2D12) else Color(0xFFFFE9C7)

// ---------- Activity ----------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Root()
                    }
                }
            }
        }
    }
}

@Composable
fun Root() {
    val st by SendState.state.collectAsState()
    if (st.active || st.finished) ProgressScreen(st) else ComposeScreen()
}

@Composable
fun Section(content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor()),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) { Column(Modifier.padding(14.dp), content = content) }
}

@Composable
fun TitleRow(title: String, badge: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        if (badge != null) {
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Text(
                    badge, Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

// ---------- Screen 1 ----------
@Composable
fun ComposeScreen() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("draft", Context.MODE_PRIVATE) }
    var numbersText by remember { mutableStateOf(prefs.getString("nums", "") ?: "") }
    var message by remember { mutableStateOf(prefs.getString("msg", "") ?: "") }
    var gap by remember { mutableFloatStateOf(prefs.getInt("gap", 30).toFloat()) }
    var sims by remember { mutableStateOf(loadSims(ctx)) }
    var simIdx by remember { mutableIntStateOf(0) }

    val gapSec = gap.roundToInt()
    val parsed = remember(numbersText) { parseNumbers(numbersText) }
    val info = remember(message) {
        if (message.isEmpty()) intArrayOf(0, 0, 0, 0) else SmsMessage.calculateLength(message, false)
    }
    val unicode = info[3] == 3

    val perms = remember {
        buildList {
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        sims = loadSims(ctx)
        if (!granted(ctx, Manifest.permission.SEND_SMS)) {
            Toast.makeText(ctx, "برای ارسال، مجوز پیامک را بدهید", Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(Unit) {
        val miss = perms.filter { !granted(ctx, it) }
        if (miss.isNotEmpty()) launcher.launch(miss.toTypedArray())
    }
    LaunchedEffect(numbersText, message, gapSec) {
        prefs.edit().putString("nums", numbersText).putString("msg", message).putInt("gap", gapSec).apply()
    }

    val pm = ctx.getSystemService(PowerManager::class.java)
    val ignoringBattery = pm.isIgnoringBatteryOptimizations(ctx.packageName)

    fun startSend() {
        val sim = sims.getOrElse(simIdx) { sims[0] }
        val i = Intent(ctx, SendService::class.java)
            .setAction(SendService.ACT_START)
            .putStringArrayListExtra("numbers", ArrayList(parsed.valid))
            .putExtra("message", message)
            .putExtra("gap", gapSec)
            .putExtra("subId", sim.subId)
            .putExtra("simLabel", sim.label)
        ContextCompat.startForegroundService(ctx, i)
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("ارسال پیامک گروهی", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("فاصله‌دار و خودکار", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Section {
                TitleRow("شماره‌ها", "${toFa(parsed.valid.size)} شماره معتبر")
                OutlinedTextField(
                    value = numbersText, onValueChange = { numbersText = it },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    textStyle = LocalTextStyle.current.copy(
                        textDirection = TextDirection.Ltr, textAlign = TextAlign.Left
                    ),
                    placeholder = { Text("09121234567\n+989351234567") }
                )
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        if (parsed.bad > 0) "${toFa(parsed.bad)} مورد نامعتبر نادیده گرفته شد" else "",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    "جداکننده: خط جدید، کاما یا فاصله",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Section {
                TitleRow("متن پیام", "${toFa(message.length)} کاراکتر · ${toFa(info[0])} پیامک")
                OutlinedTextField(
                    value = message, onValueChange = { message = it },
                    modifier = Modifier.fillMaxWidth().height(130.dp),
                    placeholder = { Text("متن پیام را بنویسید…") }
                )
                if (message.isNotEmpty()) {
                    Text(
                        if (unicode) "متن فارسی: هر پیامک تا ۷۰ کاراکتر" else "متن لاتین: هر پیامک تا ۱۶۰ کاراکتر",
                        Modifier.padding(top = 6.dp),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Section {
                TitleRow("ارسال با سیم‌کارت")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sims.forEachIndexed { idx, s ->
                        FilterChip(
                            selected = simIdx == idx, onClick = { simIdx = idx },
                            label = { Text(s.label, fontSize = 12.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Section {
                TitleRow("فاصله بین دو پیامک")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = gap, onValueChange = { gap = it },
                        valueRange = 5f..180f, steps = 34, modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${toFa(gapSec)} ثانیه", Modifier.padding(start = 12.dp),
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
                    )
                }
                if (parsed.valid.size > 1) {
                    val sec = (parsed.valid.size - 1) * gapSec
                    Text(
                        "زمان کل حدود ${if (sec >= 60) toFa(sec / 60) + " دقیقه و " else ""}${toFa(sec % 60)} ثانیه",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Surface(shape = RoundedCornerShape(12.dp), color = warnBg()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "برای جلوگیری از مسدود شدن خط، فاصله‌ی ۳۰ ثانیه یا بیشتر پیشنهاد می‌شود. " +
                            "فقط برای مخاطبانی بفرستید که رضایت داده‌اند.",
                        fontSize = 12.sp, color = warnFg(), lineHeight = 20.sp
                    )
                    if (!ignoringBattery) {
                        TextButton(onClick = {
                            ctx.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${ctx.packageName}")
                                )
                            )
                        }) { Text("معافیت از بهینه‌سازی باتری (برای ارسال پایدار)", color = warnFg()) }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        Box(Modifier.padding(16.dp)) {
            Button(
                onClick = {
                    if (granted(ctx, Manifest.permission.SEND_SMS)) startSend()
                    else launcher.launch(perms.toTypedArray())
                },
                enabled = parsed.valid.isNotEmpty() && message.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("شروع ارسال", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

// ---------- Screen 2 ----------
@Composable
fun ProgressScreen(st: UiState) {
    val ctx = LocalContext.current
    val total = st.items.size
    val done = st.items.count { it.status != Status.PENDING && it.status != Status.SENDING }
    val okCount = st.items.count { it.status == Status.SENT || it.status == Status.DELIVERED }
    val failCount = st.items.count { it.status == Status.FAILED || (st.finished && it.status == Status.PENDING) }
    val line = when {
        st.finished -> "${toFa(okCount)} موفق · ${toFa(failCount)} ناموفق"
        st.paused -> "متوقف شد — برای ادامه بزنید"
        st.countdown > 0 -> "پیامک بعدی تا ${toFa(st.countdown)} ثانیه دیگر"
        else -> "در حال ارسال…"
    }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (st.finished) "ارسال پایان یافت" else "در حال ارسال",
                fontSize = 20.sp, fontWeight = FontWeight.Bold
            )
            Text(
                "${st.simLabel} · فاصله ${toFa(st.gap)} ثانیه",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Section {
                Text("${toFa(done)} از ${toFa(total)}", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                LinearProgressIndicator(
                    progress = { if (total == 0) 0f else done.toFloat() / total },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                )
                Text(line, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Section {
                st.items.forEachIndexed { idx, it ->
                    val (label, color) = when (it.status) {
                        Status.PENDING ->
                            (if (st.finished) "ارسال نشد" else "در انتظار") to MaterialTheme.colorScheme.onSurfaceVariant
                        Status.SENDING -> "در حال ارسال" to warnFg()
                        Status.SENT -> "ارسال شد" to warnFg()
                        Status.DELIVERED -> "تحویل داده شد" to okColor()
                        Status.FAILED -> "ناموفق" to MaterialTheme.colorScheme.error
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(it.number, fontSize = 14.sp)
                        Text(label, fontSize = 12.sp, color = color, fontWeight = FontWeight.Medium)
                    }
                    if (idx < st.items.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        Box(Modifier.padding(16.dp)) {
            if (st.active) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { SendState.state.update { it.copy(paused = !it.paused) } },
                        modifier = Modifier.weight(1f).height(52.dp)
                    ) { Text(if (st.paused) "ادامه" else "توقف موقت", fontSize = 15.sp) }
                    Button(
                        onClick = {
                            ctx.startService(
                                Intent(ctx, SendService::class.java).setAction(SendService.ACT_STOP)
                            )
                        },
                        modifier = Modifier.weight(1f).height(52.dp)
                    ) { Text("لغو", fontSize = 15.sp) }
                }
            } else {
                Button(
                    onClick = { SendState.state.value = UiState() },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("بازگشت", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}
