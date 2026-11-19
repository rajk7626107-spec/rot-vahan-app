package com.rotvahan.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.util.Locale

private val GreenPrimary = Color(0xFF15803D)
private val RedAlert = Color(0xFFB91C1C)
private val SlateBorder = Color(0xFFE2E8F0)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RotVahanSyncManager.ensureInitialized(applicationContext)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.White
                ) {
                    RotVahanScreen(
                        onMoveToBackground = {
                            moveTaskToBack(true)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun RotVahanScreen(
    onMoveToBackground: () -> Unit
) {
    val context = LocalContext.current

    val activeMobile by RotVahanSyncManager.activeMobile.collectAsState()
    val userData by RotVahanSyncManager.userData.collectAsState()
    val isOnline by RotVahanSyncManager.isOnline.collectAsState()
    val serverTimeOffsetMs by RotVahanSyncManager.serverTimeOffsetMs.collectAsState()

    var nameInput by remember { mutableStateOf("") }
    var mobileInput by remember { mutableStateOf("") }
    var remainingSeconds by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    var showNotificationRationale by remember { mutableStateOf(false) }
    var pendingSessionMobile by remember { mutableStateOf<String?>(null) }
    var pendingSessionExpiry by remember { mutableLongStateOf(0L) }

    val startServiceAndMoveToBackground: (String, Long) -> Unit = { mobile, expiryMs ->
        TimerForegroundService.start(context, mobile, expiryMs)
        onMoveToBackground()
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val status = if (granted) "Granted" else "Permission Denied"
        val targetMobile = pendingSessionMobile ?: activeMobile
        val targetExpiry = pendingSessionExpiry
        if (targetMobile != null) {
            RotVahanSyncManager.updateNotificationPermission(targetMobile, status)
            startServiceAndMoveToBackground(targetMobile, targetExpiry)
        }
        pendingSessionMobile = null
        pendingSessionExpiry = 0L
    }

    // Keep UI countdown synced with Firebase server time when Activity is open
    LaunchedEffect(userData?.timerExpiryTime, userData?.timerStatus, activeMobile, serverTimeOffsetMs) {
        val expiry = userData?.timerExpiryTime ?: 0L
        val mobile = activeMobile
        if (expiry <= 0L || mobile == null) {
            remainingSeconds = 0
            return@LaunchedEffect
        }
        while (true) {
            val secs = RotVahanSyncManager.computeRemainingSeconds(expiry)
            remainingSeconds = secs
            if (secs == 0 && userData?.timerStatus != "expired") {
                RotVahanSyncManager.markTimerExpired(mobile)
                break
            }
            delay(250)
        }
    }

    // Contextual explanation before launching Android 13+ notification permission dialog
    if (showNotificationRationale && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        AlertDialog(
            onDismissRequest = {
                showNotificationRationale = false
                val targetMobile = pendingSessionMobile
                val targetExpiry = pendingSessionExpiry
                if (targetMobile != null) {
                    RotVahanSyncManager.updateNotificationPermission(targetMobile, "Permission Denied")
                    startServiceAndMoveToBackground(targetMobile, targetExpiry)
                }
                pendingSessionMobile = null
                pendingSessionExpiry = 0L
            },
            title = { Text("Timer Notification") },
            text = {
                Text(
                    "ROT Vahan uses a foreground notification so your 2-minute countdown continues reliably in the background and alerts you when time completes."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNotificationRationale = false
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                ) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showNotificationRationale = false
                        val targetMobile = pendingSessionMobile
                        val targetExpiry = pendingSessionExpiry
                        if (targetMobile != null) {
                            RotVahanSyncManager.updateNotificationPermission(targetMobile, "Permission Denied")
                            startServiceAndMoveToBackground(targetMobile, targetExpiry)
                        }
                        pendingSessionMobile = null
                        pendingSessionExpiry = 0L
                    }
                ) {
                    Text("Not Now")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "ROT Vahan",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0F172A)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isOnline) GreenPrimary else RedAlert)
                )
                Text(
                    text = if (isOnline) "Online" else "Offline",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isOnline) GreenPrimary else RedAlert
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (activeMobile == null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SlateBorder, RoundedCornerShape(20.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Enter Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = mobileInput,
                    onValueChange = { mobileInput = it.filter { ch -> ch.isDigit() }.take(10) },
                    label = { Text("Enter Mobile Number") },
                    prefix = { Text("+91 ") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                errorMessage?.let {
                    Text(
                        text = it,
                        color = RedAlert,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Button(
                    onClick = {
                        val cleanName = nameInput.trim()
                        val cleanMobile = mobileInput.trim()
                        if (cleanName.length < 2) {
                            errorMessage = "Please enter your name (at least 2 characters)."
                            return@Button
                        }
                        if (!Regex("^[6-9]\\d{9}$").matches(cleanMobile)) {
                            errorMessage = "Please enter a valid 10-digit Indian mobile number."
                            return@Button
                        }

                        errorMessage = null
                        isSaving = true

                        RotVahanSyncManager.registerAndStartTimer(
                            context = context,
                            cleanName = cleanName,
                            cleanMobile = cleanMobile,
                            onSuccess = { expiryMs ->
                                isSaving = false
                                val needsNotifPrompt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.POST_NOTIFICATIONS
                                    ) != PackageManager.PERMISSION_GRANTED

                                if (needsNotifPrompt) {
                                    pendingSessionMobile = cleanMobile
                                    pendingSessionExpiry = expiryMs
                                    showNotificationRationale = true
                                } else {
                                    startServiceAndMoveToBackground(cleanMobile, expiryMs)
                                }
                            },
                            onError = { msg ->
                                isSaving = false
                                errorMessage = msg
                            }
                        )
                    },
                    enabled = !isSaving,
                    colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                ) {
                    Text(
                        text = if (isSaving) "Saving..." else "OK",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        } else {
            val isExpired = userData != null && remainingSeconds == 0
            val displayedNotif = userData?.notificationPermissionStatus
                ?: RotVahanSyncManager.getNotificationPermissionStatus(context)
            val displayedSms = userData?.smsPermissionStatus
                ?: RotVahanSyncManager.checkSmsEligibilityAndStatus(context)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SlateBorder, RoundedCornerShape(20.dp))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = if (isExpired) "Time Completed" else "Remaining Time",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0F172A)
                )

                Text(
                    text = "${userData?.userName ?: ""} · +91 ${userData?.mobileNumber ?: activeMobile}",
                    fontSize = 14.sp,
                    color = Color(0xFF475569)
                )

                Text(
                    text = String.format(Locale.US, "%02d:%02d", remainingSeconds / 60, remainingSeconds % 60),
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = if (isExpired) RedAlert else GreenPrimary,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Registered: ${userData?.registrationTimeFormatted ?: "—"}",
                    fontSize = 13.sp,
                    color = Color(0xFF64748B)
                )

                Text(
                    text = "Timer Status: ${if (isExpired) "Expired" else "Active"}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isExpired) RedAlert else GreenPrimary
                )

                HorizontalDivider(color = SlateBorder)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Notifications", fontSize = 13.sp, color = Color(0xFF64748B))
                    Text(
                        text = displayedNotif,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = when (displayedNotif) {
                            "Granted" -> GreenPrimary
                            "Permission Denied" -> RedAlert
                            else -> Color(0xFF475569)
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("SMS Access", fontSize = 13.sp, color = Color(0xFF64748B))
                    Text(
                        text = displayedSms,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (displayedSms == "Granted") GreenPrimary else RedAlert
                    )
                }

                TextButton(onClick = {
                    TimerForegroundService.stop(context)
                    RotVahanSyncManager.clearActiveUser(context)
                    nameInput = ""
                    mobileInput = ""
                    errorMessage = null
                }) {
                    Text("Register Another User", color = Color(0xFF475569))
                }
            }
        }
    }
}
