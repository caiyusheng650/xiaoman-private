package dev.xiaoman.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xiaoman.app.settings.AppLanguage
import dev.xiaoman.app.settings.ThemeMode
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R
import dev.xiaoman.app.settings.SettingsForm
import dev.xiaoman.llm.ModelCatalog
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.SocketTimeoutException

@Composable
fun SettingsScreen(graph: AppGraph) {
    val store = graph.settings
    var form by remember { mutableStateOf(SettingsForm.of(store.current)) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val parsed = form.toSettings()
    val feedback = rememberPageFeedbackState()

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        // Fixed page header, same component as every other tab — the old in-scroll titleLarge
        // disappeared while scrolling and used a larger type size than any other page.
        ScreenHeader(title = stringResource(R.string.settings_title))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.align(Alignment.TopCenter).pageWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = Dimens.pageHorizontal)
                    .padding(bottom = Space.lg),
                verticalArrangement = Arrangement.spacedBy(Dimens.cardGap),
            ) {
            AppearanceCard(graph)
            AssistantGuideCard()

            SettingsCard(
                title = stringResource(R.string.settings_chat_model),
                description = stringResource(R.string.settings_chat_description),
            ) {
                ModelFields(form.chat) { form = form.copy(chat = it) }
            }

            SettingsCard(
                title = stringResource(R.string.settings_operator_model),
                description = stringResource(R.string.settings_operator_description),
                action = {
                    TextButton(onClick = {
                        form = form.copy(operator = form.operator.copy(baseUrl = form.chat.baseUrl, apiKey = form.chat.apiKey))
                    }) { Text(stringResource(R.string.settings_copy_chat)) }
                },
            ) {
                ModelFields(form.operator) { form = form.copy(operator = it) }
            }

            SettingsCard(title = stringResource(R.string.settings_agent)) {
                OutlinedTextField(
                    value = form.maxSteps,
                    onValueChange = { form = form.copy(maxSteps = it) },
                    label = { Text(stringResource(R.string.settings_max_steps)) },
                    isError = !form.maxStepsValid,
                    supportingText = { if (!form.maxStepsValid) Text(stringResource(R.string.settings_invalid_number)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_learn), modifier = Modifier.weight(1f))
                    Switch(checked = form.learnFromSuccess, onCheckedChange = {
                        form = form.copy(learnFromSuccess = it)
                    })
                }
            }

            SettingsCard(
                title = stringResource(R.string.settings_folders),
                description = stringResource(R.string.settings_folders_hint),
            ) {
                // Paths stay out of the UI; whoever actually needs them can copy them here.
                TextButton(onClick = {
                    val paths = listOf(graph.skillsDir, graph.memoryDir, graph.tracesDir).joinToString("\n") { it.path }
                    clipboard.setText(AnnotatedString(paths))
                    copied = true
                }) { Text(stringResource(if (copied) R.string.settings_path_copied else R.string.settings_copy_path)) }
            }
        }
            // Floats over the end of the scroll body, never over the save button below it.
            PageFeedback(
                feedback,
                Modifier.align(Alignment.BottomCenter)
                    .padding(horizontal = Dimens.pageHorizontal, vertical = Space.md),
            )
        }

        BottomActionBar {
            val invalidForm = stringResource(R.string.settings_invalid_form)
            val savedMessage = stringResource(R.string.settings_saved)
            val failedMessage = stringResource(R.string.settings_save_failed)
            Button(
                onClick = {
                    val settings = parsed
                    if (settings == null) {
                        feedback.show(invalidForm, isError = true)
                        return@Button
                    }
                    try {
                        store.save(settings)
                        feedback.show(savedMessage)
                    } catch (e: Exception) {
                        feedback.show(failedMessage, isError = true)
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.settings_save)) }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    description: String? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    StandardCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                description?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            action?.invoke()
        }
        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelFields(model: SettingsForm.ModelForm, onChange: (SettingsForm.ModelForm) -> Unit) {
    val context = LocalContext.current
    var showKey by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val canFetch = model.baseUrl.isNotBlank() && model.apiKey.isNotBlank() && !loading

    OutlinedTextField(
        value = model.baseUrl,
        onValueChange = { onChange(model.copy(baseUrl = it)); problem = null },
        label = { Text(stringResource(R.string.settings_base_url)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = model.apiKey,
        onValueChange = { onChange(model.copy(apiKey = it)); problem = null },
        label = { Text(stringResource(R.string.settings_api_key)) },
        singleLine = true,
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            IconButton(onClick = { showKey = !showKey }) {
                Icon(
                    if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(if (showKey) R.string.settings_hide_key else R.string.settings_show_key),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            enabled = canFetch,
            onClick = {
                loading = true
                problem = null
                scope.launch {
                    try {
                        val ids = ModelCatalog.listModels(model.baseUrl, model.apiKey)
                        models = ids
                        if (ids.isEmpty()) {
                            problem = context.getString(R.string.model_none)
                        } else {
                            expanded = true
                            if (model.model.isBlank() && ids.size == 1) onChange(model.copy(model = ids.first()))
                        }
                    } catch (e: SocketTimeoutException) {
                        problem = context.getString(R.string.model_timeout)
                    } catch (e: IOException) {
                        problem = context.getString(R.string.model_network)
                    } catch (e: Exception) {
                        problem = e.message ?: context.getString(R.string.model_fetch_error)
                    } finally {
                        loading = false
                    }
                }
            },
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.model_fetching), Modifier.padding(start = 8.dp))
            } else {
                Text(stringResource(R.string.model_fetch))
            }
        }
        Text(stringResource(R.string.model_fetch_hint), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (models.isNotEmpty()) expanded = !expanded }) {
        OutlinedTextField(
            value = model.model,
            onValueChange = { onChange(model.copy(model = it)) },
            label = { Text(stringResource(R.string.settings_model)) },
            singleLine = true,
            isError = problem != null,
            supportingText = { problem?.let { Text(it) } },
            trailingIcon = {
                if (models.isNotEmpty()) ExposedDropdownMenuDefaults.TrailingIcon(expanded) else null
            },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            models.forEach { id ->
                DropdownMenuItem(
                    text = { Text(id) },
                    onClick = { onChange(model.copy(model = id)); expanded = false },
                )
            }
        }
    }

    OutlinedTextField(
        value = model.temperature,
        onValueChange = { onChange(model.copy(temperature = it)) },
        label = { Text(stringResource(R.string.settings_temperature)) },
        isError = !model.temperatureValid,
        supportingText = { if (!model.temperatureValid) Text(stringResource(R.string.settings_invalid_number)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AppearanceCard(graph: AppGraph) {
    val appearance = graph.appearance
    val language by appearance.language.collectAsStateWithLifecycle()
    val theme by appearance.theme.collectAsStateWithLifecycle()
    val system = stringResource(R.string.settings_follow_system)
    SettingsCard(title = stringResource(R.string.settings_appearance)) {
        PreferenceMenu(
            stringResource(R.string.settings_language),
            language,
            mapOf(
                AppLanguage.SYSTEM to system,
                AppLanguage.CHINESE to stringResource(R.string.settings_language_chinese),
                AppLanguage.ENGLISH to stringResource(R.string.settings_language_english),
            ),
            appearance::setLanguage,
        )
        PreferenceMenu(
            stringResource(R.string.settings_theme),
            theme,
            mapOf(
                ThemeMode.SYSTEM to system,
                ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
                ThemeMode.DARK to stringResource(R.string.settings_theme_dark),
            ),
            appearance::setTheme,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> PreferenceMenu(label: String, selected: T, choices: Map<T, String>, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = choices.getValue(selected),
            onValueChange = {},
            label = { Text(label) },
            readOnly = true,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (value, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = {
                    expanded = false
                    onSelect(value)
                })
            }
        }
    }
}

@Composable
private fun AssistantGuideCard() {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    SettingsCard(
        title = stringResource(R.string.assist_settings_title),
        description = stringResource(R.string.assist_settings_description),
    ) {
        OutlinedButton(onClick = {
            try {
                context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, R.string.assist_guide_default_body, Toast.LENGTH_LONG).show()
            }
        }) { Text(stringResource(R.string.assist_settings_open)) }
        TextButton(onClick = { expanded = !expanded }) {
            Text(stringResource(if (expanded) R.string.assist_guide_hide else R.string.assist_guide_show))
        }
        if (expanded) {
            GuideStep(R.string.assist_guide_default_title, R.string.assist_guide_default_body)
            GuideStep(R.string.assist_guide_launch_title, R.string.assist_guide_launch_body)
            GuideStep(R.string.assist_guide_mic_title, R.string.assist_guide_mic_body)
            GuideStep(R.string.assist_guide_help_title, R.string.assist_guide_help_body)
        }
    }
}

@Composable
private fun GuideStep(@androidx.annotation.StringRes title: Int, @androidx.annotation.StringRes body: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
