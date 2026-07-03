package zip.arcanum.core.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import zip.arcanum.R
import zip.arcanum.core.security.AppPasswordPolicy

enum class AppPasswordInputMode {
    Numeric,
    Keyboard
}

@Composable
fun AppPasswordInputModeButton(
    mode: AppPasswordInputMode,
    onModeChange: (AppPasswordInputMode) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val nextMode = if (mode == AppPasswordInputMode.Numeric) {
        AppPasswordInputMode.Keyboard
    } else {
        AppPasswordInputMode.Numeric
    }
    IconButton(
        onClick = { if (enabled) onModeChange(nextMode) },
        enabled = enabled,
        modifier = modifier
    ) {
        Icon(
            imageVector = if (mode == AppPasswordInputMode.Numeric) Icons.Outlined.Keyboard else Icons.Outlined.Dialpad,
            contentDescription = stringResource(
                if (mode == AppPasswordInputMode.Numeric) {
                    R.string.app_password_use_keyboard
                } else {
                    R.string.app_password_use_pin_keypad
                }
            )
        )
    }
}

@Composable
fun AppPasswordKeyboardField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    isError: Boolean,
    onUseNumeric: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(AppPasswordPolicy.sanitize(it)) },
        enabled = enabled,
        singleLine = true,
        isError = isError,
        label = { Text(stringResource(R.string.app_password_keyboard_label)) },
        supportingText = {
            Text(
                stringResource(
                    if (isError && value.isNotEmpty() && !AppPasswordPolicy.isValid(value)) {
                        R.string.app_password_keyboard_error
                    } else {
                        R.string.app_password_keyboard_hint
                    }
                )
            )
        },
        visualTransformation = PasswordVisualTransformation(),
        trailingIcon = {
            AppPasswordInputModeButton(
                mode = AppPasswordInputMode.Keyboard,
                onModeChange = { onUseNumeric() },
                enabled = enabled
            )
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done
        ),
        modifier = modifier.focusRequester(focusRequester)
    )
}
