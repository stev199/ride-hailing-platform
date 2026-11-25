package com.ridehailing.core.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ridehailing.core.data.prefs.ThemeStore

/**
 * Settings tab (P2-4): the auth + preferences surface shared by both apps.
 * Signed out -> login card (password login against the Phase 1 backend; server errors
 * verbatim). Signed in -> session card + theme picker (real, applied through the compose
 * theme) + language picker (PERSISTED now; per-app locale application is a follow-up -
 * honest note shown in the UI).
 */
@Composable
fun SessionScreen(
    currentTheme: String?,
    currentLanguage: String?,
    onThemeChange: (String?) -> Unit,
    onLanguageChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: SessionViewModel = hiltViewModel()
    val session by vm.session.collectAsState()
    val signedIn = !session?.accessToken.isNullOrEmpty()

    if (signedIn) {
        SignedInCard(
            vm = vm,
            currentTheme = currentTheme,
            onThemeChange = onThemeChange,
            currentLanguage = currentLanguage,
            onLanguageChange = onLanguageChange,
            modifier = modifier,
        )
    } else {
        LoginCard(vm = vm, modifier = modifier)
    }
}

@Composable
private fun LoginCard(vm: SessionViewModel, modifier: Modifier = Modifier) {
    val signingIn by vm.signingIn.collectAsState()
    val error by vm.loginError.collectAsState()
    var identifier by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    Card(modifier = modifier.fillMaxWidth().padding(12.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = stringResource(R.string.auth_title), style = MaterialTheme.typography.titleMedium)
            Text(text = stringResource(R.string.auth_subtitle), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = identifier,
                onValueChange = { identifier = it },
                singleLine = true,
                label = { Text(stringResource(R.string.auth_identifier)) },
                modifier = Modifier.fillMaxWidth().semantics { testTag = "login_identifier" },
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                singleLine = true,
                label = { Text(stringResource(R.string.auth_password)) },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().semantics { testTag = "login_password" },
            )
            error?.let { e ->
                Text(
                    text = "${e.error.code}: ${e.error.message}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { testTag = "login_error" },
                )
            }
            Button(
                onClick = { vm.login(identifier, password) },
                enabled = !signingIn && identifier.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier.semantics { testTag = "login_submit" },
            ) {
                Text(if (signingIn) stringResource(R.string.auth_signingIn) else stringResource(R.string.auth_signIn))
            }
        }
    }
}

@Composable
private fun SignedInCard(
    vm: SessionViewModel,
    currentTheme: String?,
    onThemeChange: (String?) -> Unit,
    currentLanguage: String?,
    onLanguageChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val session by vm.session.collectAsState()
    Card(modifier = modifier.fillMaxWidth().padding(12.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = stringResource(R.string.auth_session_title), style = MaterialTheme.typography.titleMedium)
            session?.let { s ->
                Text(
                    text = stringResource(R.string.auth_signedInAs, s.userId ?: "-"),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { testTag = "session_info" },
                )
            }
            TextButton(onClick = { vm.logout() }, modifier = Modifier.semantics { testTag = "session_logout" }) {
                Text(stringResource(R.string.auth_logOut))
            }

            Text(text = stringResource(R.string.auth_theme), style = MaterialTheme.typography.titleSmall)
            ChoiceRow(
                current = currentTheme,
                onSelect = onThemeChange,
                choices = listOf(
                    Choice(null, R.string.auth_themeSystem, "theme_system"),
                    Choice(ThemeStore.THEME_LIGHT, R.string.auth_themeLight, "theme_light"),
                    Choice(ThemeStore.THEME_DARK, R.string.auth_themeDark, "theme_dark"),
                ),
            )

            Text(text = stringResource(R.string.auth_language), style = MaterialTheme.typography.titleSmall)
            ChoiceRow(
                current = currentLanguage,
                onSelect = onLanguageChange,
                choices = listOf(
                    Choice("en", R.string.auth_languageEn, "lang_en"),
                    Choice("fr", R.string.auth_languageFr, "lang_fr"),
                    Choice("ar", R.string.auth_languageAr, "lang_ar"),
                ),
            )
            Text(text = stringResource(R.string.auth_languageNote), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private class Choice(val value: String?, val labelRes: Int, val tag: String)

@Composable
private fun ChoiceRow(current: String?, onSelect: (String?) -> Unit, choices: List<Choice>) {
    Column {
        choices.forEach { choice ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = current == choice.value,
                    onClick = { onSelect(choice.value) },
                    modifier = Modifier.semantics { testTag = choice.tag },
                )
                Text(stringResource(choice.labelRes), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
