package com.vidgod.editor.ui.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vidgod.editor.editor.EditorViewModel
import com.vidgod.editor.features.AutoStyles
import com.vidgod.editor.ui.common.PanelHeader
import com.vidgod.editor.ui.theme.VG

@Composable
fun StylesPanel(vm: EditorViewModel, close: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PanelHeader("One-tap styles", close)
        Text(
            "Applies transitions, colour and effects to the whole video. Undo anytime.",
            color = VG.TextDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp)) {
            AutoStyles.all.forEach { style ->
                Column(
                    Modifier.padding(end = 10.dp).width(120.dp).clip(RoundedCornerShape(12.dp)).background(VG.Surface2)
                        .clickable {
                            if (vm.project.value.clips.isEmpty()) return@clickable vm.toast("Add clips first")
                            vm.update { style.apply(it) }
                            vm.toast("${style.name} style applied")
                        }
                        .padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(style.emoji, fontSize = 30.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(style.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(style.description, color = VG.TextDim, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 3)
                }
            }
        }
    }
}
