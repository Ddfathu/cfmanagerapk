package com.cf.manager.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cf.manager.data.AppConfig
import com.cf.manager.data.api.ApiClient
import com.cf.manager.data.local.AccountStorage
import com.cf.manager.data.model.ZoneItem
import com.google.gson.JsonObject
import kotlinx.coroutines.launch

data class ZoneSslStatusItem(
    val zone: ZoneItem,
    var caProvider: String = "Google CA",
    var status: String = "Aktif"
)

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

    var zoneList by remember { mutableStateOf<List<ZoneSslStatusItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("") }

    // Manual Form States
    var manualZone by remember { mutableStateOf<ZoneItem?>(null) }
    var zoneDropdownExpanded by remember { mutableStateOf(false) }
    var manualSubdomain by remember { mutableStateOf("") }
    var manualCaProvider by remember { mutableStateOf("Let's Encrypt") }
    var caDropdownExpanded by remember { mutableStateOf(false) }
    var isFiringManual by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    fun triggerSslReorder(zoneId: String, zoneName: String, caCode: String, caLabel: String) {
        scope.launch {
            statusMsg = "Menembak sertifikat $caLabel untuk $zoneName..."
            try {
                // 1. Update Certificate Authority di Universal SSL Settings
                val payload = mapOf("certificate_authority" to caCode)
                val res = ApiClient.api.updateUniversalSslSettings(zoneId, payload)

                // 2. Trigger validation / verification
                try {
                    ApiClient.api.reorderSslVerification(zoneId, emptyMap())
                } catch (_: Exception) {}

                if (res.isSuccessful && res.body()?.success == true) {
                    statusMsg = "🎉 Berhasil tembak SSL $caLabel untuk domain $zoneName!"
                    // Perbarui status tampilan
                    zoneList = zoneList.map {
                        if (it.zone.id == zoneId) it.copy(caProvider = caLabel, status = "Re-ordered / Aktif") else it
                    }
                } else {
                    val err = res.body()?.errors?.firstOrNull()?.message ?: "HTTP ${res.code()}"
                    statusMsg = "Gagal tembak SSL: $err"
                }
            } catch (e: Exception) {
                statusMsg = "Error: ${e.message}"
            }
        }
    }

    fun loadData() {
        if (email.isBlank() || apiKey.isBlank()) {
            statusMsg = "⚠️ Isi Email & API Key di tab Akun terlebih dahulu!"
            return
        }
        scope.launch {
            isLoading = true
            try {
                val res = ApiClient.api.listZones()
                if (res.isSuccessful && res.body()?.success == true) {
                    val rawZones = res.body()?.result ?: emptyList()
                    val items = rawZones.map { z ->
                        ZoneSslStatusItem(zone = z, caProvider = "Google CA", status = "Aktif")
                    }
                    zoneList = items
                    if (manualZone == null && items.isNotEmpty()) {
                        manualZone = items[0].zone
                    }

                    // Ambil detail CA Universal tiap zone
                    items.forEach { item ->
                        try {
                            val caRes = ApiClient.api.getUniversalSslSettings(item.zone.id)
                            if (caRes.isSuccessful && caRes.body()?.success == true) {
                                val ca = caRes.body()?.result?.get("certificate_authority")?.asString ?: "google"
                                val displayCa = if (ca.contains("lets_encrypt", ignoreCase = true)) "Let's Encrypt" else "Google CA"
                                item.caProvider = displayCa
                            }
                        } catch (_: Exception) {}
                    }
                    zoneList = ArrayList(items)
                } else {
                    val err = res.body()?.errors?.firstOrNull()?.message ?: "HTTP ${res.code()}"
                    statusMsg = "Gagal memuat domain: $err"
                }
            } catch (e: Exception) {
                statusMsg = "Error: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(email, apiKey) {
        if (email.isNotEmpty() && apiKey.isNotEmpty()) loadData()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // --- 1. HEADER & TOMBOL SCAN DOMAIN ---
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("🔐 Tembak Sertifikat SSL", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFFD97706))
                    Text("(Universal CA)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color(0xFFD97706))
                }
                Button(
                    onClick = { loadData() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(if (isLoading) "Scanning..." else "Scan Domain")
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Klik tombol oranye atau biru pada target domain untuk melakukan re-order sertifikat SSL dari CA pilihanmu.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }

        // STATUS BOX
        if (statusMsg.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Text(
                        text = statusMsg,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        }

        // --- 2. DAFTAR KARTU DOMAIN & TOMBOL TEMBAK CA ---
        items(zoneList) { item ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item.zone.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "Domain Utama",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Status: ", style = MaterialTheme.typography.bodySmall)
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF16A34A))
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(item.status, style = MaterialTheme.typography.bodySmall, color = Color(0xFF16A34A), fontWeight = FontWeight.SemiBold)
                        Text(" | Provider CA: ", style = MaterialTheme.typography.bodySmall)
                        Text(item.caProvider, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Tombol Oranye Let's Encrypt
                        Button(
                            onClick = {
                                triggerSslReorder(item.zone.id, item.zone.name, "lets_encrypt", "Let's Encrypt")
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEA580C)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            Text("Let's Encrypt", fontWeight = FontWeight.Bold)
                        }

                        // Tombol Biru Google CA
                        Button(
                            onClick = {
                                triggerSslReorder(item.zone.id, item.zone.name, "google", "Google CA")
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            Text("Google CA", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // --- 3. FORMULIR TEMBAK MANUAL ---
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("✏️ Tembak Manual:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color(0xFF2563EB))
                    Spacer(modifier = Modifier.height(10.dp))

                    // Dropdown Pilih Domain (Zone)
                    Text("Pilih Domain Utama (Zone):", style = MaterialTheme.typography.labelSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    ExposedDropdownMenuBox(
                        expanded = zoneDropdownExpanded,
                        onExpandedChange = { zoneDropdownExpanded = !zoneDropdownExpanded }
                    ) {
                        OutlinedTextField(
                            value = manualZone?.name ?: "-- Pilih Domain Utama Pemilik (Zone) --",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = zoneDropdownExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = zoneDropdownExpanded,
                            onDismissRequest = { zoneDropdownExpanded = false }
                        ) {
                            zoneList.forEach { itm ->
                                DropdownMenuItem(
                                    text = { Text(itm.zone.name) },
                                    onClick = {
                                        manualZone = itm.zone
                                        zoneDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Input Subdomain Lengkap (Opsional)
                    Text("Nama Subdomain Lengkap (Opsional):", style = MaterialTheme.typography.labelSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = manualSubdomain,
                        onValueChange = { manualSubdomain = it.trim().lowercase() },
                        placeholder = { Text("contoh: api.domainku.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Dropdown Pilih CA Provider
                    Text("Pilih CA Provider:", style = MaterialTheme.typography.labelSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    ExposedDropdownMenuBox(
                        expanded = caDropdownExpanded,
                        onExpandedChange = { caDropdownExpanded = !caDropdownExpanded }
                    ) {
                        OutlinedTextField(
                            value = manualCaProvider,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = caDropdownExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = caDropdownExpanded,
                            onDismissRequest = { caDropdownExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Let's Encrypt") },
                                onClick = {
                                    manualCaProvider = "Let's Encrypt"
                                    caDropdownExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Google CA") },
                                onClick = {
                                    manualCaProvider = "Google CA"
                                    caDropdownExpanded = false
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = {
                            val target = manualZone ?: return@Button
                            val caCode = if (manualCaProvider == "Google CA") "google" else "lets_encrypt"
                            val targetName = if (manualSubdomain.isNotBlank()) manualSubdomain else target.name
                            triggerSslReorder(target.id, targetName, caCode, manualCaProvider)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEA580C)),
                        shape = RoundedCornerShape(8.dp),
                        enabled = !isFiringManual && manualZone != null
                    ) {
                        Text("🚀 Tembak Sertifikat Manual", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
