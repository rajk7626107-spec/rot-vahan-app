package com.rotvahan.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

data class RotVahanUserData(
    val userName: String = "",
    val mobileNumber: String = "",
    val registrationTime: Long = 0L,
    val registrationTimeFormatted: String = "",
    val timerStartTime: Long = 0L,
    val timerExpiryTime: Long = 0L,
    val timerStatus: String = "active",
    val smsPermissionStatus: String = "Not Available",
    val notificationPermissionStatus: String = "Not Requested"
)

object RotVahanSyncManager {
    private const val PREFS_NAME = "rot_vahan_prefs"
    private const val KEY_MOBILE = "mobile_number"
    private const val KEY_EXPIRY = "timer_expiry_ms"
    private const val RTDB_URL = "https://sk-admin-panel-886ef-default-rtdb.firebaseio.com"
    const val TIMER_DURATION_MS = 120_000L

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val database: FirebaseDatabase by lazy { FirebaseDatabase.getInstance(RTDB_URL) }

    private var initialized = false
    private var appContext: Context? = null
    private var userListener: ValueEventListener? = null
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

    @Synchronized
    fun ensureInitialized(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (_activeMobile.value == null) {
            _activeMobile.value = prefs.getString(KEY_MOBILE, null)
        }

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

    /**
     * Checks whether SMS access is legally eligible under Android & Google Play policies.
     * ROT Vahan is not a Default SMS Handler, so SMS access is not requested and is
     * reported honestly as "Not Available".
     */
    fun checkSmsEligibilityAndStatus(context: Context): String {
        val defaultSmsPkg = Telephony.Sms.getDefaultSmsPackage(context)
        val isDefaultSmsHandler = defaultSmsPkg != null && defaultSmsPkg == context.packageName
        if (!isDefaultSmsHandler) {
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
            userRef.updateChildren(
                mapOf(
                    "connectionStatus" to if (effectiveOnline) "online" else "offline",
                    "isOnline" to effectiveOnline,
                    "online" to effectiveOnline,
                    "networkAvailable" to _networkAvailable.value,
                    "lastSeen" to ServerValue.TIMESTAMP
                )
            )
        }
    }

    private fun attachUserListener(mobile: String?) {
        if (mobile == listenedMobile) return
        listenedMobile?.let { prev ->
            userListener?.let { database.getReference("users/$prev").removeEventListener(it) }
        }
        listenedMobile = mobile
        if (mobile == null) {
            _userData.value = null
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

                appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    ?.edit()
                    ?.putLong(KEY_EXPIRY, expiryTime)
                    ?.apply()

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
                        .getValue(String::class.java) ?: "Not Requested"
                )
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        userListener = listener
        userRef.addValueEventListener(listener)
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
        val currentSmsStatus = checkSmsEligibilityAndStatus(ctx)
        val currentInternet = hasActiveInternet(ctx)

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

                            userRef.updateChildren(
                                mapOf(
                                    "registrationTimeFormatted" to fmt.format(Date(committedReg)),
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

    fun markTimerExpired(mobile: String) {
        database.getReference("users/$mobile")
            .updateChildren(mapOf("timerStatus" to "expired"))
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
            .apply()
        _activeMobile.value = null
        attachUserListener(null)
    }
}
