package com.cf.manager.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cf.manager.data.AppConfig
import com.cf.manager.data.BindingType
import com.cf.manager.data.DetectedBinding
import com.cf.manager.data.SmartWranglerParser
import com.cf.manager.data.api.ApiClient
import com.cf.manager.data.api.CfAccountHelper
import com.cf.manager.data.local.AccountStorage
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URL

data class WorkerDomainItem(
    val id: String = "",
    val hostname: String = "",
    val service: String = "",
    val environment: String = "production"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkerEditorScreen() {
    val context = LocalContext.current
    val storage = remember { AccountStorage(context) }
    val accounts = remember { storage.getAccounts() }
    val activeIdx = remember { storage.getActiveIndex() }
    val currAcc = accounts.getOrNull(activeIdx)
    val email = AppConfig.activeEmail.ifBlank { currAcc?.email ?: "" }
    val apiKey = AppConfig.activeApiKey.ifBlank { currAcc?.apiKey ?: "" }

    var workers by remember { mutableStateOf<List<String>>(emptyList()) }
    var workerDomainsMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isLoadingWorkers by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("") }

    // Modal Smart Wrangler Global
    var detectedBindings by remember { mutableStateOf<List<DetectedBinding>>(emptyList()) }
    var showWranglerModal by remember { mutableStateOf(false) }

    // State Buat Worker Baru (Lengkap dengan Runtime & Smart Wrangler)
    var showCreateDialog by remember { mutableStateOf(false) }
    var newWorkerName by remember { mutableStateOf("") }
    var newWorkerCode by remember { mutableStateOf("export default {\n  async fetch(request, env) {\n    return new Response(\"Hello from Cloudflare Worker!\");\n  }\n};") }
    var newRawUrl by remember { mutableStateOf("") }
    var newCompatDate by remember { mutableStateOf("2024-01-01") }
    var newEnableNodeCompat by remember { mutableStateOf(true) }
    var isFetchingRawNew by remember { mutableStateOf(false) }
    var isDeployingNew by remember { mutableStateOf(false) }

    // State Edit Script Worker
    var showEditDialog by remember { mutableStateOf(false) }
    var activeWorkerToEdit by remember { mutableStateOf("") }
    var editingScriptCode by remember { mutableStateOf("") }
    var editRawUrl by remember { mutableStateOf("") }
    var editCompatDate by remember { mutableStateOf("2024-01-01") }
    var editEnableNodeCompat by remember { mutableStateOf(true) }
    var isFetchingRawEdit by remember { mutableStateOf(false) }
    var isLoadingCode by remember { mutableStateOf(false) }
    var isSavingEdit by remember { mutableStateOf(false) }

    // State Rute Domain Pop-up
    var showRouteDialog by remember { mutableStateOf(false) }
    var activeWorkerForRoute by remember { mutableStateOf("") }
    var activeWorkerRoutes by remember { mutableStateOf<List<WorkerDomainItem>>(emptyList()) }
    var newRouteDomainInput by remember { mutableStateOf("") }
    var isLoadingRoutes by remember { mutableStateOf(false) }
    var isAddingRoute by remember { mutableStateOf(false) }

    // State Hapus Worker
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var workerToDelete by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()
    val gson = remember { Gson() }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val content = context.contentResolver.openInputStream(uri)?.bufferedReader().use { it?.readText() } ?: ""
                if (content.isNotBlank()) {
                    if (showEditDialog) {
                        editingScriptCode = content
                    } else if (showCreateDialog) {
                        newWorkerCode = content
                    }
                    statusMsg = "📄 File script berhasil dimuat!"
                }
            } catch (e: Exception) {
                statusMsg = "Gagal baca file: ${e.message}"
            }
        }
    }

    fun loadWorkerRoutes(workerName: String) {
        scope.launch {
            isLoadingRoutes = true
            try {
                val accId = CfAccountHelper.ensureAccountId()
                val res = ApiClient.api.listWorkerDomains(accId)
                if (res.isSuccessful && res.body()?.success == true) {
                    val raw = res.body()?.result ?: emptyList()
                    val list = mutableListOf<WorkerDomainItem>()
                    raw.forEach { obj ->
                        val service = obj.get("service")?.asString ?: ""
                        if (service.equals(workerName, ignoreCase = true)) {
                            val id = obj.get("id")?.asString ?: ""
                            val hostname = obj.get("hostname")?.asString ?: ""
                            val env = obj.get("environment")?.asString ?: "production"
                            list.add(WorkerDomainItem(id, hostname, service, env))
                        }
                    }
                    activeWorkerRoutes = list
                }
            } catch (_: Exception) {} finally {
                isLoadingRoutes = false
            }
        }
    }

    fun loadWorkers() {
        if (email.isBlank() || apiKey.isBlank()) {
            statusMsg = "⚠️ Isi Email & API Key di tab Akun terlebih dahulu!"
            return
        }
        scope.launch {
            isLoadingWorkers = true
            try {
                val accId = CfAccountHelper.ensureAccountId()
                val res = ApiClient.api.listWorkers(accId)
                if (res.isSuccessful && res.body()?.success == true) {
                    val rawList = res.body()?.result ?: emptyList()
                    workers = rawList.mapNotNull { it.get("id")?.asString }

                    val resDomains = ApiClient.api.listWorkerDomains(accId)
                    if (resDomains.isSuccessful && resDomains.body()?.success == true) {
                        val dList = resDomains.body()?.result ?: emptyList()
                        val map = mutableMapOf<String, String>()
                        dList.forEach { obj ->
                            val srv = obj.get("service")?.asString
                            val host = obj.get("hostname")?.asString
                            if (!srv.isNullOrBlank() && !host.isNullOrBlank()) {
                                map[srv] = host
                            }
                        }
                        workerDomainsMap = map
                    }
                } else {
                    val err = res.body()?.errors?.firstOrNull()?.message ?: "HTTP ${res.code()} */"
                    statusMsg = "Gagal memuat list worker: $err"
                }
            } catch (e: Exception) {
                statusMsg = "Error: ${e.message}"
            } finally {
                isLoadingWorkers = false
            }
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
                    Text("⚡ Cloudflare Workers", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Smart Wrangler Binding, Runtime Config & Deploy", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                IconButton(onClick = { loadWorkers() }, enabled = !isLoadingWorkers) {
                    Text(if (isLoadingWorkers) "⏳" else "🔄")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = {
                    newWorkerName = ""
                    newRawUrl = ""
                    newCompatDate = "2024-01-01"
                    newEnableNodeCompat = true
                    newWorkerCode = "export default {\n  async fetch(request, env) {\n    return new Response(\"Hello from Cloudflare Worker!\");\n  }\n};"
                    showCreateDialog = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("➕ Buat Worker Baru")
            }
        }

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

        item {
            Text("Daftar Worker Aktif (${workers.size}):", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        if (workers.isEmpty() && !isLoadingWorkers) {
            item {
                Text("Belum ada script worker di akun ini.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }

        items(workers) { wName ->
            val customDomain = workerDomainsMap[wName]

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
                        Column(modifier = Modifier.weight(1f)) {
                            Text("⚡ $wName", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(2.dp))
                            if (!customDomain.isNullOrBlank()) {
                                Text("🌐 $customDomain", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            } else {
                                Text("Belum terhubung custom domain", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                activeWorkerToEdit = wName
                                editRawUrl = ""
                                showEditDialog = true
                                isLoadingCode = true
                                scope.launch {
                                    try {
                                        val accId = CfAccountHelper.ensureAccountId()
                                        val res = ApiClient.api.getWorkerCode(accId, wName)
                                        if (res.isSuccessful) {
                                            editingScriptCode = res.body()?.string() ?: ""
                                        } else {
                                            editingScriptCode = "/* Gagal mengunduh kode: HTTP ${res.code()} */"
                                        }

                                        // Muat juga setelan runtime saat ini
                                        try {
                                            val setRes = ApiClient.api.getWorkerSettings(accId, wName)
                                            if (setRes.isSuccessful && setRes.body()?.success == true) {
                                                val resObj = setRes.body()?.result
                                                val cDate = resObj?.get("compatibility_date")?.asString
                                                if (!cDate.isNullOrBlank()) editCompatDate = cDate
                                                val flags = resObj?.getAsJsonArray("compatibility_flags")?.map { it.asString } ?: emptyList()
                                                editEnableNodeCompat = flags.contains("nodejs_compat")
                                            }
                                        } catch (_: Exception) {}
                                    } catch (e: Exception) {
                                        editingScriptCode = "/* Error: ${e.message} */"
                                    } finally {
                                        isLoadingCode = false
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text("✏️ Edit Script")
                        }

                        OutlinedButton(
                            onClick = {
                                activeWorkerForRoute = wName
                                newRouteDomainInput = ""
                                loadWorkerRoutes(wName)
                                showRouteDialog = true
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text("🌐 Rute")
                        }

                        Button(
                            onClick = {
                                workerToDelete = wName
                                showDeleteConfirm = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text("🗑")
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // 1. MODAL DIALOG EDIT SCRIPT WORKER
    // =========================================================================
    if (showEditDialog && activeWorkerToEdit.isNotBlank()) {
        Dialog(
            onDismissRequest = { if (!isSavingEdit) showEditDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("✏️ Edit Script Worker", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Target: $activeWorkerToEdit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Row {
                            Button(
                                onClick = {
                                    detectedBindings = SmartWranglerParser.parseScriptBindings(editingScriptCode)
                                    showWranglerModal = true
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("🧠 Wrangler")
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            IconButton(onClick = { showEditDialog = false }) { Text("❌") }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // INPUT TARIK URL RAW
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = editRawUrl,
                            onValueChange = { editRawUrl = it.trim() },
                            label = { Text("Tarik dari RAW URL") },
                            placeholder = { Text("https://raw.github.../script.js") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                if (editRawUrl.isBlank()) return@Button
                                scope.launch {
                                    isFetchingRawEdit = true
                                    try {
                                        val code = withContext(Dispatchers.IO) { URL(editRawUrl).readText() }
                                        editingScriptCode = code
                                        statusMsg = "✅ Script RAW dimuat!"
                                    } catch (e: Exception) {
                                        statusMsg = "Gagal tarik RAW: ${e.message}"
                                    } finally {
                                        isFetchingRawEdit = false
                                    }
                                }
                            },
                            enabled = !isFetchingRawEdit && editRawUrl.isNotBlank()
                        ) {
                            Text(if (isFetchingRawEdit) "..." else "Tarik")
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // PENGATURAN RUNTIME (NODE COMPAT + DATE)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = editCompatDate,
                            onValueChange = { editCompatDate = it.trim() },
                            label = { Text("Compat Date") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = editEnableNodeCompat,
                                onCheckedChange = { editEnableNodeCompat = it }
                            )
                            Text("nodejs_compat", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Script JavaScript:", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { filePickerLauncher.launch("*/*") }) {
                            Text("📂 Pilih File")
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        if (isLoadingCode) {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        } else {
                            OutlinedTextField(
                                value = editingScriptCode,
                                onValueChange = { editingScriptCode = it },
                                modifier = Modifier.fillMaxSize(),
                                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showEditDialog = false },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Tutup")
                        }

                        Button(
                            onClick = {
                                scope.launch {
                                    isSavingEdit = true
                                    try {
                                        val accId = CfAccountHelper.ensureAccountId()

                                        // Persiapkan metadata lengkap dengan Runtime & Smart Wrangler Flags
                                        val metadataObj = JsonObject()
                                        metadataObj.addProperty("main_module", "index.js")
                                        metadataObj.addProperty("compatibility_date", editCompatDate)
                                        if (editEnableNodeCompat) {
                                            metadataObj.add("compatibility_flags", gson.toJsonTree(listOf("nodejs_compat")))
                                        }

                                        val metaBody = metadataObj.toString().toRequestBody("application/json".toMediaTypeOrNull())
                                        val scriptBody = editingScriptCode.toRequestBody("application/javascript+module".toMediaTypeOrNull())
                                        val scriptPart = MultipartBody.Part.createFormData("index.js", "index.js", scriptBody)

                                        val res = ApiClient.api.deployWorkerMultipart(accId, activeWorkerToEdit, metaBody, scriptPart)
                                        if (res.isSuccessful && res.body()?.success == true) {
                                            statusMsg = "✅ Worker '$activeWorkerToEdit' berhasil di-deploy!"
                                            showEditDialog = false
                                        } else {
                                            val err = res.body()?.errors?.firstOrNull()?.message ?: "HTTP ${res.code()} */"
                                            statusMsg = "Gagal deploy: $err"
                                        }
                                    } catch (e: Exception) {
                                        statusMsg = "Error: ${e.message}"
                                    } finally {
                                        isSavingEdit = false
                                    }
                                }
                            },
                            enabled = !isSavingEdit && !isLoadingCode && editingScriptCode.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (isSavingEdit) "Menyimpan..." else "💾 Simpan & Deploy")
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // 2. MODAL DIALOG BUAT WORKER BARU (LENGKAP DENGAN RUNTIME & SMART WRANGLER)
    // =========================================================================
    if (showCreateDialog) {
        Dialog(
            onDismissRequest = { if (!isDeployingNew) showCreateDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🚀 Buat Worker Baru", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Row {
                            Button(
                                onClick = {
                                    detectedBindings = SmartWranglerParser.parseScriptBindings(newWorkerCode)
                                    showWranglerModal = true
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("🧠 Wrangler")
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            IconButton(onClick = { showCreateDialog = false }) { Text("❌") }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = newWorkerName,
                        onValueChange = { newWorkerName = it.lowercase().trim() },
                        label = { Text("Nama Worker") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newRawUrl,
                            onValueChange = { newRawUrl = it.trim() },
                            label = { Text("URL Raw Script") },
                            placeholder = { Text("https://raw.github.../worker.js") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                if (newRawUrl.isBlank()) return@Button
                                scope.launch {
                                    isFetchingRawNew = true
                                    try {
                                        val code = withContext(Dispatchers.IO) { URL(newRawUrl).readText() }
                                        newWorkerCode = code
                                        statusMsg = "✅ Script RAW dimuat!"
                                    } catch (e: Exception) {
                                        statusMsg = "Gagal tarik RAW: ${e.message}"
                                    } finally {
                                        isFetchingRawNew = false
                                    }
                                }
                            },
                            enabled = !isFetchingRawNew && newRawUrl.isNotBlank()
                        ) {
                            Text(if (isFetchingRawNew) "..." else "Tarik")
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // PENGATURAN RUNTIME SAAT BUAT WORKER BARU
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newCompatDate,
                            onValueChange = { newCompatDate = it.trim() },
                            label = { Text("Compat Date") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = newEnableNodeCompat,
                                onCheckedChange = { newEnableNodeCompat = it }
                            )
                            Text("nodejs_compat", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Kode Worker:", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { filePickerLauncher.launch("*/*") }) {
                            Text("📂 Muat dari File")
                        }
                    }

                    OutlinedTextField(
                        value = newWorkerCode,
                        onValueChange = { newWorkerCode = it },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showCreateDialog = false },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Batal")
                        }

                        Button(
                            onClick = {
                                val target = newWorkerName.trim().lowercase()
                                if (target.isBlank()) {
                                    statusMsg = "Nama Worker tidak boleh kosong!"
                                    return@Button
                                }
                                scope.launch {
                                    isDeployingNew = true
                                    try {
                                        val accId = CfAccountHelper.ensureAccountId()

                                        val metadataObj = JsonObject()
                                        metadataObj.addProperty("main_module", "index.js")
                                        metadataObj.addProperty("compatibility_date", newCompatDate)
                                        if (newEnableNodeCompat) {
                                            metadataObj.add("compatibility_flags", gson.toJsonTree(listOf("nodejs_compat")))
                                        }

                                        val metaBody = metadataObj.toString().toRequestBody("application/json".toMediaTypeOrNull())
                                        val scriptBody = newWorkerCode.toRequestBody("application/javascript+module".toMediaTypeOrNull())
                                        val scriptPart = MultipartBody.Part.createFormData("index.js", "index.js", scriptBody)

                                        val res = ApiClient.api.deployWorkerMultipart(accId, target, metaBody, scriptPart)
                                        if (res.isSuccessful && res.body()?.success == true) {
                                            statusMsg = "🎉 Worker '$target' berhasil dibuat dengan Runtime $newCompatDate!"
                                            showCreateDialog = false
                                            loadWorkers()
                                        } else {
                                            val err = res.body()?.errors?.firstOrNull()?.message ?: "HTTP ${res.code()} */"
                                            statusMsg = "Gagal deploy: $err"
                                        }
                                    } catch (e: Exception) {
                                        statusMsg = "Error: ${e.message}"
                                    } finally {
                                        isDeployingNew = false
                                    }
                                }
                            },
                            enabled = !isDeployingNew && newWorkerName.isNotBlank() && newWorkerCode.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (isDeployingNew) "Deploying..." else "🚀 Deploy Worker")
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // 3. MODAL SMART WRANGLER RESULT
    // =========================================================================
    if (showWranglerModal) {
        Dialog(onDismissRequest = { showWranglerModal = false }) {
            Surface(
                modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("🧠 Smart Wrangler AST Parser", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Deteksi presisi type binding berdasarkan method runtime", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

                    Spacer(modifier = Modifier.height(12.dp))

                    if (detectedBindings.isEmpty()) {
                        Text("Tidak ditemukan panggilan binding (KV/R2/D1) di dalam script ini.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            detectedBindings.forEach { b ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(b.variableName, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                            Text(b.confidenceReason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                        }

                                        val badgeColor = when (b.type) {
                                            BindingType.KV_NAMESPACE -> Color(0xFF2563EB)
                                            BindingType.R2_BUCKET -> Color(0xFFEA580C)
                                            BindingType.D1_DATABASE -> Color(0xFF059669)
                                            BindingType.PLAIN_TEXT -> Color(0xFF6B7280)
                                        }

                                        Surface(shape = RoundedCornerShape(4.dp), color = badgeColor) {
                                            Text(
                                                text = b.type.name.replace("_", " "),
                                                color = Color.White,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showWranglerModal = false }) { Text("Tutup") }
                    }
                }
            }
        }
    }

    // =========================================================================
    // 4. MODAL RUTE DOMAIN & HAPUS WORKER
    // =========================================================================
    if (showRouteDialog && activeWorkerForRoute.isNotBlank()) {
        Dialog(onDismissRequest = { showRouteDialog = false }) {
            Surface(
                modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("🌐 Custom Domain Rute", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Worker: $activeWorkerForRoute", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { loadWorkerRoutes(activeWorkerForRoute) }, enabled = !isLoadingRoutes) {
                            Text(if (isLoadingRoutes) "⏳" else "🔄")
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newRouteDomainInput,
                            onValueChange = { newRouteDomainInput = it.lowercase().trim() },
                            label = { Text("Domain (cth: api.domainku.com)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                if (newRouteDomainInput.isBlank()) return@Button
                                scope.launch {
                                    isAddingRoute = true
                                    try {
                                        val accId = CfAccountHelper.ensureAccountId()
                                        val payload = mapOf(
                                            "hostname" to newRouteDomainInput,
                                            "service" to activeWorkerForRoute,
                                            "environment" to "production"
                                        )
                                        val res = ApiClient.api.putWorkerDomain(accId, payload)
                                        if (res.isSuccessful && res.body()?.success == true) {
                                            statusMsg = "✅ Custom domain '$newRouteDomainInput' terhubung!"
                                            newRouteDomainInput = ""
                                            loadWorkerRoutes(activeWorkerForRoute)
                                            loadWorkers()
                                        } else {
                                            val err = res.body()?.errors?.firstOrNull()?.message ?: "HTTP ${res.code()} */"
                                            statusMsg = "Gagal: $err"
                                        }
                                    } catch (e: Exception) {
                                        statusMsg = "Error: ${e.message}"
                                    } finally {
                                        isAddingRoute = false
                                    }
                                }
                            },
                            enabled = !isAddingRoute && newRouteDomainInput.isNotBlank()
                        ) {
                            Text(if (isAddingRoute) "..." else "Tambah")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))

                    Text("Domain Rute Terdaftar (${activeWorkerRoutes.size}):", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))

                    if (activeWorkerRoutes.isEmpty()) {
                        Text(
                            text = if (isLoadingRoutes) "Memuat rute domain..." else "Belum ada custom domain yang diarahkan ke worker ini.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            activeWorkerRoutes.forEach { r ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(r.hostname, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                        IconButton(
                                            onClick = {
                                                scope.launch {
                                                    try {
                                                        val accId = CfAccountHelper.ensureAccountId()
                                                        val res = ApiClient.api.deleteWorkerDomain(accId, r.id)
                                                        if (res.isSuccessful && res.body()?.success == true) {
                                                            statusMsg = "🗑 Rute domain ${r.hostname} dicopot!"
                                                            loadWorkerRoutes(activeWorkerForRoute)
                                                            loadWorkers()
                                                        }
                                                    } catch (e: Exception) {
                                                        statusMsg = "Error: ${e.message}"
                                                    }
                                                }
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Text("🗑")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showRouteDialog = false }) { Text("Tutup") }
                    }
                }
            }
        }
    }

    if (showDeleteConfirm && workerToDelete.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Hapus Worker?") },
            text = { Text("Yakin ingin menghapus worker '$workerToDelete' secara permanen?") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    scope.launch {
                        statusMsg = "Menghapus worker '$workerToDelete'..."
                        try {
                            val accId = CfAccountHelper.ensureAccountId()
                            val res = ApiClient.api.deleteWorker(accId, workerToDelete)
                            if (res.isSuccessful && res.body()?.success == true) {
                                statusMsg = "🗑 Worker '$workerToDelete' berhasil dihapus!"
                                loadWorkers()
                            } else {
                                val err = res.body()?.errors?.firstOrNull()?.message ?: "HTTP ${res.code()} */"
                                statusMsg = "Gagal menghapus: $err"
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
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Batal") }
            }
        )
    }
}
