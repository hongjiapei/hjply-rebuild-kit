package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.DefaultProfileSeeder
import io.nekohasekai.sfa.SeedState
import io.nekohasekai.sfa.bg.BoxService
import io.nekohasekai.sfa.compose.base.BaseViewModel
import io.nekohasekai.sfa.compose.base.UiEvent
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.AppLifecycleObserver
import io.nekohasekai.sfa.utils.CommandClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

data class DashboardUiState(
    val profiles: List<Profile> = emptyList(),
    val selectedProfileId: Long = -1L,
    val selectedProfileName: String? = null,
    val selectedProfileLastUpdated: Date? = null,
    val seedState: SeedState = SeedState.Idle,
    val serviceStatus: Status = Status.Stopped,
    val serviceStartTime: Long? = null,
    val uplinkTotal: String = "0 B",
    val downlinkTotal: String = "0 B",
    val isDarkMode: Boolean = false,
)

class DashboardViewModel :
    BaseViewModel<DashboardUiState, UiEvent>(),
    CommandClient.Handler {
    private val serviceStatusFlow = MutableStateFlow(Status.Stopped)

    private val commandClient =
        CommandClient(
            viewModelScope,
            listOf(CommandClient.ConnectionType.Status),
            this,
        )

    override fun createInitialState() = DashboardUiState(isDarkMode = Settings.themeDarkMode)

    init {
        loadProfiles()
        ProfileManager.registerCallback(::onProfilesChanged)

        viewModelScope.launch {
            DefaultProfileSeeder.seedState.collect { state ->
                updateState { copy(seedState = state) }
                if (state is SeedState.Ready) loadProfiles()
            }
        }

        viewModelScope.launch {
            AppLifecycleObserver.isForeground.collect { foreground ->
                if (serviceStatusFlow.value != Status.Started) return@collect
                if (foreground) commandClient.connect() else commandClient.disconnect()
            }
        }
    }

    fun retrySeed() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { DefaultProfileSeeder.seedIfNeeded(Application.application) }
        }
    }

    fun toggleTheme() {
        val dark = !currentState.isDarkMode
        Settings.themeDarkMode = dark
        updateState { copy(isDarkMode = dark) }
    }

    fun toggleService() {
        when (serviceStatusFlow.value) {
            Status.Starting, Status.Started -> viewModelScope.launch(Dispatchers.IO) { BoxService.stop() }
            Status.Stopped -> sendGlobalEvent(UiEvent.RequestStartService)
            else -> Unit
        }
    }

    fun updateServiceStatus(status: Status) {
        serviceStatusFlow.value = status
        updateState { copy(serviceStatus = status) }
        when (status) {
            Status.Started -> {
                loadStartedAt()
                if (AppLifecycleObserver.isForeground.value) commandClient.connect()
            }
            Status.Stopped -> {
                commandClient.disconnect()
                updateState {
                    copy(
                        serviceStartTime = null,
                        uplinkTotal = "0 B",
                        downlinkTotal = "0 B",
                    )
                }
            }
            else -> Unit
        }
    }

    private fun loadStartedAt() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { Libbox.newStandaloneCommandClient().startedAt }
                .onSuccess { startedAt ->
                    withContext(Dispatchers.Main) {
                        updateState { copy(serviceStartTime = startedAt) }
                    }
                }
        }
    }

    private fun onProfilesChanged() = loadProfiles()

    private fun loadProfiles() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val profiles = ProfileManager.list()
                val selectedId = Settings.selectedProfile
                val selected = profiles.firstOrNull { it.id == selectedId }
                withContext(Dispatchers.Main) {
                    updateState {
                        copy(
                            profiles = profiles,
                            selectedProfileId = selectedId,
                            selectedProfileName = selected?.name,
                            selectedProfileLastUpdated = selected?.typed?.lastUpdated,
                        )
                    }
                }
            }.onFailure(::sendError)
        }
    }

    override fun updateStatus(status: StatusMessage) {
        viewModelScope.launch(Dispatchers.Main) {
            updateState {
                copy(
                    uplinkTotal = Libbox.formatBytes(status.uplinkTotal),
                    downlinkTotal = Libbox.formatBytes(status.downlinkTotal),
                )
            }
        }
    }

    override fun onCleared() {
        ProfileManager.unregisterCallback(::onProfilesChanged)
        commandClient.disconnect()
        super.onCleared()
    }
}
