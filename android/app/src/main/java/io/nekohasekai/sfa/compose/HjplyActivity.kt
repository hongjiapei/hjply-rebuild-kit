package io.nekohasekai.sfa.compose

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.bg.ServiceConnection
import io.nekohasekai.sfa.bg.ServiceNotification
import io.nekohasekai.sfa.compose.base.GlobalEventBus
import io.nekohasekai.sfa.compose.base.UiEvent
import io.nekohasekai.sfa.compose.screen.dashboard.DashboardScreen
import io.nekohasekai.sfa.compose.screen.dashboard.DashboardViewModel
import io.nekohasekai.sfa.compose.theme.SFATheme
import io.nekohasekai.sfa.compose.ui.theme.DarkHjplyPalette
import io.nekohasekai.sfa.compose.ui.theme.LightHjplyPalette
import io.nekohasekai.sfa.compose.ui.theme.LocalHjplyPalette
import io.nekohasekai.sfa.constant.Alert
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HjplyActivity :
    AppCompatActivity(),
    ServiceConnection.Callback {
    private val connection = ServiceConnection(this, this)
    private lateinit var dashboardViewModel: DashboardViewModel
    private var serviceStatus by mutableStateOf(Status.Stopped)
    private var currentAlert by mutableStateOf<Pair<Alert, String?>?>(null)
    private var errorMessage by mutableStateOf<String?>(null)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted || !Settings.dynamicNotification) {
                startServiceInternal()
            } else {
                onServiceAlert(Alert.RequestNotificationPermission, null)
            }
        }

    private val prepareVpnLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                startServiceInternal()
            } else {
                onServiceAlert(Alert.RequestVPNPermission, null)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        connection.reconnect()

        setContent {
            val viewModel: DashboardViewModel = viewModel()
            dashboardViewModel = viewModel
            val dashboardState by viewModel.uiState.collectAsState()
            LaunchedEffect(Unit) {
                GlobalEventBus.events.collect { event ->
                    when (event) {
                        is UiEvent.ErrorMessage -> errorMessage = event.message
                        UiEvent.RequestStartService -> startService()
                        UiEvent.RequestReconnectService -> connection.reconnect()
                        UiEvent.RestartToTakeEffect -> {
                            if (serviceStatus == Status.Started) {
                                withContext(Dispatchers.IO) {
                                    Libbox.newStandaloneCommandClient().serviceReload()
                                }
                            }
                        }
                        is UiEvent.EditProfile, is UiEvent.OpenUrl -> Unit
                    }
                }
            }

            SFATheme(darkTheme = dashboardState.isDarkMode) {
                CompositionLocalProvider(
                    LocalHjplyPalette provides if (dashboardState.isDarkMode) DarkHjplyPalette else LightHjplyPalette,
                ) {
                    DashboardScreen(
                        serviceStatus = serviceStatus,
                        viewModel = viewModel,
                    )

                    currentAlert?.let { (type, message) ->
                        ServiceAlertDialog(type, message) { currentAlert = null }
                    }
                    errorMessage?.let { message ->
                        MessageDialog(message) { errorMessage = null }
                    }
                }
            }
        }
    }

    @SuppressLint("NewApi")
    private fun startService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !ServiceNotification.checkPermission()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startServiceInternal()
    }

    private fun startServiceInternal() {
        lifecycleScope.launch(Dispatchers.IO) {
            val permissionIntent = withContext(Dispatchers.Main) { VpnService.prepare(this@HjplyActivity) }
            if (permissionIntent != null) {
                withContext(Dispatchers.Main) { prepareVpnLauncher.launch(permissionIntent) }
                return@launch
            }
            val serviceIntent = Intent(Application.application, Settings.serviceClass())
            withContext(Dispatchers.Main) {
                ContextCompat.startForegroundService(this@HjplyActivity, serviceIntent)
            }
            Settings.startedByUser = true
        }
    }

    override fun onServiceStatusChanged(status: Status) {
        serviceStatus = status
        if (::dashboardViewModel.isInitialized) dashboardViewModel.updateServiceStatus(status)
    }

    override fun onServiceAlert(type: Alert, message: String?) {
        currentAlert = type to message
    }

    override fun onDestroy() {
        connection.disconnect()
        super.onDestroy()
    }

    @androidx.compose.runtime.Composable
    private fun ServiceAlertDialog(type: Alert, message: String?, onDismiss: () -> Unit) {
        val title = when (type) {
            Alert.RequestNotificationPermission -> stringResource(R.string.notification_permission_title)
            Alert.StartCommandServer -> stringResource(R.string.error_start_command_server)
            Alert.CreateService -> stringResource(R.string.error_create_service)
            Alert.StartService -> stringResource(R.string.error_start_service)
            else -> null
        }
        val body = when (type) {
            Alert.RequestVPNPermission -> stringResource(R.string.error_missing_vpn_permission)
            Alert.RequestNotificationPermission -> stringResource(R.string.notification_permission_required_description)
            Alert.EmptyConfiguration -> stringResource(R.string.error_empty_configuration)
            Alert.RequestLocationPermission -> "当前配置需要额外的网络权限"
            else -> message
        }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = title?.let { { Text(it) } },
            text = body?.let { { Text(it) } },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
            },
        )
    }

    @androidx.compose.runtime.Composable
    private fun MessageDialog(message: String, onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
            },
        )
    }
}
