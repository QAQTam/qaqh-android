package com.qaqh.harness.ui.approvals

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * 审批操作前的生物认证把关（P4）。允许 BIOMETRIC_WEAK，未录入生物识别时
 * 降级为设备凭据（PIN/图案/密码）。取消/失败走 [onDenied]。
 */
fun FragmentActivity.authenticateForApproval(
    title: String,
    subtitle: String,
    onAllowed: () -> Unit,
    onDenied: () -> Unit = {},
) {
    val prompt = BiometricPrompt(
        this,
        ContextCompat.getMainExecutor(this),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onAllowed()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onDenied()
            }
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        .build()
    prompt.authenticate(info)
}
