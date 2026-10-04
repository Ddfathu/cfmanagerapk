package com.cf.dfathu.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cf.dfathu.data.AppConfig
import com.cf.dfathu.data.api.ApiClient
import com.cf.dfathu.data.api.CfAccountHelper
import com.cf.dfathu.data.local.AccountStorage
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

data class WorkerVarItem(
    val name: String,
    val type: String, // "plain_text" atau "secret_text"
    val text: String = ""
)

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

    var varList by remember { mutableStateOf<List<WorkerVarItem>>(emptyList()) }
    
    // State Form Tambah Utama (Atas)
    var varType by remember { mutableStateOf("plain_text") }
    var varNameInput by remember { mutableStateOf("") }
    var varValueInput by remember { mutableStateOf("") }

    // State Pop-up Dialog Edit (BARU)
    var showEditDialog by remember { mutableStateOf(false) }
    var editVarTarget by remember { mutableStateOf<WorkerVarItem?>(null) }
    var editVarType by remember { mutableStateOf("plain_text") }
    var editVarValueInput by remember { mutableStateOf("") }

    // State Dialog Hapus
    var showDeleteDialog by remember { mutableStateOf(false) }
    var varToDelete by remember { mutableStateOf<WorkerVarItem?>(null) }

    var statusMsg by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val gson = remember { Gson() }

    fun loadWorkerVars(workerName: String) {
        if (workerName.isBlank()) return
        scope.launch {
            isLoading = true
            try {
                val accId = CfAccountHelper.ensureAccountId()
                val res = ApiClient.api.getWorkerBindings(accId, workerName)
                if (res.isSuccessful && res.body()?.success == true) {
                    val raw = res.body()?.result ?: emptyList()
                    val list = raw.mapNotNull { obj ->
                        val type = obj.get("type")?.asString ?: return@mapNotNull null
                        if (type == "plain_text" || type == "secret_text") {
                            val name = obj.get("name")?.asString ?: ""
                            val text = obj.get("text")?.asString ?: ""
                            WorkerVarItem(name, type, text)
                        } else null
                    }
                    varList = list
                } else {
                    varList = emptyList()
                }
            } catch (e: Exception) {
                statusMsg = "Error: " + e.message
            } finally {
                isLoading = false
            }
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
                        loadWorkerVars(list[0])
                    }
                }
            } catch (e: Exception) {
                statusMsg = "Error: " + e.message
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("🔑 Worker Variables & Secrets", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Plain Text & Secret terenkripsi (Persis SC Web)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                IconButton(onClick = { if (selectedWorker.isNotBlank()) loadWorkerVars(selectedWorker) }, enabled = !isLoading) {
                    Text(if (isLoading) "⏳" else "🔄")
                }
            }

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
                                    varNameInput = ""
                                    varValueInput = ""
                                    loadWorkerVars(w)
                                }
                            )
                        }
                    }
                }
            }
        }

        // --- FORM TAMBAH VARIABLE / SECRET ---
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "➕ Tambah Secret / Variable",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = (varType == "plain_text"),
                            onClick = { varType = "plain_text" }
                        )
                        Text("Plain Text (📝)", style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.width(16.dp))
                        RadioButton(
                            selected = (varType == "secret_text"),
                            onClick = { varType = "secret_text" }
                        )
                        Text("Secret (🔒 Enkripsi)", style = MaterialTheme.typography.bodySmall)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = varNameInput,
                        onValueChange = { varNameInput = it.trim() },
                        label = { Text("Nama Variable (env.NAMA_INI)") },
                        placeholder = { Text("Contoh: API_KEY, UUID, PROXY_IP") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = varValueInput,
                        onValueChange = { varValueInput = it },
                        label = { Text(if (varType == "secret_text") "Nilai Secret (Terenkripsi)" else "Nilai Variable (Plain Text)") },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            val w = selectedWorker
                            val name = varNameInput.trim()
                            val value = varValueInput

                            if (w.isBlank() || name.isBlank() || value.isBlank()) {
                                statusMsg = "Pilih worker dan isi nama serta nilainya!"
                                return@Button
                            }

                            scope.launch {
                                isSaving = true
                                statusMsg = "Menyimpan variable ke Cloudflare..."
                                try {
                                    val accId = CfAccountHelper.ensureAccountId()

                                    if (varType == "secret_text") {
                                        val payload = mapOf(
                                            "name" to name,
                                            "text" to value,
                                            "type" to "secret_text"
                                        )
                                        val res = ApiClient.api.putWorkerSecretStringMap(accId, w, payload)
                                        if (res.isSuccessful && res.body()?.success == true) {
                                            statusMsg = "✅ Secret env." + name + " berhasil disimpan di " + w + "!"
                                            varNameInput = ""
                                            varValueInput = ""
                                            loadWorkerVars(w)
                                        } else {
                                            val err = res.body()?.errors?.firstOrNull()?.message ?: ("HTTP " + res.code())
                                            statusMsg = "Gagal: " + err
                                        }
                                    } else {
                                        val getRes = ApiClient.api.getWorkerBindings(accId, w)
                                        val existingBindings = if (getRes.isSuccessful && getRes.body()?.success == true) {
                                            getRes.body()?.result?.toMutableList() ?: mutableListOf()
                                        } else mutableListOf()

                                        val filteredList = existingBindings.filter {
                                            val bName = it.get("name")?.asString
                                            val bType = it.get("type")?.asString
                                            !(bName == name && bType == "plain_text")
                                        }.toMutableList()

                                        val newBinding = JsonObject().apply {
                                            addProperty("type", "plain_text")
                                            addProperty("name", name)
                                            addProperty("text", value)
                                        }
                                        filteredList.add(newBinding)

                                        val settingsMap = mapOf("bindings" to filteredList)
                                        val jsonStr = gson.toJson(settingsMap)
                                        val reqBody = jsonStr.toRequestBody("application/json".toMediaTypeOrNull())
                                        val part = MultipartBody.Part.createFormData("settings", "settings.json", reqBody)

                                        val patchRes = ApiClient.api.patchWorkerSettingsMultipart(accId, w, part)
                                        if (patchRes.isSuccessful && patchRes.body()?.success == true) {
                                            statusMsg = "✅ Variable env." + name + " berhasil disimpan di " + w + "!"
                                            varNameInput = ""
                                            varValueInput = ""
                                            loadWorkerVars(w)
                                        } else {
                                            val err = patchRes.body()?.errors?.firstOrNull()?.message ?: ("HTTP " + patchRes.code())
                                            statusMsg = "Gagal: " + err
                                        }
                                    }
                                } catch (e: Exception) {
                                    statusMsg = "Error: " + e.message
                                } finally {
                                    isSaving = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSaving && selectedWorker.isNotBlank() && varNameInput.isNotBlank() && varValueInput.isNotBlank()
                    ) {
                        Text(if (isSaving) "Menyimpan..." else "💾 Simpan Variable")
                    }
                }
            }
        }

        item {
            Text("Daftar Variables & Secrets Terpasang (" + varList.size + "):", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        if (varList.isEmpty() && !isLoading) {
            item {
                Text("Belum ada Environment Variable atau Secret terpasang pada worker ini.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }

        items(varList) { v ->
            val isSecret = (v.type == "secret_text")
            val badgeBg = if (isSecret) Color(0xFFFEE2E2) else Color(0xFFDCFCE7)
            val badgeTextColor = if (isSecret) Color(0xFF991B1B) else Color(0xFF166534)

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = badgeBg
                            ) {
                                Text(
                                    text = if (isSecret) "SECRET (🔒)" else "PLAIN TEXT (📝)",
                                    color = badgeTextColor,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "env." + v.name,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.titleSmall
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isSecret) "•••••••••••• (Encrypted Secret)" else v.text,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row {
                        // TOMBOL PENSIL (LANGSUNG BUKA POP-UP DIALOG EDIT)
                        IconButton(
                            onClick = {
                                editVarTarget = v
                                editVarType = v.type
                                editVarValueInput = if (isSecret) "" else v.text
                                showEditDialog = true
                            }
                        ) {
                            Text("✏️")
                        }

                        IconButton(
                            onClick = {
                                varToDelete = v
                                showDeleteDialog = true
                            }
                        ) {
                            Text("🗑")
                        }
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

    // --- POP-UP MODAL EDIT DIALOG (BARU) ---
    if (showEditDialog && editVarTarget != null) {
        val target = editVarTarget!!
        AlertDialog(
            onDismissRequest = { if (!isSaving) showEditDialog = false },
            title = {
                Text("✏️ Edit Variable: env." + target.name, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Tipe: " + if (target.type == "secret_text") "Secret (🔒)" else "Plain Text (📝)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    
                    OutlinedTextField(
                        value = editVarValueInput,
                        onValueChange = { editVarValueInput = it },
                        label = { Text("Nilai Baru") },
                        placeholder = { Text(if (target.type == "secret_text") "Masukkan nilai secret baru..." else "Nilai variable...") },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val w = selectedWorker
                        val name = target.name
                        val value = editVarValueInput

                        if (w.isBlank() || value.isBlank()) return@Button

                        scope.launch {
                            isSaving = true
                            statusMsg = "Memperbarui env." + name + "..."
                            try {
                                val accId = CfAccountHelper.ensureAccountId()

                                if (target.type == "secret_text") {
                                    val payload = mapOf(
                                        "name" to name,
                                        "text" to value,
                                        "type" to "secret_text"
                                    )
                                    val res = ApiClient.api.putWorkerSecretStringMap(accId, w, payload)
                                    if (res.isSuccessful && res.body()?.success == true) {
                                        statusMsg = "✅ Secret env." + name + " berhasil diperbarui!"
                                        showEditDialog = false
                                        loadWorkerVars(w)
                                    } else {
                                        val err = res.body()?.errors?.firstOrNull()?.message ?: ("HTTP " + res.code())
                                        statusMsg = "Gagal edit: " + err
                                    }
                                } else {
                                    val getRes = ApiClient.api.getWorkerBindings(accId, w)
                                    val existingBindings = if (getRes.isSuccessful && getRes.body()?.success == true) {
                                        getRes.body()?.result?.toMutableList() ?: mutableListOf()
                                    } else mutableListOf()

                                    val filteredList = existingBindings.filter {
                                        val bName = it.get("name")?.asString
                                        val bType = it.get("type")?.asString
                                        !(bName == name && bType == "plain_text")
                                    }.toMutableList()

                                    val newBinding = JsonObject().apply {
                                        addProperty("type", "plain_text")
                                        addProperty("name", name)
                                        addProperty("text", value)
                                    }
                                    filteredList.add(newBinding)

                                    val settingsMap = mapOf("bindings" to filteredList)
                                    val jsonStr = gson.toJson(settingsMap)
                                    val reqBody = jsonStr.toRequestBody("application/json".toMediaTypeOrNull())
                                    val part = MultipartBody.Part.createFormData("settings", "settings.json", reqBody)

                                    val patchRes = ApiClient.api.patchWorkerSettingsMultipart(accId, w, part)
                                    if (patchRes.isSuccessful && patchRes.body()?.success == true) {
                                        statusMsg = "✅ Variable env." + name + " berhasil diperbarui!"
                                        showEditDialog = false
                                        loadWorkerVars(w)
                                    } else {
                                        val err = patchRes.body()?.errors?.firstOrNull()?.message ?: ("HTTP " + patchRes.code())
                                        statusMsg = "Gagal edit: " + err
                                    }
                                }
                            } catch (e: Exception) {
                                statusMsg = "Error: " + e.message
                            } finally {
                                isSaving = false
                            }
                        }
                    },
                    enabled = !isSaving && editVarValueInput.isNotBlank()
                ) {
                    Text(if (isSaving) "Memproses..." else "💾 Simpan")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showEditDialog = false },
                    enabled = !isSaving
                ) {
                    Text("Batal")
                }
            }
        )
    }

    // --- DIALOG KONFIRMASI HAPUS ---
    if (showDeleteDialog && varToDelete != null) {
        val target = varToDelete!!
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Hapus Variable?") },
            text = { Text("Yakin ingin menghapus env." + target.name + " dari worker '" + selectedWorker + "'?") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    scope.launch {
                        statusMsg = "Menghapus env." + target.name + "..."
                        try {
                            val accId = CfAccountHelper.ensureAccountId()
                            if (target.type == "secret_text") {
                                val res = ApiClient.api.deleteWorkerSecret(accId, selectedWorker, target.name)
                                if (res.isSuccessful && res.body()?.success == true) {
                                    statusMsg = "🗑 Secret env." + target.name + " berhasil dihapus!"
                                    loadWorkerVars(selectedWorker)
                                } else {
                                    statusMsg = "Gagal hapus: HTTP " + res.code()
                                }
                            } else {
                                val getRes = ApiClient.api.getWorkerBindings(accId, selectedWorker)
                                if (getRes.isSuccessful && getRes.body()?.success == true) {
                                    val existing = getRes.body()?.result?.toMutableList() ?: mutableListOf()
                                    val filtered = existing.filter { !(it.get("name")?.asString == target.name && it.get("type")?.asString == "plain_text") }

                                    val settingsMap = mapOf("bindings" to filtered)
                                    val jsonStr = gson.toJson(settingsMap)
                                    val reqBody = jsonStr.toRequestBody("application/json".toMediaTypeOrNull())
                                    val part = MultipartBody.Part.createFormData("settings", "settings.json", reqBody)

                                    val patchRes = ApiClient.api.patchWorkerSettingsMultipart(accId, selectedWorker, part)
                                    if (patchRes.isSuccessful && patchRes.body()?.success == true) {
                                        statusMsg = "🗑 Variable env." + target.name + " berhasil dihapus!"
                                        loadWorkerVars(selectedWorker)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            statusMsg = "Error: " + e.message
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
