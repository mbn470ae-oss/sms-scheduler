package com.example.smsscheduler

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SmsManager
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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// =====================================================
// بخش ۱: وضعیت و سرویس ارسال
// =====================================================

enum class Status { PENDING, SENDING, SENT, DELIVERED, FAILED }

data class Item(val number: String, val status: Status = Status.PENDING)

data class UiState(
    val active: Boolean = false,
    val paused: Boolean = false,
    val finished: Boolean = false,
    val items: List<Item> = emptyList(),
    val countdown: Int = 0,
    val simLabel: String = "",
    val gap: Int = 30
)

object SendState {
    val state = MutableStateFlow(UiState())

    fun setStatus(i: Int, s: Status) {
        state.update { st ->
            if (i !in st.items.indices) {
                st
            } else {
                val cur = st.items[i].status
                val ok = when (s) {
                    Status.FAILED -> true
                    Status.SENT, Status.SENDING -> cur == Status.PENDING || cur == Status.SENDING
                    Status.DELIVERED -> cur != Status.FAILED
                    Status.PENDING -> true
                }
                if (!ok) {
                    st
                } else {
                    val list = st.items.toMutableList()
                    list[i] = Item(list[i].number, s)
                    st.copy(items = list)
                }
            }
        }
    }
}

class SendService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var tail: Job? = null
    private var wake: PowerManager.WakeLock? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val idx = intent.getIntExtra("idx", -1)
            when (intent.action) {
                ACT_SENT -> SendState.setStatus(
                    idx,
                    if (resultCode == Activity.RESULT_OK) Status.SENT else Status.FAILED
                )
                ACT_DELIVERED -> if (resultCode == Activity.RESULT_OK) {
                    SendState.setStatus(idx, Status.DELIVERED)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel(CH, "ارسال پیامک", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        val f = IntentFilter()
        f.addAction(ACT_SENT)
        f.addAction(ACT_DELIVERED)
        ContextCompat.registerReceiver(this, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_START -> startSending(intent)
            ACT_STOP -> stopAll()
        }
        return START_NOT_STICKY
    }

    private fun startSending(i: Intent) {
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotif("آماده‌سازی…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        val numbers = i.getStringArrayListExtra("numbers")
        val message = i.getStringExtra("message")
        if (numbers.isNullOrEmpty() || message.isNullOrEmpty()) {
            stopSelf()
            return
        }
        if (job?.isActive == true) return
        tail?.cancel()

        val gap = i.getIntExtra("gap", 30).coerceAtLeast(5)
        val subId = i.getIntExtra("subId", -1)
        SendState.state.value = UiState(
            active = true,
            items = numbers.map { Item(it) },
            simLabel = i.getStringExtra("simLabel") ?: "",
            gap = gap
        )
        if (wake == null) {
            val w = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smsscheduler:send")
            w.acquire(3 * 60 * 60 * 1000L)
            wake = w
        }

        job = scope.launch {
            for (idx in numbers.indices) {
                if (idx > 0) {
                    var left = gap
                    while (left > 0) {
                        SendState.state.update { it.copy(countdown = left) }
                        delay(1000)
                        if (!SendState.state.value.paused) left--
                    }
                }
                while (SendState.state.value.paused) delay(300)
                SendState.state.update { it.copy(countdown = 0) }
                sendOne(idx, numbers[idx], message, subId)
                updateNotif("${idx + 1} از ${numbers.size}")
            }
            SendState.state.update {
                it.copy(active = false, finished = true, paused = false, countdown = 0)
            }
            finishTail()
        }
    }

    private fun finishTail() {
        tail = scope.launch {
            delay(15000)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopAll() {
        if (!SendState.state.value.active) {
            stopSelf()
            return
        }
        job?.cancel()
        tail?.cancel()
        SendState.state.update {
            it.copy(active = false, finished = true, paused = false, countdown = 0)
        }
        finishTail()
    }

    private fun sendOne(idx: Int, number: String, text: String, subId: Int) {
        SendState.setStatus(idx, Status.SENDING)
        try {
            val sms = smsManagerFor(subId)
            val parts = sms.divideMessage(text)
            val sentPi = makePi(ACT_SENT, idx, 0)
            val delPi = makePi(ACT_DELIVERED, idx, 1)
            if (parts.size > 1) {
                val s = ArrayList<PendingIntent>()
                val d = ArrayList<PendingIntent>()
                for (k in 0 until parts.size) {
                    s.add(sentPi)
                    d.add(delPi)
                }
                sms.sendMultipartTextMessage(number, null, parts, s, d)
            } else {
                sms.sendTextMessage(number, null, text, sentPi, delPi)
            }
        } catch (e: Exception) {
            SendState.setStatus(idx, Status.FAILED)
        }
    }

    private fun makePi(action: String, idx: Int, t: Int): PendingIntent {
        val i = Intent(action).setPackage(packageName).putExtra("idx", idx)
        return PendingIntent.getBroadcast(
            this, idx * 2 + t, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    @Suppress("DEPRECATION")
    private fun smsManagerFor(subId: Int): SmsManager {
        if (Build.VERSION.SDK_INT >= 31) {
            val base = getSystemService(SmsManager::class.java)
            return if (subId >= 0) base.createForSubscriptionId(subId) else base
        }
        return if (subId >= 0) SmsManager.getSmsManagerForSubscriptionId(subId)
        else SmsManager.getDefault()
    }

    private fun buildNotif(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 99,
            Intent(this, SendService::class.java).setAction(ACT_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 98, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("ارسال پیامک در حال انجام است")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "توقف", stop)
            .build()
    }

    private fun updateNotif(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif(text))
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        scope.cancel()
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        const val ACT_START = "com.example.smsscheduler.START"
        const val ACT_STOP = "com.example.smsscheduler.STOP"
        const val ACT_SENT = "com.example.smsscheduler.SENT"
        const val ACT_DELIVERED = "com.example.smsscheduler.DELIVERED"
        private const val CH = "send"
        private const val NOTIF_ID = 1
    }
}

// =====================================================
// بخش ۲: توابع کمکی
// =====================================================

fun toFa(v: Any): String =
    v.toString().map { if (it in '0'..'9') '۰' + (it - '0') else it }.joinToString("")

fun granted(ctx: Context, p: String) =
    ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

data class Parsed(val valid: List<String>, val bad: Int)

fun parseNumbers(text: String): Parsed {
    val sb = StringBuilder()
    for (c in text) {
        when (c) {
            in '۰'..'۹' -> sb.append('0' + (c - '۰'))
            in '٠'..'٩' -> sb.append('0' + (c - '٠'))
            else -> sb.append(c)
        }
    }
    val tokens = sb.toString().split(Regex("[\\n,;،\\s]+")).filter { it.isNotBlank() }
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
        if (l.isEmpty()) {
            listOf(def)
        } else {
            l.sortedBy { it.simSlotIndex }
                .map { Sim(it.subscriptionId, "سیم‌کارت ${toFa(it.simSlotIndex + 1)} (${it.displayName})") }
        }
    } catch (e: Exception) {
        listOf(def)
    }
}

// =====================================================
// بخش ۳: تم و رنگ‌ها
// =====================================================

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

@Composable
fun cardColor() = if (isSystemInDarkTheme()) Color(0xFF172423) else Color.White

@Composable
fun okColor() = if (isSystemInDarkTheme()) Color(0xFF6BD99B) else Color(0xFF1B7F4B)

@Composable
fun warnFg() = if (isSystemInDarkTheme()) Color(0xFFF2B35C) else Color(0xFF9A5B00)

@Composable
fun warnBg() = if (isSystemInDarkTheme()) Color(0xFF3B2D12) else Color(0xFFFFE9C7)

// =====================================================
// بخش ۴: اکتیویتی و صفحه‌ها
// =====================================================

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
        val l = mutableListOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        l.toList()
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
                if (parsed.bad > 0) {
                    Text(
                        "${toFa(parsed.bad)} مورد نامعتبر نادیده گرفته شد",
                        Modifier.padding(top = 6.dp),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    "جداکننده: خط جدید، کاما یا فاصله",
                    Modifier.padding(top = 6.dp),
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
                    val mins = if (sec >= 60) toFa(sec / 60) + " دقیقه و " else ""
                    Text(
                        "زمان کل حدود $mins${toFa(sec % 60)} ثانیه",
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

@Composable
fun ProgressScreen(st: UiState) {
    val ctx = LocalContext.current
    val total = st.items.size
    val done = st.items.count { it.status != Status.PENDING && it.status != Status.SENDING }
    val okCount = st.items.count { it.status == Status.SENT || it.status == Status.DELIVERED }
    val failCount = st.items.count {
        it.status == Status.FAILED || (st.finished && it.status == Status.PENDING)
    }
    val line = when {
        st.finished -> "${toFa(okCount)} موفق · ${toFa(failCount)} ناموفق"
        st.paused -> "متوقف شد — برای ادامه بزنید"
        st.countdown > 0 -> "پیامک بعدی تا ${toFa(st.countdown)} ثانیه دیگر"
        else -> "در حال ارسال…"
    }
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val errColor = MaterialTheme.colorScheme.error
    val warnColor = warnFg()
    val okCol = okColor()

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
                fontSize = 12.sp, color = mutedColor
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
                Text(line, fontSize = 13.sp, color = mutedColor)
            }
            Section {
                for (idx in st.items.indices) {
                    val item = st.items[idx]
                    val label: String
                    val color: Color
                    when (item.status) {
                        Status.PENDING -> {
                            label = if (st.finished) "ارسال نشد" else "در انتظار"
                            color = mutedColor
                        }
                        Status.SENDING -> { label = "در حال ارسال"; color = warnColor }
                        Status.SENT -> { label = "ارسال شد"; color = warnColor }
                        Status.DELIVERED -> { label = "تحویل داده شد"; color = okCol }
                        Status.FAILED -> { label = "ناموفق"; color = errColor }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(item.number, fontSize = 14.sp)
                        Text(label, fontSize = 12.sp, color = color, fontWeight = FontWeight.Medium)
                    }
                    if (idx < st.items.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }
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
