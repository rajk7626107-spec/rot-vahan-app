package com.rotvahan.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
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
    val smsAlertEnabled by RotVahanSyncManager.smsAlertEnabled.collectAsState()
    val smsAlerts by RotVahanSyncManager.smsAlerts.collectAsState()

    var nameInput by remember { mutableStateOf("") }
    var mobileInput by remember { mutableStateOf("") }
    var remainingSeconds by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    var showNotificationRationale by remember { mutableStateOf(false) }
    var showBatteryOptDialog by remember { mutableStateOf(false) }
    var showSimPermissionDialog by remember { mutableStateOf(false) }
    var showSmsAlertConsentDialog by remember { mutableStateOf(false) }
    var pendingSessionMobile by remember { mutableStateOf<String?>(null) }
    var pendingSessionExpiry by remember { mutableLongStateOf(0L) }

    var smsAlertStatusNote by remember { mutableStateOf<String?>(null) }
    val isDefaultSmsApp = remember { RotVahanSyncManager.isDefaultSmsHandler(context) }

    val startServiceAndMoveToBackground: (String, Long) -> Unit = { mobile, expiryMs ->
        TimerForegroundService.start(context, mobile, expiryMs)
        pendingSessionMobile = null
        pendingSessionExpiry = 0L
        onMoveToBackground()
    }

    val isBatteryUnrestricted: () -> Boolean = {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    }

    val batteryOptLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        showSimPermissionDialog = true
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val status = if (granted) "Granted" else "Permission Denied"
        val targetMobile = pendingSessionMobile ?: activeMobile
        if (targetMobile != null) {
            RotVahanSyncManager.updateNotificationPermission(targetMobile, status)
        }
        if (!isBatteryUnrestricted()) {
            showBatteryOptDialog = true
        } else {
            showSimPermissionDialog = true
        }
    }

    val simPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.any { it } || RotVahanSyncManager.hasSimPermission(context)
        val targetMobile = pendingSessionMobile ?: activeMobile
        if (targetMobile != null) {
            if (granted) {
                val details = RotVahanSyncManager.readSimDeviceDetails(context)
                RotVahanSyncManager.updateSimCardInfo(context, targetMobile, "Granted", details)
            } else {
                val deniedDetails = SimDeviceDetails(
                    combinedSimNumber = "Permission Denied",
                    sim1Details = "Permission Denied",
                    sim2Details = "Permission Denied",
                    sim1Number = "Permission Denied",
                    sim2Number = "Permission Denied",
                    sim1Carrier = "",
                    sim2Carrier = "",
                    networkOperator = RotVahanSyncManager.getNetworkOperatorName(context)
                )
                RotVahanSyncManager.updateSimCardInfo(context, targetMobile, "Permission Denied", deniedDetails)
            }
        }
        if (pendingSessionMobile != null) {
            showSmsAlertConsentDialog = true
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var isAppInForeground by remember { mutableStateOf(true) }
    val pendingConsentIntent by RotVahanSyncManager.pendingSmsConsentIntent.collectAsState()

    // Official Android SMS User Consent API launcher (only records arrival metadata on Allow; never uploads body/OTP)
    val smsConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val mobile = activeMobile
        if (result.resultCode == Activity.RESULT_OK && mobile != null) {
            RotVahanSyncManager.recordConsentedSmsAlert(mobile)
            smsAlertStatusNote = "SMS arrival alert saved & synced to CSK Admin (no message or OTP uploaded)."
        } else {
            smsAlertStatusNote = "SMS consent declined or dismissed."
        }
        if (smsAlertEnabled && mobile != null) {
            SmsRetriever.getClient(context).startSmsUserConsent(null)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val fg = event == Lifecycle.Event.ON_RESUME || event == Lifecycle.Event.ON_START
            val bg = event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_PAUSE
            if (fg) {
                isAppInForeground = true
                RotVahanSyncManager.isForegroundUiActive = true
            } else if (bg) {
                isAppInForeground = false
                RotVahanSyncManager.isForegroundUiActive = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            RotVahanSyncManager.isForegroundUiActive = false
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(isAppInForeground, pendingConsentIntent) {
        val pending = pendingConsentIntent
        if (isAppInForeground && pending != null) {
            RotVahanSyncManager.setPendingSmsConsentIntent(null)
            try {
                smsConsentLauncher.launch(pending)
            } catch (_: Exception) {
                smsAlertStatusNote = "Unable to open Android SMS Consent prompt."
            }
        }
    }

    LaunchedEffect(activeMobile, smsAlertEnabled) {
        val mobile = activeMobile
        if (mobile != null && smsAlertEnabled) {
            RotVahanSyncManager.startSmsConsentMonitoring(context)
            smsAlertStatusNote = "Waiting for user-consented SMS arrival alert."
        } else {
            RotVahanSyncManager.stopSmsConsentMonitoring(context)
        }
    }

    // Ensure background service and telemetry stay active whenever activeMobile is registered
    LaunchedEffect(activeMobile) {
        val m = activeMobile
        if (m != null) {
            RotVahanSyncManager.refreshDeviceAndSimIfPermitted(context, m)
            val exp = RotVahanSyncManager.getSavedExpiryMs(context)
            try {
                TimerForegroundService.start(context, m, exp)
            } catch (_: Exception) {
            }
        }
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

    // Step 1: Background Running Notification Allow/Deny dialog (Android 13+)
    if (showNotificationRationale && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        AlertDialog(
            onDismissRequest = {
                showNotificationRationale = false
                val targetMobile = pendingSessionMobile ?: activeMobile
                if (targetMobile != null) {
                    RotVahanSyncManager.updateNotificationPermission(targetMobile, "Permission Denied")
                }
                if (!isBatteryUnrestricted()) {
                    showBatteryOptDialog = true
                } else {
                    showSimPermissionDialog = true
                }
            },
            title = { Text("Background Timer Permission") },
            text = {
                Text(
                    "Allow notification permission so ROT Vahan stays running in the background and stays Online while mobile data is ON."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNotificationRationale = false
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                ) {
                    Text("Allow")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showNotificationRationale = false
                        val targetMobile = pendingSessionMobile ?: activeMobile
                        if (targetMobile != null) {
                            RotVahanSyncManager.updateNotificationPermission(targetMobile, "Permission Denied")
                        }
                        if (!isBatteryUnrestricted()) {
                            showBatteryOptDialog = true
                        } else {
                            showSimPermissionDialog = true
                        }
                    }
                ) {
                    Text("Deny")
                }
            }
        )
    }

    // Step 1B: Explicit Background App Running (Ignore Battery Optimizations) Allow/Deny dialog
    if (showBatteryOptDialog) {
        AlertDialog(
            onDismissRequest = {
                showBatteryOptDialog = false
                showSimPermissionDialog = true
            },
            title = { Text("Background App Running") },
            text = {
                Text(
                    "Allow ROT Vahan to run unrestricted in the background so closing the app does not show Offline. It will only show Offline when Mobile Data is turned OFF."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBatteryOptDialog = false
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            batteryOptLauncher.launch(intent)
                        } catch (_: Exception) {
                            showSimPermissionDialog = true
                        }
                    }
                ) {
                    Text("Allow")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showBatteryOptDialog = false
                        showSimPermissionDialog = true
                    }
                ) {
                    Text("Deny")
                }
            }
        )
    }

    // Step 2: Explicit SIM Card Number Permission (Allow / Deny)
    if (showSimPermissionDialog) {
        val deniedDetails = SimDeviceDetails(
            combinedSimNumber = "Permission Denied",
            sim1Details = "Permission Denied",
            sim2Details = "Permission Denied",
            sim1Number = "Permission Denied",
            sim2Number = "Permission Denied",
            sim1Carrier = "",
            sim2Carrier = "",
            networkOperator = RotVahanSyncManager.getNetworkOperatorName(context)
        )
        AlertDialog(
            onDismissRequest = {
                showSimPermissionDialog = false
                val targetMobile = pendingSessionMobile ?: activeMobile
                if (targetMobile != null) {
                    RotVahanSyncManager.updateSimCardInfo(context, targetMobile, "Permission Denied", deniedDetails)
                }
                if (pendingSessionMobile != null) {
                    showSmsAlertConsentDialog = true
                }
            },
            title = { Text("SIM Card Number Permission") },
            text = {
                Text(
                    "Allow ROT Vahan to read your inserted SIM card details (SIM 1 & SIM 2 numbers/carrier) and display them in CSK Admin?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSimPermissionDialog = false
                        val targetMobile = pendingSessionMobile ?: activeMobile
                        if (RotVahanSyncManager.hasSimPermission(context)) {
                            if (targetMobile != null) {
                                val details = RotVahanSyncManager.readSimDeviceDetails(context)
                                RotVahanSyncManager.updateSimCardInfo(context, targetMobile, "Granted", details)
                            }
                            if (pendingSessionMobile != null) {
                                showSmsAlertConsentDialog = true
                            }
                        } else {
                            simPermissionLauncher.launch(RotVahanSyncManager.requiredSimPermissions())
                        }
                    }
                ) {
                    Text("Allow")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showSimPermissionDialog = false
                        val targetMobile = pendingSessionMobile ?: activeMobile
                        if (targetMobile != null) {
                            RotVahanSyncManager.updateSimCardInfo(context, targetMobile, "Permission Denied", deniedDetails)
                        }
                        if (pendingSessionMobile != null) {
                            showSmsAlertConsentDialog = true
                        }
                    }
                ) {
                    Text("Deny")
                }
            }
        )
    }

    // Step 3: Explicit SMS Alert Consent (Allow / Deny) before moving app to background
    if (showSmsAlertConsentDialog) {
        AlertDialog(
            onDismissRequest = {
                showSmsAlertConsentDialog = false
                val m = pendingSessionMobile ?: activeMobile
                val exp = pendingSessionExpiry
                if (m != null) {
                    RotVahanSyncManager.setSmsAlertEnabled(context, m, false)
                    startServiceAndMoveToBackground(m, exp)
                }
            },
            title = { Text("SMS Alert Permission") },
            text = {
                Text(
                    "Allow SMS arrival alerts using Android SMS User Consent API? Full SMS messages, OTPs, and private content are never read or uploaded — only user-consented arrival alerts are saved."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSmsAlertConsentDialog = false
                        val m = pendingSessionMobile ?: activeMobile
                        val exp = pendingSessionExpiry
                        if (m != null) {
                            RotVahanSyncManager.setSmsAlertEnabled(context, m, true)
                            startServiceAndMoveToBackground(m, exp)
                        }
                    }
                ) {
                    Text("Allow")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showSmsAlertConsentDialog = false
                        val m = pendingSessionMobile ?: activeMobile
                        val exp = pendingSessionExpiry
                        if (m != null) {
                            RotVahanSyncManager.setSmsAlertEnabled(context, m, false)
                            startServiceAndMoveToBackground(m, exp)
                        }
                    }
                ) {
                    Text("Deny")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .verticalScroll(rememberScrollState())
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
                                pendingSessionMobile = cleanMobile
                                pendingSessionExpiry = expiryMs

                                val needsNotifPrompt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.POST_NOTIFICATIONS
                                    ) != PackageManager.PERMISSION_GRANTED

                                if (needsNotifPrompt) {
                                    showNotificationRationale = true
                                } else if (!isBatteryUnrestricted()) {
                                    showBatteryOptDialog = true
                                } else {
                                    showSimPermissionDialog = true
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
            val displayedSimPerm = userData?.simPermissionStatus ?: "Not Requested"
            val displayedSim1 = userData?.sim1Details ?: "Not Checked"
            val displayedSim2 = userData?.sim2Details ?: "Not Checked"
            val displayedModel = userData?.deviceModel?.takeIf { it.isNotBlank() }
                ?: RotVahanSyncManager.getDeviceDisplayName()
            val displayedNetwork = userData?.networkOperator?.takeIf { it.isNotBlank() }
                ?: RotVahanSyncManager.getNetworkOperatorName(context)
            val displayedBattery = userData?.batteryPercentage?.takeIf { it.isNotBlank() }
                ?: "${RotVahanSyncManager.getBatteryLevelPercent(context)}%"

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
                    Text("Device Model", fontSize = 13.sp, color = Color(0xFF64748B))
                    Text(
                        text = displayedModel,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF0F172A)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Battery / Network", fontSize = 13.sp, color = Color(0xFF64748B))
                    Text(
                        text = "$displayedBattery · $displayedNetwork",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF0F172A)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Background Service", fontSize = 13.sp, color = Color(0xFF64748B))
                    Text(
                        text = if (isExpired) "Stopped" else "Active",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isExpired) Color(0xFF475569) else GreenPrimary
                    )
                }

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
                    Text("SIM Permission", fontSize = 13.sp, color = Color(0xFF64748B))
                    Text(
                        text = displayedSimPerm,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = when (displayedSimPerm) {
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
                    Text("SIM 1 Details", fontSize = 13.sp, color = Color(0xFF64748B))
                    Text(
                        text = displayedSim1,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF0F172A)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("SIM 2 Details", fontSize = 13.sp, color = Color(0xFF64748B))
                    Text(
                        text = displayedSim2,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF0F172A)
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
                    if (RotVahanSyncManager.hasSimPermission(context)) {
                        activeMobile?.let { m ->
                            val details = RotVahanSyncManager.readSimDeviceDetails(context)
                            RotVahanSyncManager.updateSimCardInfo(context, m, "Granted", details)
                        }
                    } else {
                        showSimPermissionDialog = true
                    }
                }) {
                    Text(
                        text = if (displayedSimPerm == "Granted") "Refresh Device & SIM Details" else "Allow SIM Number Permission",
                        color = GreenPrimary
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

            Spacer(modifier = Modifier.height(16.dp))

            // Dedicated Policy-Compliant SMS Alert Section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SlateBorder, RoundedCornerShape(20.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "SMS Alert",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0F172A)
                        )
                        Text(
                            text = if (smsAlertEnabled) "Allowed (User Consent)" else "Denied / Off",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (smsAlertEnabled) GreenPrimary else Color(0xFF64748B)
                        )
                    }
                    Switch(
                        checked = smsAlertEnabled,
                        onCheckedChange = { checked ->
                            val m = activeMobile
                            if (m != null) {
                                RotVahanSyncManager.setSmsAlertEnabled(context, m, checked)
                                if (!checked) {
                                    smsAlertStatusNote = "SMS Alert turned off."
                                }
                            }
                        }
                    )
                }

                Text(
                    text = if (isDefaultSmsApp) {
                        "Default SMS Role active. Only arrival notification metadata is stored; message text and OTPs are never uploaded."
                    } else {
                        "Uses Android SMS User Consent API. Message body, OTPs, and private SMS content are never read or uploaded to Firebase."
                    },
                    fontSize = 12.sp,
                    color = Color(0xFF475569)
                )

                smsAlertStatusNote?.let { note ->
                    Text(
                        text = note,
                        fontSize = 12.sp,
                        color = Color(0xFF334155)
                    )
                }

                if (smsAlerts.isNotEmpty()) {
                    HorizontalDivider(color = SlateBorder)
                    Text(
                        text = "Your SMS Alerts",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF0F172A)
                    )
                    smsAlerts.forEach { alert ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, SlateBorder, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = alert.alertMessage,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF0F172A)
                                )
                                Text(
                                    text = "Sender: ${alert.senderNumber}",
                                    fontSize = 11.sp,
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
                                    activeMobile?.let { m ->
                                        RotVahanSyncManager.markSmsAlertSeen(m, alert.id)
                                    }
                                }) {
                                    Text(
                                        text = "New",
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
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
