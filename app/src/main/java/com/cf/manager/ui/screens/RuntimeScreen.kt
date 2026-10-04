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
import com.cf.manager.data.api.CfAccountHelper
import com.cf.manager.data.local.AccountStorage
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuntimeScreen() {
    val context = LocalContext.current
    val storage = remember { AccountStorage(context) }
    val accounts = remember { storage.getAccounts() }
    val activeIdx = remember { storage.getActiveIndex() }
    val currAcc = accounts.getOrNull(activeIdx)
    val email = AppConfig.activeEmail.ifBlank { currAcc?.email ?: "" }
    val apiKey = AppConfig.activeApiKey.ifBlank { currAcc?.apiKey ?: "" }

    var workers by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedWorker by remember { mutableStateOf("") }
    var workerDropdownExpanded by remember { mutableStateOf(false) }

    // State Placement
    var placementMode by remember { mutableStateOf("off") } // "off" = Default (Close to end-users)
    var placementExpanded by remember { mutableStateOf(false) }
    var isSavingPlacement by remember { mutableStateOf(false) }

    // State Compatibility
    var compatDate by remember { mutableStateOf("2024-01-01") }
    var compatFlags by remember { mutableStateOf("nodejs_compat, streams_enable_constructors") }
    var isSavingCompat by remember { mutableStateOf(false) }

    var statusMsg by remember { mutableStateOf("") }
    var isLoadingSettings by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val gson = remember { Gson() }

    fun loadWorkerRuntime(workerName: String) {
        if (workerName.isBlank() || email.isBlank() || apiKey.isBlank()) return
        scope.launch {
            isLoadingSettings = true
            try {
                val accId = CfAccountHelper.ensureAccountId()
                val res = ApiClient.api.getWorkerSettings(accId, workerName)
                if (res.isSuccessful && res.body()?.success == true) {
                    val resultElement = res.body()?.result
                    val resultObj = if (resultElement?.isJsonObject == true) resultElement.asJsonObject else null

                    // Baca compatibility date & flags
                    val cDate = resultObj?.get("compatibility_date")?.asString
                    if (!cDate.isNullOrBlank()) compatDate = cDate

                    val flagsArr = resultObj?.getAsJsonArray("compatibility_flags")
                    if (flagsArr != null) {
                        val flags = flagsArr.map { it.asString }
                        compatFlags = flags.joinToString(", ")
                    }

                    // Baca placement
                    val placeObj = resultObj?.getAsJsonObject("placement")
                    val mode = placeObj?.get("mode")?.asString ?: "off"
                    placementMode = mode
                }
            } catch (e: Exception) {
                statusMsg = "Gagal memuat runtime: ${e.message}"
            } finally {
                isLoadingSettings = false
            }
        }
    }

    fun loadWorkers() {
        if (email.isBlank() || apiKey.isBlank()) return
        scope.launch {
            try {
                val accId = CfAccountHelper.ensureAccountId()
                val res = ApiClient.api.listWorkers(accId)
                if (res.isSuccessful && res.body()?.success == true) {
                    val list = res.body()?.result?.mapNotNull { it.get("id")?.asString } ?: emptyList()
                    workers = list
                    if (selectedWorker.isBlank() && list.isNotEmpty()) {
                        selectedWorker = list[0]
                        loadWorkerRuntime(list[0])
                    }
                }
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(email, apiKey) {
        if (email.isNotEmpty() && apiKey.isNotEmpty()) {
            loadWorkers()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("⚙️ Worker Runtime & Placement", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Smart Placement, Compatibility Date & Flags", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                IconButton(onClick = { if (selectedWorker.isNotBlank()) loadWorkerRuntime(selectedWorker) }, enabled = !isLoadingSettings) {
                    Text(if (isLoadingSettings) "⏳" else "🔄")
                }
            }
        }

        // PILIH WORKER TARGET
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Pilih Worker Target Setelan:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(6.dp))

                    ExposedDropdownMenuBox(
                        expanded = workerDropdownExpanded,
                        onExpandedChange = { workerDropdownExpanded = !workerDropdownExpanded }
                    ) {
                        OutlinedTextField(
                            value = selectedWorker.ifBlank { "Pilih Worker..." },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Worker") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = workerDropdownExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = workerDropdownExpanded,
                            onDismissRequest = { workerDropdownExpanded = false }
                        ) {
                            workers.forEach { w ->
                                DropdownMenuItem(
                                    text = { Text(w) },
                                    onClick = {
                                        selectedWorker = w
                                        workerDropdownExpanded = false
                                        loadWorkerRuntime(w)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // 1. PLACEMENT CARD
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Placement", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(10.dp))

                    ExposedDropdownMenuBox(
                        expanded = placementExpanded,
                        onExpandedChange = { placementExpanded = !placementExpanded }
                    ) {
                        val displayMode = if (placementMode == "off") "Default (Close to end-users)" else "Smart Placement (Optimized for backend)"
                        OutlinedTextField(
                            value = displayMode,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Mode Placement") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = placementExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = placementExpanded,
                            onDismissRequest = { placementExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Default (Close to end-users)") },
                                onClick = {
                                    placementMode = "off"
                                    placementExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Smart Placement (Optimized for backend)") },
                                onClick = {
                                    placementMode = "smart"
                                    placementExpanded = false
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = {
                            val w = selectedWorker
                            if (w.isBlank()) return@Button
                            scope.launch {
                                isSavingPlacement = true
                                statusMsg = "Menyimpan pengaturan Placement..."
                                try {
                                    val accId = CfAccountHelper.ensureAccountId()
                                    val payload = mapOf("placement" to mapOf("mode" to placementMode))
                                    val res = ApiClient.api.updateWorkerSettings(accId, w, payload)
                                    if (res.isSuccessful && res.body()?.success == true) {
                                        statusMsg = "💾 Berhasil menyimpan Placement Region!"
                                    } else {
                                        val err = res.body()?.errors?.firstOrNull()?.message ?: "HTTP ${res.code()}"
                                        statusMsg = "Gagal: $err"
                                    }
                                } catch (e: Exception) {
                                    statusMsg = "Error: ${e.message}"
                                } finally {
                                    isSavingPlacement = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF16A34A)),
                        enabled = !isSavingPlacement && selectedWorker.isNotBlank()
                    ) {
                        Text(if (isSavingPlacement) "Menyimpan..." else "💾 Simpan Placement Region")
                    }
                }
            }
        }

        // 2. COMPATIBILITY CARD
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Compatibility", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = compatDate,
                        onValueChange = { compatDate = it.trim() },
                        label = { Text("Compatibility date (YYYY-MM-DD)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = compatFlags,
                        onValueChange = { compatFlags = it },
                        label = { Text("Compatibility flags") },
                        placeholder = { Text("nodejs_compat, streams_enable_constructors") },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Pisahkan dengan koma (contoh: nodejs_compat, streams_enable_constructors).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = {
                            val w = selectedWorker
                            if (w.isBlank()) return@Button
                            scope.launch {
                                isSavingCompat = true
                                statusMsg = "Menyimpan pengaturan Compatibility..."
                                try {
                                    val accId = CfAccountHelper.ensureAccountId()
                                    val parsedFlags = compatFlags.split(",")
                                        .map { it.trim() }
                                        .filter { it.isNotBlank() }

                                    val payload = mapOf(
                                        "compatibility_date" to compatDate,
                                        "compatibility_flags" to parsedFlags
                                    )
                                    val res = ApiClient.api.updateWorkerSettings(accId, w, payload)
                                    if (res.isSuccessful && res.body()?.success == true) {
                                        statusMsg = "💾 Berhasil menyimpan Compatibility!"
                                    } else {
                                        val err = res.body()?.errors?.firstOrNull()?.message ?: "HTTP ${res.code()}"
                                        statusMsg = "Gagal: $err"
                                    }
                                } catch (e: Exception) {
                                    statusMsg = "Error: ${e.message}"
                                } finally {
                                    isSavingCompat = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSavingCompat && selectedWorker.isNotBlank()
                    ) {
                        Text(if (isSavingCompat) "Menyimpan..." else "💾 Simpan Compatibility")
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
    }
}
