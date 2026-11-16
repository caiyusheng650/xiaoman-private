package dev.xiaoman.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.xiaoman.app.R

internal enum class SetupTopic(@StringRes val title: Int, @StringRes val detail: Int) {
    ASSISTANT(R.string.assist_settings_title, R.string.guide_assistant_detail),
    ACCESSIBILITY(R.string.guide_accessibility_title, R.string.guide_accessibility_detail),
    NOTIFICATIONS(R.string.guide_notifications_title, R.string.guide_notifications_detail),
    MODELS(R.string.guide_models_title, R.string.guide_models_detail),
    AUTOMATION(R.string.guide_automation_title, R.string.guide_automation_detail),
    PET(R.string.pet_settings_title, R.string.guide_pet_detail),
}

@Composable
internal fun SetupGuideEntry(onClick: () -> Unit) {
    StandardCard(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(stringResource(R.string.guide_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.guide_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
    }
}

@Composable
internal fun SetupGuideScreen(initialTopic: SetupTopic?, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    // A contextual link goes straight to the requested section.
    var expanded by rememberSaveable(initialTopic) { mutableStateOf(initialTopic) }
    val topics = if (initialTopic == null) SetupTopic.entries.toList()
        else listOf(initialTopic) + SetupTopic.entries.filterNot { it == initialTopic }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.pageWidth().padding(vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            }
            Text(
                stringResource(R.string.guide_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
        }
        Column(
            Modifier.weight(1f).pageWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.pageHorizontal).padding(bottom = Space.lg),
            verticalArrangement = Arrangement.spacedBy(Dimens.cardGap),
        ) {
            topics.forEach { topic ->
                val open = expanded == topic
                val state = stringResource(if (open) R.string.guide_expanded else R.string.guide_collapsed)
                StandardCard {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .semantics { stateDescription = state }
                            .clickable(role = Role.Button) { expanded = if (open) null else topic },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                            Text(stringResource(topic.title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(topic.detail),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                    }
                    if (open) {
                        HorizontalDivider(Modifier.padding(vertical = Space.md))
                        TopicInstructions(topic)
                    }
                }
            }
        }
    }
}

@Composable
private fun TopicInstructions(topic: SetupTopic) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        when (topic) {
            SetupTopic.ASSISTANT -> {
                GuideStep(R.string.assist_guide_default_title, R.string.assist_guide_default_body)
                GuideStep(R.string.assist_guide_launch_title, R.string.assist_guide_launch_body)
                GuideStep(R.string.assist_guide_type_title, R.string.assist_guide_type_body)
                AssistantSettingsButton()
            }
            SetupTopic.ACCESSIBILITY -> {
                Text(stringResource(R.string.guide_accessibility_body), style = MaterialTheme.typography.bodyMedium)
                SystemSettingsButton(R.string.guide_accessibility_open, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            SetupTopic.NOTIFICATIONS -> {
                val context = LocalContext.current
                Text(stringResource(R.string.guide_notifications_body), style = MaterialTheme.typography.bodyMedium)
                SystemSettingsButton(
                    R.string.guide_notifications_open,
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            }
            SetupTopic.MODELS -> {
                GuideStep(R.string.settings_chat_model, R.string.guide_chat_model_body)
                GuideStep(R.string.settings_operator_model, R.string.guide_operator_model_body)
            }
            SetupTopic.AUTOMATION -> {
                GuideStep(R.string.settings_max_steps, R.string.guide_steps_body)
                GuideStep(R.string.settings_learn, R.string.guide_learn_body)
            }
            SetupTopic.PET -> {
                GuideStep(R.string.pet_guide_what_title, R.string.pet_guide_what_body)
                GuideStep(R.string.pet_guide_get_title, R.string.pet_guide_get_body)
                GuideStep(R.string.pet_guide_upload_title, R.string.pet_guide_upload_body)
            }
        }
    }
}

@Composable
internal fun AssistantSettingsButton(emphasized: Boolean = false) {
    SystemSettingsButton(
        R.string.assist_settings_open,
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        emphasized = emphasized,
    )
}

@Composable
private fun SystemSettingsButton(
    @StringRes label: Int,
    intent: Intent,
    fallback: Intent? = null,
    emphasized: Boolean = false,
) {
    val context = LocalContext.current
    val open: () -> Unit = {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(fallback ?: Intent(Settings.ACTION_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, R.string.guide_open_failed, Toast.LENGTH_LONG).show()
            }
        }
    }
    if (emphasized) {
        FilledTonalButton(onClick = open) { Text(stringResource(label)) }
    } else {
        OutlinedButton(onClick = open) { Text(stringResource(label)) }
    }
}

@Composable
private fun GuideStep(@StringRes title: Int, @StringRes body: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
    }
}
