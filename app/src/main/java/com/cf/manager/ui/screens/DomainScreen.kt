package com.cf.manager.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cf.manager.data.AppConfig
import com.cf.manager.data.api.ApiClient
import com.cf.manager.data.api.CfAccountHelper
import com.cf.manager.data.local.AccountStorage
import com.cf.manager.data.model.ZoneItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DomainScreen() {
    val context = LocalContext.current
    val storage = remember { AccountStorage(context) }
    val accounts = remember { storage.getAccounts() }
    val activeIdx = remember { storage.getActiveIndex() }
    val currAcc = accounts.getOrNull(activeIdx)
    val email = AppConfig.activeEmail.ifBlank { currAcc?.email ?: "" }
    val apiKey = AppConfig.activeApiKey.ifBlank { currAcc?.apiKey ?: "" }

    var zones by remember { mutableStateOf<List<ZoneItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("") }

    var newDomainInput by remember { mutableStateOf("") }
    var isAddingDomain by remember { mutableStateOf(false) }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var zoneToDelete by remember { mutableStateOf<ZoneItem?>(null) }

    val scope = rememberCoroutineScope()

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
                    zones = res.body()?.result ?: emptyList()
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
        if (email.isNotEmpty() && apiKey.isNotEmpty()) loadZones()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // HEADER
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("🌐 Kelola Domain (Zones)", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Daftar domain aktif & status nameserver Cloudflare", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                IconButton(onClick = { loadZones() }, enabled = !isLoading) {
                    Text(if (isLoading) "⏳" else "🔄")
                }
            }
        }

        // FORM TAMBAH DOMAIN BARU
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("➕ Tambah Domain Baru ke Cloudflare", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newDomainInput,
                            onValueChange = { newDomainInput = it.lowercase().trim() },
                            label = { Text("Nama Domain (cth: domainku.com)") },
                            placeholder = { Text("tanpa http/www") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                val target = newDomainInput.trim()
                                if (target.isBlank()) return@Button
                                scope.launch {
                                    isAddingDomain = true
                                    statusMsg = "Mendaftarkan domain '$target' ke Cloudflare..."
                                    try {
                                        val accId = CfAccountHelper.ensureAccountId()
                                        val payload = mapOf(
                                            "name" to target,
                                            "account" to mapOf("id" to accId),
                                            "jump_start" to true
                                        )
                                        val res = ApiClient.api.createZone(payload)
                                        if (res.isSuccessful && res.body()?.success == true) {
                                            statusMsg = "🎉 Domain '$target' berhasil didaftarkan ke Cloudflare!"
                                            newDomainInput = ""
                                            loadZones()
                                        } else {
                                            val err = res.body()?.errors?.firstOrNull()?.message ?: "HTTP ${res.code()}"
                                            statusMsg = "Gagal: $err"
                                        }
                                    } catch (e: Exception) {
                                        statusMsg = "Error: ${e.message}"
                                    } finally {
                                        isAddingDomain = false
                                    }
                                }
                            },
                            enabled = !isAddingDomain && newDomainInput.isNotBlank()
                        ) {
                            Text(if (isAddingDomain) "..." else "Tambah")
                        }
                    }
                }
            }
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

        // DAFTAR DOMAIN
        item {
            Text("Domain Terdaftar (${zones.size}):", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        if (zones.isEmpty() && !isLoading) {
            item {
                Text("Belum ada domain terdaftar di akun ini.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }

        items(zones) { z ->
            val isActive = z.status.equals("active", ignoreCase = true)
            val statusColor = if (isActive) Color(0xFF16A34A) else Color(0xFFD97706)

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🌐 ${z.name}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = statusColor.copy(alpha = 0.15f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(statusColor)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = if (isActive) "Aktif" else z.status,
                                    color = statusColor,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Zone ID: ${z.id}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.outline
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("zone_id", z.id))
                            statusMsg = "📋 Zone ID disalin ke clipboard!"
                        }) {
                            Text("Salin ID")
                        }

                        Button(
                            onClick = {
                                zoneToDelete = z
                                showDeleteDialog = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text("🗑 Hapus", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog && zoneToDelete != null) {
        val target = zoneToDelete!!
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Hapus Domain?") },
            text = { Text("Yakin ingin menghapus domain '${target.name}' dari Cloudflare? Semua DNS record akan terhapus.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    scope.launch {
                        statusMsg = "Menghapus domain '${target.name}'..."
                        try {
                            val res = ApiClient.api.deleteZone(target.id)
                            if (res.isSuccessful && res.body()?.success == true) {
                                statusMsg = "🗑 Domain '${target.name}' berhasil dihapus!"
                                loadZones()
                            } else {
                                val err = res.body()?.errors?.firstOrNull()?.message ?: "HTTP ${res.code()}"
                                statusMsg = "Gagal: $err"
                            }
                        } catch (e: Exception) {
                            statusMsg = "Error: ${e.message}"
                        }
                    }
                }) {
                    Text("Ya, Hapus!", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }
}
