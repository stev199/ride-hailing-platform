package com.ridehailing.core.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal fun TabBody(text: String, modifier: Modifier = Modifier) {
    Text(text = text, modifier = modifier)
}
