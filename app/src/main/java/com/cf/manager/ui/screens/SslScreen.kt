package com.cf.manager.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cf.manager.data.AppConfig
import com.cf.manager.data.api.ApiClient
import com.cf.manager.data.local.AccountStorage
import com.cf.manager.data.model.ZoneItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SslScreen() {
    val context = LocalContext.current
    val storage = remember { AccountStorage(context) }
    val accounts = remember { storage.getAccounts() }
    val activeIdx = remember { storage.getActiveIndex() }
    val currAcc = accounts.getOrNull(activeIdx)
    val email = AppConfig.activeEmail.ifBlank { currAcc?.email ?: "" }
    val apiKey = AppConfig.activeApiKey.ifBlank { currAcc?.apiKey ?: "" }

    var zones by remember { mutableStateOf<List<ZoneItem>>(emptyList()) }
    var selectedZone by remember { mutableStateOf<ZoneItem?>(null) }
    var zoneExpanded by remember { mutableStateOf(false) }

    var sslMode by remember { mutableStateOf("full") }
    var alwaysHttps by remember { mutableStateOf(true) }

    var statusMsg by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val sslModes = listOf("off", "flexible", "full", "strict")

    fun loadSslSettings(zoneId: String) {
        scope.launch {
            try {
                val res = ApiClient.api.getSslSetting(zoneId)
                if (res.isSuccessful && res.body()?.success == true) {
                    val value = res.body()?.result?.get("value")?.asString ?: "full"
                    sslMode = value
                }
            } catch (_: Exception) {}
        }
    }

    fun loadZones() {
        if (email.isBlank() || apiKey.isBlank()) {
            statusMsg = "⚠️ Isi Email & API Key di tab Akun terlebih dahulu!"
            return
        }
        scope.launch {
            isLoading = true
            try {
                val res = ApiClient.api.listZones()
                if (res.isSuccessful && res.body()?.success == true) {
                    val list = res.body()?.result ?: emptyList()
                    zones = list
                    if (list.isNotEmpty()) {
                        selectedZone = list[0]
                        loadSslSettings(list[0].id)
                    }
                }
            } catch (e: Exception) {
                statusMsg = "Error: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(email, apiKey) {
        if (email.isNotEmpty() && apiKey.isNotEmpty()) loadZones()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("🔒 SSL / TLS Edge Encryption", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Konfigurasi mode enkripsi HTTPS domain Cloudflare", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            Spacer(modifier = Modifier.height(8.dp))

            if (zones.size > 1) {
                ExposedDropdownMenuBox(
                    expanded = zoneExpanded,
                    onExpandedChange = { zoneExpanded = !zoneExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedZone?.name ?: "Pilih Domain",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Pilih Domain") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = zoneExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = zoneExpanded,
                        onDismissRequest = { zoneExpanded = false }
                    ) {
                        zones.forEach { z ->
                            DropdownMenuItem(
                                text = { Text(z.name) },
                                onClick = {
                                    selectedZone = z
                                    zoneExpanded = false
                                    loadSslSettings(z.id)
                                }
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Mode Enkripsi SSL/TLS:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))

                    sslModes.forEach { mode ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = (sslMode == mode),
                                onClick = { sslMode = mode }
                            )
                            Text(
                                text = when (mode) {
                                    "off" -> "Off (Tidak Ada Enkripsi)"
                                    "flexible" -> "Flexible (Edge ke Browser Encrypted)"
                                    "full" -> "Full (Rekomendasi - Self Signed Origin)"
                                    else -> "Full (Strict) - Wajib Valid CA Certificate"
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = alwaysHttps,
                            onCheckedChange = { alwaysHttps = it }
                        )
                        Text("Always Use HTTPS (Redirect HTTP ke HTTPS otomatis)", style = MaterialTheme.typography.bodySmall)
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = {
                            val zone = selectedZone ?: return@Button
                            scope.launch {
                                isSaving = true
                                statusMsg = "Menyimpan pengaturan SSL..."
                                try {
                                    val sslPayload = mapOf("value" to sslMode)
                                    val resSsl = ApiClient.api.updateSslSetting(zone.id, sslPayload)
                                    val httpsPayload = mapOf("value" to if (alwaysHttps) "on" else "off")
                                    ApiClient.api.updateAlwaysUseHttps(zone.id, httpsPayload)

                                    if (resSsl.isSuccessful && resSsl.body()?.success == true) {
                                        statusMsg = "✅ Pengaturan SSL berhasil disimpan ke Cloudflare!"
                                    } else {
                                        statusMsg = "Gagal: HTTP ${resSsl.code()}"
                                    }
                                } catch (e: Exception) {
                                    statusMsg = "Error: ${e.message}"
                                } finally {
                                    isSaving = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSaving && selectedZone != null
                    ) {
                        Text(if (isSaving) "Menyimpan..." else "💾 Terapkan Pengaturan SSL")
                    }
                }
            }
        }

        if (statusMsg.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Text(text = statusMsg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
                }
            }
        }
    }
}
