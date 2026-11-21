package com.cskadmin.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.database.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

private val BlueAccent = Color(0xFF1D4ED8)
private val GreenPrimary = Color(0xFF15803D)
private val RedAlert = Color(0xFFB91C1C)
private val SlateBorder = Color(0xFFE2E8F0)
private const val RTDB_URL = "https://sk-admin-panel-886ef-default-rtdb.firebaseio.com"
private const val ADMIN_PREFS = "csk_admin_prefs"
private const val KEY_ADMIN_PASSWORD = "admin_password"
private const val KEY_ADMIN_UNLOCKED = "admin_unlocked"
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

    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var currentPw by remember { mutableStateOf("") }
    var newPw by remember { mutableStateOf("") }
    var pwStatusMessage by remember { mutableStateOf<String?>(null) }
    var pwIsError by remember { mutableStateOf(false) }
    var isUpdatingPw by remember { mutableStateOf(false) }

    // Live tick for 2-minute session countdown status
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(250)
        }
    }

    // Automatic internet connectivity detection
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

    // Keep Firebase Auth state synced; sign in anonymously when unlocked so RTDB has an authenticated session
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

    // Firebase RTDB connection + serverTimeOffset
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

    // Real-time /users and /sms_alerts listeners when unlocked
    DisposableEffect(isUnlocked, currentUser?.uid) {
        if (!isUnlocked) {
            users = emptyList()
            smsAlerts = emptyList()
            onDispose { }
        } else {
            val usersRef = database.getReference("users")
            val alertsRef = database.getReference("sms_alerts")
            val timeFmt = SimpleDateFormat("hh:mm:ss a", Locale("en", "IN"))
            val dateFmt = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale("en", "IN"))

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
                            ?: "Pending"

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

    val filteredUsers = remember(users, searchQuery) {
        val q = searchQuery.trim().lowercase(Locale.getDefault())
        if (q.isEmpty()) users
        else users.filter {
            it.userName.lowercase(Locale.getDefault()).contains(q) ||
                it.mobileNumber.lowercase(Locale.getDefault()).contains(q) ||
                it.simNumber.lowercase(Locale.getDefault()).contains(q) ||
                it.sim1Details.lowercase(Locale.getDefault()).contains(q) ||
                it.sim2Details.lowercase(Locale.getDefault()).contains(q) ||
                it.deviceModel.lowercase(Locale.getDefault()).contains(q)
        }
    }

    val onlineCount = remember(users) { users.count { it.isOnline } }
    val offlineCount = users.size - onlineCount

    if (showChangePasswordDialog) {
        AlertDialog(
            onDismissRequest = { showChangePasswordDialog = false },
            title = { Text("Change Admin Password") },
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
                            color = if (pwIsError) RedAlert else GreenPrimary
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isUpdatingPw,
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        // Top Header matching CSK ADMIN screenshot
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "CSK ADMIN",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0F172A)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (firebaseConnected) "Firebase Connected" else "Disconnected",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (firebaseConnected) GreenPrimary else RedAlert
                )
                if (isUnlocked) {
                    TextButton(onClick = {
                        prefs.edit().putBoolean(KEY_ADMIN_UNLOCKED, false).apply()
                        isUnlocked = false
                        passwordInput = ""
                    }) {
                        Text("Lock", fontSize = 12.sp)
                    }
                }
            }
        }
        HorizontalDivider(color = SlateBorder)

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
                        .border(1.dp, SlateBorder, RoundedCornerShape(20.dp))
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
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
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
                    .padding(horizontal = 20.dp),
                contentPadding = PaddingValues(vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Shield,
                                contentDescription = null,
                                tint = BlueAccent,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "CSK ADMIN · ${if (firebaseConnected) "Firebase Connected" else "Disconnected"}",
                                fontSize = 13.sp,
                                color = Color(0xFF475569)
                            )
                        }
                        Text(
                            text = "Synced $lastSyncedTime",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF64748B)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Device Overview",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0F172A)
                        )
                    }
                }

                item {
                    Button(
                        onClick = {
                            pwStatusMessage = null
                            showChangePasswordDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BlueAccent),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Key,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Change Password",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, SlateBorder, RoundedCornerShape(20.dp))
                            .padding(20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Device Count",
                                fontSize = 14.sp,
                                color = Color(0xFF475569)
                            )
                            Text(
                                text = users.size.toString(),
                                fontSize = 36.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0F172A)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "$onlineCount Online",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = GreenPrimary
                                )
                                Text("·", color = Color(0xFF94A3B8))
                                Text(
                                    text = "$offlineCount Offline",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = RedAlert
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.Outlined.Smartphone,
                            contentDescription = null,
                            tint = BlueAccent
                        )
                    }
                }

                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        leadingIcon = {
                            Icon(Icons.Outlined.Search, contentDescription = null)
                        },
                        placeholder = { Text("Search by device number, phone...") },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
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
                                .border(1.dp, SlateBorder, RoundedCornerShape(20.dp))
                                .padding(28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No registered devices found.", color = Color(0xFF64748B))
                        }
                    }
                } else {
                    items(filteredUsers, key = { it.mobileNumber }) { item ->
                        val serverNow = nowMs + serverTimeOffsetMs
                        val remainingSecs = if (item.timerExpiryTime > 0L) {
                            kotlin.math.max(
                                0,
                                kotlin.math.ceil((item.timerExpiryTime - serverNow) / 1000.0).toInt()
                            )
                        } else {
                            0
                        }
                        val timerLabel = if (item.timerExpiryTime <= 0L) {
                            "—"
                        } else if (remainingSecs > 0) {
                            String.format(
                                Locale.US,
                                "Active · %02d:%02d",
                                remainingSecs / 60,
                                remainingSecs % 60
                            )
                        } else {
                            "Expired (00:00)"
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, SlateBorder, RoundedCornerShape(20.dp))
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = item.userName,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF0F172A)
                                    )
                                    Text(
                                        text = "+91 ${item.mobileNumber}",
                                        fontSize = 13.sp,
                                        color = Color(0xFF475569)
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(9.dp)
                                                .clip(CircleShape)
                                                .background(if (item.isOnline) GreenPrimary else RedAlert)
                                        )
                                        Text(
                                            text = if (item.isOnline) "Online" else "Offline",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (item.isOnline) GreenPrimary else RedAlert
                                        )
                                    }
                                }
                            }

                            Text(
                                text = "Timer: $timerLabel",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = if (remainingSecs > 0) GreenPrimary else Color(0xFF475569)
                            )

                            Text(
                                text = "Registered: ${item.registrationTimeFormatted}",
                                fontSize = 12.sp,
                                color = Color(0xFF64748B)
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "Device Model: ${item.deviceModel}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF0F172A)
                                )
                                Text(
                                    text = "SIM 1 Details: ${item.sim1Details}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (item.simPermissionStatus == "Granted") Color(0xFF0F172A) else Color(0xFF475569)
                                )
                                Text(
                                    text = "SIM 2 Details: ${item.sim2Details}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF334155)
                                )
                                Text(
                                    text = "Network Operator: ${item.networkOperator} · Battery: ${item.batteryPercentage}",
                                    fontSize = 12.sp,
                                    color = Color(0xFF475569)
                                )
                                Text(
                                    text = "Device Date & Time: ${item.deviceDateTime}",
                                    fontSize = 12.sp,
                                    color = Color(0xFF475569)
                                )
                                Text(
                                    text = "SIM Permission: ${item.simPermissionStatus}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = when (item.simPermissionStatus) {
                                        "Granted" -> GreenPrimary
                                        "Permission Denied" -> RedAlert
                                        else -> Color(0xFF475569)
                                    }
                                )
                                Text(
                                    text = "SMS Permission: ${item.smsPermissionStatus}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (item.smsPermissionStatus == "Granted") GreenPrimary else RedAlert
                                )
                                Text(
                                    text = "Notifications: ${item.notificationPermissionStatus}",
                                    fontSize = 12.sp,
                                    color = Color(0xFF475569)
                                )
                                Text(
                                    text = "SMS Alert: ${if (item.smsAlertEnabled) "Enabled" else "Off"}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (item.smsAlertEnabled) GreenPrimary else Color(0xFF64748B)
                                )
                            }

                            val userAlerts = smsAlerts.filter { it.mobileNumber == item.mobileNumber }
                            if (userAlerts.isNotEmpty()) {
                                HorizontalDivider(color = SlateBorder)
                                Text(
                                    text = "User SMS Alerts (${userAlerts.size})",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F172A)
                                )
                                userAlerts.take(5).forEach { alert ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .border(1.dp, SlateBorder, RoundedCornerShape(12.dp))
                                            .padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Text(
                                                text = alert.alertMessage,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFF0F172A)
                                            )
                                            Text(
                                                text = "Sender: ${alert.senderNumber} · ${alert.receivedAtFormatted}",
                                                fontSize = 11.sp,
                                                color = Color(0xFF64748B)
                                            )
                                        }
                                        if (alert.status == "New") {
                                            TextButton(onClick = {
                                                database.getReference("sms_alerts/${alert.mobileNumber}/${alert.id}/status")
                                                    .setValue("Seen")
                                            }) {
                                                Text(
                                                    text = "New · Seen",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = GreenPrimary
                                                )
                                            }
                                        } else {
                                            Text(
                                                text = "Seen",
                                                fontSize = 11.sp,
                                                color = Color(0xFF64748B)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "SMS Alerts (sms_alerts)",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )
                }

                if (smsAlerts.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, SlateBorder, RoundedCornerShape(20.dp))
                                .padding(20.dp),
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
                    items(smsAlerts, key = { "${it.mobileNumber}_${it.id}" }) { alert ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, SlateBorder, RoundedCornerShape(16.dp))
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    text = "+91 ${alert.mobileNumber} · ${alert.alertMessage}",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F172A)
                                )
                                Text(
                                    text = "Sender: ${alert.senderNumber}",
                                    fontSize = 12.sp,
                                    color = Color(0xFF475569)
                                )
                                Text(
                                    text = alert.receivedAtFormatted,
                                    fontSize = 11.sp,
                                    color = Color(0xFF64748B)
                                )
                            }

                            if (alert.status == "New") {
                                TextButton(onClick = {
                                    database.getReference("sms_alerts/${alert.mobileNumber}/${alert.id}/status")
                                        .setValue("Seen")
                                }) {
                                    Text(
                                        text = "New · Mark Seen",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = GreenPrimary
                                    )
                                }
                            } else {
                                Text(
                                    text = "Seen",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF64748B),
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
