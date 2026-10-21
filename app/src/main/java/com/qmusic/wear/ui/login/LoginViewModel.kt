package com.qmusic.wear.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.data.auth.LoginType
import com.qmusic.wear.data.auth.QrLoginEvent
import com.qmusic.wear.data.source.SourceManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 登录页 UI 状态 + 二维码状态机 */
data class LoginUiState(
    val status: QrStatus = QrStatus.Loading,
    val qrBytes: ByteArray? = null,
    val message: String = "",
    /** 当前扫码方式（QQ / 微信） */
    val type: LoginType = LoginType.QQ,
    /** 当前音乐源是否支持微信扫码；false 时不显示切换入口（酷狗/网易云等源） */
    val wxAvailable: Boolean = false,
) {
    enum class QrStatus {
        Loading, Ready, WaitingScan, ScannedConfirm, Expired, Refused, Error, Success,
    }
}

class LoginViewModel : ViewModel() {

    private val _ui = MutableStateFlow(
        LoginUiState(type = if (ServiceLocator.settingsStore.loginWxFlow.value) LoginType.WECHAT else LoginType.QQ),
    )
    val ui: StateFlow<LoginUiState> = _ui.asStateFlow()

    /** 当前登录协程：重进页面/切方式时先取消，避免两条链并行回写状态 */
    private var loginJob: Job? = null

    /**
     * 登录轮次号。取消源轮询到它真正退出有几秒延迟，期间上一轮的残留事件仍会投递过来；
     * 只接受轮次号与当前一致的事件，防止旧二维码的过期/错误状态覆盖新二维码。
     */
    private var generation = 0

    init {
        // 进页面时源可能还在下载或正在切源，能力探测跟随流更新；
        // 若新源不支持微信则收回入口并回落 QQ，否则会停在一个调不通的方式上
        viewModelScope.launch {
            SourceManager.capabilitiesFlow.collect { caps ->
                val wx = LoginType.WECHAT.handler in caps
                _ui.update { s ->
                    val type = if (!wx && s.type == LoginType.WECHAT) LoginType.QQ else s.type
                    if (s.wxAvailable == wx && s.type == type) s else s.copy(wxAvailable = wx, type = type)
                }
            }
        }
    }

    /** 发起（或重试）当前扫码方式的登录 */
    fun startLogin() = start(_ui.value.type)

    /** 切换 QQ / 微信扫码：终止上一轮轮询并立刻出新码 */
    fun selectType(type: LoginType) {
        if (type == _ui.value.type) return
        _ui.update { it.copy(type = type) }
        // 记住选择：只装微信、没装 QQ 的用户不必每次重选
        ServiceLocator.settingsStore.setLoginWx(type == LoginType.WECHAT)
        start(type)
    }

    private fun start(type: LoginType) {
        val gen = ++generation
        loginJob?.cancel()
        // 登录轮询占着源的单线程引擎，不取消它下一次调用要排队等上一轮跑完
        ServiceLocator.qrLogin.cancel()
        val wxAvailable = _ui.value.wxAvailable
        _ui.value = LoginUiState(type = type, wxAvailable = wxAvailable)
        val onEvent: (QrLoginEvent) -> Unit = { event ->
            if (gen == generation) applyEvent(event)
        }
        loginJob = viewModelScope.launch {
            if (!SourceManager.ensureReady()) {
                _ui.value = _ui.value.copy(
                    status = LoginUiState.QrStatus.Error,
                    message = "音乐源未就绪，请检查网络后重试",
                )
                return@launch
            }
            ServiceLocator.qrLogin.login(type, onEvent)
        }
    }

    private fun applyEvent(event: QrLoginEvent) {
        when (event) {
            is QrLoginEvent.QrReady ->
                _ui.value = _ui.value.copy(status = LoginUiState.QrStatus.Ready, qrBytes = event.bytes)

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

    fun retry() = startLogin()

    /**
     * 离开登录页即停掉本轮登录。
     *
     * 登录页的 VM 挂在 Activity 作用域上，返回上级页面不会触发 onCleared；
     * 而登录轮询占着源引擎的单线程，不放手则之后所有接口调用都在排队。
     */
    fun abandon() {
        generation++
        loginJob?.cancel()
        loginJob = null
        ServiceLocator.qrLogin.cancel()
    }

    override fun onCleared() {
        // 离开登录页即放手：否则残留轮询会占住引擎，下次进登录页要等它跑完
        abandon()
        super.onCleared()
    }
}
