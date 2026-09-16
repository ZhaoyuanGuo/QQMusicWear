package com.qmusic.wear.ui.login

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import com.qmusic.wear.R
import com.qmusic.wear.ui.components.GlassPanel
import com.qmusic.wear.ui.login.LoginUiState.QrStatus

@Composable
fun LoginScreen(
    onBack: () -> Unit,
    onSuccess: () -> Unit,
    vm: LoginViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.startLogin() }
    LaunchedEffect(ui.status) {
        if (ui.status == QrStatus.Success) {
            kotlinx.coroutines.delay(600)
            onSuccess()
        }
    }

    ScreenScaffold(
        timeText = { TimeText() },
    ) { contentPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.login_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(7.dp))

            when (ui.status) {
                QrStatus.Loading, QrStatus.Ready, QrStatus.WaitingScan, QrStatus.ScannedConfirm -> {
                    GlassPanel {
                        val bytes = ui.qrBytes
                        if (bytes != null) {
                            val decoded = runCatching {
                                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            }.getOrNull() ?: android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
                            Image(
                                bitmap = decoded.asImageBitmap(),
                                contentDescription = "QQ 登录二维码",
                                modifier = Modifier
                                    .size(104.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White)
                                    .padding(4.dp),
                            )
                        } else {
                            Box(
                                Modifier.size(104.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                androidx.wear.compose.material3.CircularProgressIndicator()
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = when (ui.status) {
                                QrStatus.ScannedConfirm -> stringResource(R.string.login_scanned)
                                else -> stringResource(R.string.login_wait_scan)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                QrStatus.Expired, QrStatus.Refused, QrStatus.Error -> {
                    Text(
                        text = ui.message.ifEmpty { stringResource(R.string.login_expired) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { vm.retry() }) {
                        Text(stringResource(R.string.cd_refresh))
                    }
                }
                QrStatus.Success -> {
                    Text(
                        text = stringResource(R.string.login_success),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            androidx.wear.compose.material3.Button(
                onClick = onBack,
                modifier = Modifier.padding(bottom = 4.dp),
                colors = androidx.wear.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Text(stringResource(R.string.login_guest), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
