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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cf.manager.data.AppConfig
import com.cf.manager.data.api.ApiClient
import com.cf.manager.data.api.CfAccountHelper
import com.cf.manager.data.local.AccountStorage
import kotlinx.coroutines.launch

data class SecretItemUi(val name: String, val type: String = "secret_text")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VariablesScreen() {
    val context = LocalContext.current
    val storage = remember { AccountStorage(context) }
    val accounts = remember { storage.getAccounts() }
    val activeIdx = remember { storage.getActiveIndex() }
    val currAcc = accounts.getOrNull(activeIdx)
    val email = AppConfig.activeEmail.ifBlank { currAcc?.email ?: "" }
    val apiKey = AppConfig.activeApiKey.ifBlank { currAcc?.apiKey ?: "" }

    var workerList by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedWorker by remember { mutableStateOf("") }
    var workerExpanded by remember { mutableStateOf(false) }

    var secretList by remember { mutableStateOf<List<SecretItemUi>>(emptyList()) }
    var secretNameInput by remember { mutableStateOf("") }
    var secretValueInput by remember { mutableStateOf("") }

    var statusMsg by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    fun loadSecrets(workerName: String) {
        if (workerName.isBlank()) return
        scope.launch {
            try {
                val accId = CfAccountHelper.ensureAccountId()
                val res = ApiClient.api.listWorkerSecrets(accId, workerName)
                if (res.isSuccessful && res.body()?.success == true) {
                    val raw = res.body()?.result ?: emptyList()
                    val list = raw.mapNotNull {
                        val name = it.get("name")?.asString ?: return@mapNotNull null
                        val type = it.get("type")?.asString ?: "secret_text"
                        SecretItemUi(name, type)
                    }
                    secretList = list
                }
            } catch (_: Exception) {}
        }
    }

    fun loadWorkers() {
        if (email.isBlank() || apiKey.isBlank()) {
            statusMsg = "⚠️ Isi Email & API Key di tab Akun terlebih dahulu!"
            return
        }
        scope.launch {
            isLoading = true
            try {
                val accId = CfAccountHelper.ensureAccountId()
                val res = ApiClient.api.listWorkers(accId)
                if (res.isSuccessful && res.body()?.success == true) {
                    val list = res.body()?.result?.mapNotNull { it.get("id")?.asString } ?: emptyList()
                    workerList = list
                    if (list.isNotEmpty()) {
                        selectedWorker = list[0]
                        loadSecrets(list[0])
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
        if (email.isNotEmpty() && apiKey.isNotEmpty()) loadWorkers()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("🔑 Worker Variables & Secrets", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Kelola env variables terenkripsi native Cloudflare", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            Spacer(modifier = Modifier.height(8.dp))

            if (workerList.isNotEmpty()) {
                ExposedDropdownMenuBox(
                    expanded = workerExpanded,
                    onExpandedChange = { workerExpanded = !workerExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedWorker,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Pilih Worker Target") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = workerExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = workerExpanded,
                        onDismissRequest = { workerExpanded = false }
                    ) {
                        workerList.forEach { w ->
                            DropdownMenuItem(
                                text = { Text(w) },
                                onClick = {
                                    selectedWorker = w
                                    workerExpanded = false
                                    loadSecrets(w)
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
                    Text("➕ Tambah Secret / Variable", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = secretNameInput,
                        onValueChange = { secretNameInput = it.trim() },
                        label = { Text("Nama Variable (cth: UUID, PROXY_IP)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = secretValueInput,
                        onValueChange = { secretValueInput = it.trim() },
                        label = { Text("Nilai Secret") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = {
                            val w = selectedWorker
                            if (w.isBlank() || secretNameInput.isBlank() || secretValueInput.isBlank()) {
                                statusMsg = "Pilih worker dan isi nama serta nilainya!"
                                return@Button
                            }
                            scope.launch {
                                isSaving = true
                                statusMsg = "Menyimpan secret ke Cloudflare..."
                                try {
                                    val accId = CfAccountHelper.ensureAccountId()
                                    val payload = mapOf(
                                        "name" to secretNameInput,
                                        "text" to secretValueInput,
                                        "type" to "secret_text"
                                    )
                                    val res = ApiClient.api.putWorkerSecret(accId, w, payload)
                                    if (res.isSuccessful && res.body()?.success == true) {
                                        statusMsg = "✅ Secret '$secretNameInput' tersimpan!"
                                        secretNameInput = ""
                                        secretValueInput = ""
                                        loadSecrets(w)
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
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSaving && selectedWorker.isNotBlank() && secretNameInput.isNotBlank() && secretValueInput.isNotBlank()
                    ) {
                        Text(if (isSaving) "Menyimpan..." else "💾 Simpan Secret")
                    }
                }
            }
        }

        item {
            Text("Daftar Secret Aktif (${secretList.size}):", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        items(secretList) { s ->
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
                        Text("🔒 ${s.name}", fontWeight = FontWeight.Bold)
                        Text(s.type, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }

                    IconButton(onClick = {
                        scope.launch {
                            statusMsg = "Menghapus secret ${s.name}..."
                            try {
                                val accId = CfAccountHelper.ensureAccountId()
                                val res = ApiClient.api.deleteWorkerSecret(accId, selectedWorker, s.name)
                                if (res.isSuccessful && res.body()?.success == true) {
                                    statusMsg = "🗑 Secret ${s.name} berhasil dihapus!"
                                    loadSecrets(selectedWorker)
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
