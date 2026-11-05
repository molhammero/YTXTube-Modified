package com.tensei.ytxtube

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Visible diagnostic panel. The user can copy the complete log and send it back
 * without needing adb/logcat; the log contains no cookies or auth headers.
 */
@Composable
fun WatchSyncPanel(context: Context) {
    val c = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val version = WatchSyncLog.version
    val fullReport = remember(version) { WatchSyncLog.report() }
    val shownReport = remember(version) { WatchSyncLog.report(90) }
    val last = remember(version) { WatchSyncLog.lastVideo() }

    var verifying by remember { mutableStateOf(false) }
    var marking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var tvLinked by remember { mutableStateOf(TvAuth.isLinked) }
    var tvCode by remember { mutableStateOf<TvAuth.DeviceCode?>(null) }
    var tvBusy by remember { mutableStateOf(false) }
    var tvMessage by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = I18n.t("watch_sync_title", "YouTube history sync"),
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            color = c.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Signed in: ${if (Account.signedIn) "yes" else "no"}",
            fontSize = 12.sp,
            color = if (Account.signedIn) c.primary else c.error,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "Last video: ${last?.let { it.second ?: it.first } ?: "none"}",
            fontSize = 12.sp,
            color = c.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "The app checks the official YouTube history at +8s and +30s after a marked watch. Cookies and Authorization headers are never recorded.",
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = c.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = c.onSurface.copy(alpha = 0.08f))
        Spacer(Modifier.height(8.dp))
        Text(
            text = "TV OAuth: ${if (tvLinked) "linked" else "not linked"}",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (tvLinked) c.primary else c.onSurfaceVariant,
        )
        Text(
            text = "YouTube attaches third-party history writes to a TV OAuth session. Link once; this does not open YouTube or use a WebView.",
            fontSize = 10.sp,
            lineHeight = 13.sp,
            color = c.onSurfaceVariant,
        )
        if (!tvLinked) {
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        tvBusy = true
                        tvMessage = runCatching {
                            withContext(Dispatchers.IO) { TvAuth.requestCode() }
                        }.fold(
                            onSuccess = { code ->
                                tvCode = code
                                "Code ready. Open ${code.verificationUrl} and enter the code."
                            },
                            onFailure = { "TV link failed: ${it.message ?: it.javaClass.simpleName}" },
                        )
                        tvBusy = false
                    }
                },
                enabled = !tvBusy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(if (tvBusy) "Preparing TV link…" else "Link TV account", fontSize = 11.sp)
            }
            tvCode?.let { code ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Code: ${code.userCode}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = c.primary,
                )
                Text(
                    text = "Open ${code.verificationUrl} on any device, enter this code, then come back and check authorization.",
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    color = c.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                                ClipData.newPlainText("YouTube TV code", code.userCode),
                            )
                            tvMessage = "Code copied."
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("Copy code", fontSize = 10.sp) }
                    OutlinedButton(
                        onClick = {
                            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                                ClipData.newPlainText("YouTube device page", code.verificationUrl),
                            )
                            tvMessage = "Device page copied."
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("Copy page", fontSize = 10.sp) }
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            tvBusy = true
                            val result = runCatching {
                                withContext(Dispatchers.IO) { TvAuth.completeLink(code) }
                            }
                            tvBusy = false
                            tvMessage = result.fold(
                                onSuccess = { linked ->
                                    if (linked) {
                                        tvLinked = true
                                        tvCode = null
                                        "TV OAuth linked. Press Test final mark to send a stats write."
                                    } else {
                                        "Not approved yet. Enter the code, then check again."
                                    }
                                },
                                onFailure = { "Authorization check failed: ${it.message ?: it.javaClass.simpleName}" },
                            )
                        }
                    },
                    enabled = !tvBusy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(if (tvBusy) "Checking…" else "Check authorization", fontSize = 11.sp)
                }
            }
        } else {
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = {
                    TvAuth.unlink()
                    tvLinked = false
                    tvCode = null
                    tvMessage = "TV OAuth unlinked."
                },
                shape = RoundedCornerShape(12.dp),
            ) { Text("Unlink TV account", fontSize = 10.sp) }
        }
        tvMessage?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, fontSize = 11.sp, color = c.primary)
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = c.onSurface.copy(alpha = 0.08f))
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(
                        ClipData.newPlainText("YTXTube watch sync report", fullReport),
                    )
                    message = "Report copied to clipboard."
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Copy report", fontSize = 11.sp)
            }
            OutlinedButton(
                onClick = {
                    val video = last
                    if (video == null) {
                        message = "Play a video first."
                    } else {
                        scope.launch {
                            verifying = true
                            val result = runCatching {
                                InnerTube.verifyHistoryNow(video.first, "manual")
                            }
                            verifying = false
                            message = result.fold(
                                onSuccess = { found ->
                                    when (found) {
                                        true -> "Found in the official YouTube history."
                                        false -> "Not found in the first 3 official history pages."
                                        null -> "Sign in to check the official history."
                                    }
                                },
                                onFailure = { "Check failed: ${it.message ?: it.javaClass.simpleName}" },
                            )
                        }
                    }
                },
                enabled = !verifying,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(if (verifying) "Checking…" else "Verify now", fontSize = 11.sp)
            }
        }

        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = {
                scope.launch {
                    marking = true
                    val result = withContext(Dispatchers.IO) { InnerTube.markLastNow() }
                    marking = false
                    message = result
                }
            },
            enabled = !marking && last != null,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(if (marking) "Sending final mark…" else "Test final mark (last video)", fontSize = 11.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Diagnostic: sends the final playback + watchtime pair now and marks the last video as fully watched. History is then checked automatically.",
            fontSize = 10.sp,
            lineHeight = 13.sp,
            color = c.onSurfaceVariant,
        )

        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = {
                WatchSyncLog.clear()
                message = "Diagnostic log cleared."
            },
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Clear log", fontSize = 11.sp)
        }

        message?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, fontSize = 11.sp, color = c.primary)
        }

        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = c.onSurface.copy(alpha = 0.08f))
        Spacer(Modifier.height(8.dp))

        SelectionContainer {
            Text(
                text = shownReport,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.5.sp,
                lineHeight = 12.5.sp,
                color = c.onSurfaceVariant,
            )
        }
    }
}
