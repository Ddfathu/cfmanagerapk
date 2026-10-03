package com.cf.manager.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.cf.manager.data.api.CfAccountHelper
import com.cf.manager.data.local.AccountStorage
import com.cf.manager.data.model.ZoneItem
import kotlinx.coroutines.launch

data class EmailDestItem(val id: String, val email: String, val verified: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailScreen() {
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

    var destinations by remember { mutableStateOf<List<EmailDestItem>>(emptyList()) }
    var newDestEmail by remember { mutableStateOf("") }
    var statusMsg by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    fun loadDestinations() {
        scope.launch {
            try {
                val accId = CfAccountHelper.ensureAccountId()
                val res = ApiClient.api.listEmailDestinations(accId)
                if (res.isSuccessful && res.body()?.success == true) {
                    val raw = res.body()?.result ?: emptyList()
                    val list = raw.mapNotNull {
                        val id = it.get("id")?.asString ?: return@mapNotNull null
                        val emailAddr = it.get("email")?.asString ?: return@mapNotNull null
                        val verified = it.get("verified")?.asString != null || it.get("verified")?.asBoolean == true
                        EmailDestItem(id, emailAddr, verified)
                    }
                    destinations = list
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
            try {
                val res = ApiClient.api.listZones()
                if (res.isSuccessful && res.body()?.success == true) {
                    val list = res.body()?.result ?: emptyList()
                    zones = list
                    if (list.isNotEmpty()) selectedZone = list[0]
                }
                loadDestinations()
            } catch (e: Exception) {
                statusMsg = "Error: ${e.message}"
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
            Text("✉️ Email Routing (Direct)", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Kelola forwarding email kustom & catch-all Cloudflare", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("➕ Tambah Email Tujuan Forwarding", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newDestEmail,
                            onValueChange = { newDestEmail = it.trim() },
                            label = { Text("Email Tujuan (cth: inbox@gmail.com)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                if (newDestEmail.isBlank()) return@Button
                                scope.launch {
                                    isSaving = true
                                    statusMsg = "Mendaftarkan email tujuan..."
                                    try {
                                        val accId = CfAccountHelper.ensureAccountId()
                                        val payload = mapOf("email" to newDestEmail)
                                        val res = ApiClient.api.createEmailDestination(accId, payload)
                                        if (res.isSuccessful && res.body()?.success == true) {
                                            statusMsg = "✅ Verifikasi dikirim ke $newDestEmail! Cek inbox."
                                            newDestEmail = ""
                                            loadDestinations()
                                        } else {
                                            statusMsg = "Gagal: HTTP ${res.code()}"
                                        }
                                    } catch (e: Exception) {
                                        statusMsg = "Error: ${e.message}"
                                    } finally {
                                        isSaving = false
                                    }
                                }
                            },
                            enabled = !isSaving && newDestEmail.isNotBlank()
                        ) {
                            Text(if (isSaving) "..." else "Tambah")
                        }
                    }
                }
            }
        }

        item {
            Text("Daftar Email Tujuan (${destinations.size}):", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        items(destinations) { d ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(d.email, fontWeight = FontWeight.Bold)
                        Text(
                            text = if (d.verified) "✅ Terverifikasi" else "⏳ Menunggu Verifikasi",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (d.verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }

                    IconButton(onClick = {
                        scope.launch {
                            statusMsg = "Menghapus tujuan ${d.email}..."
                            try {
                                val accId = CfAccountHelper.ensureAccountId()
                                val res = ApiClient.api.deleteEmailDestination(accId, d.id)
                                if (res.isSuccessful && res.body()?.success == true) {
                                    statusMsg = "🗑 Email ${d.email} dicopot!"
                                    loadDestinations()
                                }
                            } catch (e: Exception) {
                                statusMsg = "Error: ${e.message}"
                            }
                        }
                    }) {
                        Text("🗑")
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
