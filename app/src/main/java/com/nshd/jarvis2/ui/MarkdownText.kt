package com.nshd.jarvis2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Segments ───────────────────────────────────────────────────────────

private sealed interface Segment {
    data class TextSeg(val text: String) : Segment
    data class CodeSeg(val lang: String, val code: String) : Segment
}

private fun parseSegments(content: String): List<Segment> {
    val segments = mutableListOf<Segment>()
    var rest = content
    while (true) {
        val fence = rest.indexOf("```")
        if (fence < 0) {
            if (rest.isNotBlank()) segments += Segment.TextSeg(rest)
            break
        }
        if (fence > 0 && rest.substring(0, fence).isNotBlank()) {
            segments += Segment.TextSeg(rest.substring(0, fence))
        }
        val after = rest.substring(fence + 3)
        val nl = after.indexOf('\n')
        val lang = if (nl >= 0) after.substring(0, nl).trim() else ""
        val codeStart = if (nl >= 0) nl + 1 else 0
        val end = after.indexOf("```", codeStart)
        if (end < 0) {
            segments += Segment.CodeSeg(lang, after.substring(codeStart).trimEnd('\n'))
            break
        }
        segments += Segment.CodeSeg(lang, after.substring(codeStart, end).trimEnd('\n'))
        rest = after.substring(end + 3)
    }
    if (segments.isEmpty()) segments += Segment.TextSeg(content)
    return segments
}

// ── Inline parsing: **bold** and `code` ────────────────────────────────

private fun parseInline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append("**")
                    i += 2
                }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = Color(0x2A000000)
                        )
                    ) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else {
                    append('`')
                    i += 1
                }
            }
            else -> {
                append(text[i])
                i += 1
            }
        }
    }
}

// ── Public composable ──────────────────────────────────────────────────

/**
 * Minimal safe markdown renderer for assistant replies: paragraphs with
 * **bold** / `inline code`, and fenced blocks on a proper CODE CANVAS —
 * dark panel, language header + Copy, monospace, horizontal scroll, never
 * wraps. Mirrors the simulator's codeblock fix.
 */
@Composable
fun MarkdownText(content: String, textColor: Color) {
    val segments = remember(content) { parseSegments(content) }
    Column {
        segments.forEach { seg ->
            when (seg) {
                is Segment.TextSeg -> {
                    val trimmed = seg.text.trim('\n')
                    if (trimmed.isNotBlank()) {
                        Text(
                            text = parseInline(trimmed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
                is Segment.CodeSeg -> CodeCanvas(lang = seg.lang, code = seg.code)
            }
        }
    }
}

// D1: deep-plum canvas (#241A33 — the snackbar plum) so code reads as a
// plum-family object inside the purple design system, in every theme.
private val CanvasBackground = Color(0xFF241A33)
private val CanvasCodeColor = Color(0xFFF5F0FA)
private val CanvasHeaderColor = Color(0xFFCFC4D8)
private val CanvasAccent = Color(0xFFD8B4FE)

@Composable
private fun CodeCanvas(lang: String, code: String) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(CanvasBackground, RoundedCornerShape(JarvisDimens.radiusField))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = lang.ifBlank { "code" }.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = CanvasHeaderColor
            )
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                Text("Copy", color = CanvasAccent, style = MaterialTheme.typography.labelMedium)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            Text(
                text = code,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = CanvasCodeColor,
                softWrap = false,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
    }
}
