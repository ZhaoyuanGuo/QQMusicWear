package com.qmusic.wear.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.data.auth.QrLoginEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 登录页 UI 状态 + 二维码状态机 */
data class LoginUiState(
    val status: QrStatus = QrStatus.Loading,
    val qrBytes: ByteArray? = null,
    val message: String = "",
) {
    enum class QrStatus {
        Loading, Ready, WaitingScan, ScannedConfirm, Expired, Refused, Error, Success,
    }
}

class LoginViewModel : ViewModel() {

    private val _ui = MutableStateFlow(LoginUiState())
    val ui: StateFlow<LoginUiState> = _ui.asStateFlow()

    /** 发起（或重试）扫码登录 */
    fun startLogin() {
        _ui.value = LoginUiState()
        viewModelScope.launch {
            ServiceLocator.qrLogin.login { event ->
                when (event) {
                    is QrLoginEvent.QrReady ->
                        _ui.value = LoginUiState(LoginUiState.QrStatus.Ready, event.bytes)

                    QrLoginEvent.WaitingScan ->
                        _ui.value = _ui.value.copy(status = LoginUiState.QrStatus.WaitingScan)

                    QrLoginEvent.ScannedConfirm ->
                        _ui.value = _ui.value.copy(status = LoginUiState.QrStatus.ScannedConfirm)

                    QrLoginEvent.Expired ->
                        _ui.value = _ui.value.copy(status = LoginUiState.QrStatus.Expired)

                    QrLoginEvent.Refused ->
                        _ui.value = _ui.value.copy(status = LoginUiState.QrStatus.Refused)

                    is QrLoginEvent.Error ->
                        _ui.value = _ui.value.copy(
                            status = LoginUiState.QrStatus.Error,
                            message = event.message,
                        )

                    is QrLoginEvent.Success -> {
                        ServiceLocator.onLoginSuccess(event.credential)
                        _ui.value = _ui.value.copy(status = LoginUiState.QrStatus.Success)
                    }
                }
            }
        }
    }

    fun retry() = startLogin()
}
