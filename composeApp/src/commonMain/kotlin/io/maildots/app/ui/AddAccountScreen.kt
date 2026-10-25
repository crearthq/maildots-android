package io.maildots.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.maildots.app.AppController
import io.maildots.app.AppState
import io.maildots.app.Screen

/** Marka yazı işareti (metin olarak; vektör logo sonraki adımda eklenecek). */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    Text(
        "maildots.",
        modifier = modifier,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.04).em,
    )
}

@Composable
fun AddAccountScreen(state: AppState, screen: Screen.AddAccount, controller: AppController) {
    val s = LocalStrings.current
    val focus = LocalFocusManager.current
    var email by rememberSaveable { mutableStateOf("") }
    // Şifre kaydedilebilir durumda TUTULMAZ (süreç ölürse diske yazılmasın).
    var password by androidx.compose.runtime.remember { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val submit = { if (!state.busy) controller.addAccount(email, password) }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
            Wordmark()
            Spacer(Modifier.height(40.dp))
            Text(
                if (screen.canCancel) s.addAccountTitle else s.signInTitle,
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                s.signInBody,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))

            OutlinedTextField(
                value = email,
                onValueChange = { email = it.trim() },
                label = { Text(s.email) },
                placeholder = { Text(s.emailHint) },
                singleLine = true,
                enabled = !state.busy,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(s.password) },
                singleLine = true,
                enabled = !state.busy,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (showPassword) s.hidePassword else s.showPassword,
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); submit() }),
                modifier = Modifier.fillMaxWidth(),
            )

            state.formError?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    s.error(it),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { focus.clearFocus(); submit() },
                enabled = !state.busy,
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(s.signingIn)
                } else {
                    Text(s.signIn)
                }
            }
            if (screen.canCancel) {
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = { controller.back() },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(s.cancel) }
            }

            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp).padding(top = 2.dp),
                )
                Text(
                    s.securedNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
