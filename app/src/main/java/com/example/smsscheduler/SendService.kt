package com.example.smsscheduler

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
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.telephony.SmsManager
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

    fun setStatus(i: Int, s: Status) = state.update { st ->
        if (i !in st.items.indices) return@update st
        val cur = st.items[i].status
        val ok = when (s) {
            Status.FAILED -> true
            Status.SENT, Status.SENDING -> cur == Status.PENDING || cur == Status.SENDING
            Status.DELIVERED -> cur != Status.FAILED
            else -> true
        }
        if (!ok) st else st.copy(items = st.items.toMutableList().also { it[i] = Item(it[i].number, s) })
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
                    idx, if (resultCode == Activity.RESULT_OK) Status.SENT else Status.FAILED
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
        val f = IntentFilter().apply { addAction(ACT_SENT); addAction(ACT_DELIVERED) }
        ContextCompat.registerReceiver(this, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_START -> start(intent)
            ACT_STOP -> stopAll()
        }
        return START_NOT_STICKY
    }

    private fun start(i: Intent) {
        ServiceCompat.startForeground(
            this, NOTIF_ID, notif("آماده‌سازی…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        val numbers = i.getStringArrayListExtra("numbers")
        val message = i.getStringExtra("message")
        if (numbers.isNullOrEmpty() || message.isNullOrEmpty()) { stopSelf(); return }
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
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smsscheduler:send")
                .apply { acquire(3 * 60 * 60 * 1000L) }
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
                notify("${idx + 1} از ${numbers.size}")
            }
            SendState.state.update { it.copy(active = false, finished = true, paused = false, countdown = 0) }
            finishTail()
        }
    }

    private fun finishTail() {
        // چند ثانیه صبر می‌کنیم تا گزارش «ارسال/تحویل» برسد
        tail = scope.launch {
            delay(15000)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopAll() {
        if (!SendState.state.value.active) { stopSelf(); return }
        job?.cancel()
        tail?.cancel()
        SendState.state.update { it.copy(active = false, finished = true, paused = false, countdown = 0) }
        finishTail()
    }

    private fun sendOne(idx: Int, number: String, text: String, subId: Int) {
        SendState.setStatus(idx, Status.SENDING)
        try {
            val sms = smsManager(subId)
            val parts = sms.divideMessage(text)
            val sentPi = pi(ACT_SENT, idx, 0)
            val delPi = pi(ACT_DELIVERED, idx, 1)
            if (parts.size > 1) {
                val s = ArrayList<PendingIntent>()
                val d = ArrayList<PendingIntent>()
                repeat(parts.size) { s.add(sentPi); d.add(delPi) }
                sms.sendMultipartTextMessage(number, null, parts, s, d)
            } else {
                sms.sendTextMessage(number, null, text, sentPi, delPi)
            }
        } catch (e: Exception) {
            SendState.setStatus(idx, Status.FAILED)
        }
    }

    private fun pi(action: String, idx: Int, t: Int): PendingIntent {
        val i = Intent(action).setPackage(packageName).putExtra("idx", idx)
        return PendingIntent.getBroadcast(
            this, idx * 2 + t, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    @Suppress("DEPRECATION")
    private fun smsManager(subId: Int): SmsManager {
        if (Build.VERSION.SDK_INT >= 31) {
            val base = getSystemService(SmsManager::class.java)
            return if (subId >= 0) base.createForSubscriptionId(subId) else base
        }
        return if (subId >= 0) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
    }

    private fun notif(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 99, Intent(this, SendService::class.java).setAction(ACT_STOP), PendingIntent.FLAG_IMMUTABLE
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

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif(text))
    }

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
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
