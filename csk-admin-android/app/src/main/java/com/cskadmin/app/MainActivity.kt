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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.EmailAuthProvider
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

data class AdminUserRow(
    val userName: String = "",
    val mobileNumber: String = "",
    val registrationTime: Long = 0L,
    val registrationTimeFormatted: String = "—",
    val timerExpiryTime: Long = 0L,
    val timerStatus: String = "active",
    val isOnline: Boolean = false,
    val smsPermissionStatus: String = "Not Available",
    val notificationPermissionStatus: String = "Not Requested"
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

    var currentUser by remember { mutableStateOf(auth.currentUser) }
    var isAdminVerified by remember { mutableStateOf(false) }
    var checkingAdmin by remember { mutableStateOf(false) }

    var emailInput by remember { mutableStateOf("") }
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

    // Firebase Auth state listener + /admins/{uid} verification
    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { fa ->
            val u: FirebaseUser? = fa.currentUser
            currentUser = u
            if (u != null && !u.isAnonymous) {
                checkingAdmin = true
                database.getReference("admins/${u.uid}").get()
                    .addOnSuccessListener { snap ->
                        isAdminVerified = snap.exists() && snap.getValue(Boolean::class.java) == true
                        checkingAdmin = false
                    }
                    .addOnFailureListener {
                        isAdminVerified = false
                        checkingAdmin = false
                    }
            } else {
                isAdminVerified = false
                checkingAdmin = false
            }
        }
        auth.addAuthStateListener(listener)
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

    // Real-time /users listener when admin is verified
    DisposableEffect(currentUser?.uid, isAdminVerified) {
        val u = currentUser
        if (u == null || u.isAnonymous || !isAdminVerified) {
            users = emptyList()
            onDispose { }
        } else {
            val usersRef = database.getReference("users")
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
                                notificationPermissionStatus = notifStatus
                            )
                        )
                    }
                    list.sortByDescending { it.registrationTime }
                    users = list
                    lastSyncedTime = timeFmt.format(Date())
                }

                override fun onCancelled(error: DatabaseError) {
                    dbError = error.message
                }
            }
            usersRef.addValueEventListener(listener)
            onDispose { usersRef.removeEventListener(listener) }
        }
    }

    val filteredUsers = remember(users, searchQuery) {
        val q = searchQuery.trim().lowercase(Locale.getDefault())
        if (q.isEmpty()) users
        else users.filter {
            it.userName.lowercase(Locale.getDefault()).contains(q) ||
                it.mobileNumber.lowercase(Locale.getDefault()).contains(q)
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
                        val user = auth.currentUser
                        val email = user?.email
                        if (user == null || email.isNullOrBlank()) {
                            pwIsError = true
                            pwStatusMessage = "Password change requires email sign-in."
                            return@TextButton
                        }
                        if (newPw.length < 6) {
                            pwIsError = true
                            pwStatusMessage = "New password must be at least 6 characters."
                            return@TextButton
                        }
                        isUpdatingPw = true
                        pwStatusMessage = null
                        val cred = EmailAuthProvider.getCredential(email, currentPw)
                        user.reauthenticate(cred)
                            .addOnSuccessListener {
                                user.updatePassword(newPw)
                                    .addOnSuccessListener {
                                        isUpdatingPw = false
                                        pwIsError = false
                                        pwStatusMessage = "Password updated."
                                        currentPw = ""
                                        newPw = ""
                                    }
                                    .addOnFailureListener { e ->
                                        isUpdatingPw = false
                                        pwIsError = true
                                        pwStatusMessage = e.message
                                    }
                            }
                            .addOnFailureListener { e ->
                                isUpdatingPw = false
                                pwIsError = true
                                pwStatusMessage = e.message
                            }
                    }
                ) {
                    Text(if (isUpdatingPw) "Updating..." else "Update")
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
                if (currentUser != null && currentUser?.isAnonymous == false) {
                    TextButton(onClick = { auth.signOut() }) {
                        Text("Sign Out", fontSize = 12.sp)
                    }
                }
            }
        }
        HorizontalDivider(color = SlateBorder)

        val user = currentUser
        if (user == null || user.isAnonymous) {
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
                        text = "CSK ADMIN Sign In",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )
                    OutlinedTextField(
                        value = emailInput,
                        onValueChange = { emailInput = it },
                        label = { Text("Admin Email") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    authError?.let {
                        Text(text = it, color = RedAlert, fontSize = 13.sp)
                    }
                    Button(
                        onClick = {
                            authError = null
                            isSigningIn = true
                            auth.signInWithEmailAndPassword(emailInput.trim(), passwordInput)
                                .addOnSuccessListener { isSigningIn = false }
                                .addOnFailureListener { e ->
                                    isSigningIn = false
                                    authError = e.message ?: "Sign-in failed."
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
                            text = if (isSigningIn) "Signing in..." else "Sign In",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        } else if (checkingAdmin) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Checking admin access...", color = Color(0xFF64748B))
            }
        } else if (!isAdminVerified) {
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
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Admin Access Required", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Add admins/${user.uid}: true in Firebase Realtime Database sk-admin-panel-886ef.",
                        fontSize = 14.sp,
                        color = Color(0xFF475569)
                    )
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

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
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
                            }
                        }
                    }
                }
            }
        }
    }
}
