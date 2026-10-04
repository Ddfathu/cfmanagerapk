package com.cf.dfathu.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cf.dfathu.data.AppConfig
import com.cf.dfathu.data.BindingType
import com.cf.dfathu.data.DetectedBinding
import com.cf.dfathu.data.SmartWranglerParser
import com.cf.dfathu.data.api.ApiClient
import com.cf.dfathu.data.api.CfAccountHelper
import com.cf.dfathu.data.local.AccountStorage
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URL
import java.util.concurrent.TimeUnit

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
    var accountSubdomain by remember { mutableStateOf("") }
    var isLoadingWorkers by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("") }

    var detectedBindings by remember { mutableStateOf<List<DetectedBinding>>(emptyList()) }
    var showWranglerModal by remember { mutableStateOf(false) }

    var showCreateDialog by remember { mutableStateOf(false) }
    var newWorkerName by remember { mutableStateOf("") }
    var newWorkerCode by remember {
        mutableStateOf(
            """
            export default {
              async fetch(request, env) {
                return new Response("Halo dari Cloudflare Worker!");
              }
            };
            """.trimIndent()
        )
    }
    var newRawUrl by remember { mutableStateOf("") }
    var newCompatDate by remember { mutableStateOf("2024-01-01") }
    var newEnableNodeCompat by remember { mutableStateOf(true) }
    var isFetchingRawNew by remember { mutableStateOf(false) }
    var isDeployingNew by remember { mutableStateOf(false) }

    var showEditDialog by remember { mutableStateOf(false) }
    var activeWorkerToEdit by remember { mutableStateOf("") }
    var editingScriptCode by remember { mutableStateOf("") }
    var editRawUrl by remember { mutableStateOf("") }
    var editCompatDate by remember { mutableStateOf("2024-01-01") }
    var editEnableNodeCompat by remember { mutableStateOf(true) }
    var isFetchingRawEdit by remember { mutableStateOf(false) }
    var isLoadingCode by remember { mutableStateOf(false) }
    var isSavingEdit by remember { mutableStateOf(false) }

    var showRouteDialog by remember { mutableStateOf(false) }
    var activeWorkerForRoute by remember { mutableStateOf("") }
    var activeWorkerRoutes by remember { mutableStateOf<List<WorkerDomainItem>>(emptyList()) }
    var isLoadingRoutes by remember { mutableStateOf(false) }
    var isAddingRoute by remember { mutableStateOf(false) }

    // State untuk Subdomain & Dropdown CF Zones
    var routeSubdomainInput by remember { mutableStateOf("") }
    var cfZonesList by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedZoneDomain by remember { mutableStateOf("") }
    var isZoneDropdownExpanded by remember { mutableStateOf(false) }
    var isLoadingZones by remember { mutableStateOf(false) }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var workerToDelete by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()
    val gson = remember { Gson() }

    // OPTIMASI HTTP CLIENT: Timeout ketat & Reusable
    val httpClient = remember {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    fun openUrl(urlStr: String) {
        try {
            val validUrl = if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) "https://$urlStr" else urlStr
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(validUrl)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val content = context.contentResolver.openInputStream(uri)?.bufferedReader().use { it?.readText() } ?: ""
                if (content.isNotBlank()) {
                    if (showEditDialog) {
                        editingScriptCode = content
                        detectedBindings = SmartWranglerParser.parseScriptBindings(content)
                    } else if (showCreateDialog) {
                        newWorkerCode = content
                        detectedBindings = SmartWranglerParser.parseScriptBindings(content)
                    }
                    statusMsg = "📄 File script berhasil dimuat!"
                }
            } catch (e: Exception) {
                statusMsg = "Gagal baca file: " + e.message
            }
        }
    }

    // Load Zones / Domain utama dari akun Cloudflare
    fun fetchCfZones() {
        if (email.isBlank() || apiKey.isBlank()) return
        scope.launch {
            isLoadingZones = true
            try {
                val accId = CfAccountHelper.ensureAccountId()
                val req = Request.Builder()
                    .url("https://api.cloudflare.com/client/v4/zones?account.id=$accId&status=active&per_page=50")
                    .header("X-Auth-Email", email)
                    .header("X-Auth-Key", apiKey)
                    .get().build()

                val respStr = withContext(Dispatchers.IO) {
                    val resp = httpClient.newCall(req).execute()
                    resp.body?.string() ?: ""
                }
                val json = gson.fromJson(respStr, JsonObject::class.java)
                val arr = json?.getAsJsonArray("result") ?: JsonArray()
                val list = mutableListOf<String>()
                arr.forEach { el ->
                    val name = el.asJsonObject.get("name")?.asString ?: ""
                    if (name.isNotBlank()) list.add(name)
                }
                cfZonesList = list
                if (list.isNotEmpty() && selectedZoneDomain.isBlank()) {
                    selectedZoneDomain = list[0]
                }
            } catch (_: Exception) {
            } finally {
                isLoadingZones = false
            }
        }
    }

    // --- FUN HELPER DEPLOYMENT WORKER ---
    fun deployWorkerScript(
        accId: String,
        workerName: String,
        codeStr: String,
        compatDate: String,
        enableNodeCompat: Boolean,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        scope.launch {
            try {
                val deployResult = withContext(Dispatchers.IO) {
                    val metadataObj = JsonObject().apply {
                        addProperty("main_module", "index.js")
                        addProperty("compatibility_date", compatDate)
                        val flagsArr = com.google.gson.JsonArray()
                        if (enableNodeCompat) flagsArr.add("nodejs_compat")
                        add("compatibility_flags", flagsArr)
                    }

                    val multipartBuilder = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("metadata", "metadata.json", metadataObj.toString().toRequestBody("application/json".toMediaType()))
                        .addFormDataPart("index.js", "index.js", codeStr.toByteArray(Charsets.UTF_8).toRequestBody("application/javascript+module".toMediaType()))

                    val deployReq = Request.Builder()
                        .url("https://api.cloudflare.com/client/v4/accounts/$accId/workers/scripts/$workerName")
                        .header("X-Auth-Email", email)
                        .header("X-Auth-Key", apiKey)
                        .put(multipartBuilder.build())
                        .build()

                    val deployResp = httpClient.newCall(deployReq).execute()
                    val deployRespStr = deployResp.body?.string() ?: ""
                    val deployJson = gson.fromJson(deployRespStr, JsonObject::class.java)

                    if (deployJson?.get("success")?.asBoolean == true) {
                        val enableSubdomainBody = JsonObject().apply { addProperty("enabled", true) }
                        val enableReq = Request.Builder()
                            .url("https://api.cloudflare.com/client/v4/accounts/$accId/workers/scripts/$workerName/subdomain")
                            .header("X-Auth-Email", email)
                            .header("X-Auth-Key", apiKey)
                            .post(enableSubdomainBody.toString().toRequestBody("application/json".toMediaType()))
                            .build()
                        httpClient.newCall(enableReq).execute()
                        Pair(true, "")
                    } else {
                        val errDetail = deployJson?.getAsJsonArray("errors")?.firstOrNull()?.asJsonObject?.get("message")?.asString ?: ("HTTP " + deployResp.code)
                        Pair(false, "Gagal Deploy: $errDetail")
                    }
                }

                if (deployResult.first) {
                    onSuccess()
                } else {
                    onError(deployResult.second)
                }

            } catch (e: Exception) {
                onError("Exception: " + e.message)
            }
        }
    }

    fun loadWorkerRoutes(workerName: String) {
        scope.launch {
            isLoadingRoutes = true
            try {
                val accId = CfAccountHelper.ensureAccountId()
                val res = withContext(Dispatchers.IO) { ApiClient.api.listWorkerDomains(accId) }
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

    // OPTIMASI: LOAD WORKERS PARALLEL (CEPAT & RESPONSIF)
    fun loadWorkers() {
        if (email.isBlank() || apiKey.isBlank()) {
            statusMsg = "⚠️ Isi Email & API Key di tab Akun terlebih dahulu!"
            return
        }
        scope.launch {
            isLoadingWorkers = true
            try {
                val accId = CfAccountHelper.ensureAccountId()

                withContext(Dispatchers.IO) {
                    val subDeferred = async { ApiClient.api.getAccountSubdomain(accId) }
                    val workersDeferred = async { ApiClient.api.listWorkers(accId) }
                    val domainsDeferred = async { ApiClient.api.listWorkerDomains(accId) }

                    try {
                        val subRes = subDeferred.await()
                        if (subRes.isSuccessful && subRes.body()?.success == true) {
                            val subElement = subRes.body()?.result
                            val subObj = if (subElement?.isJsonObject == true) subElement.asJsonObject else null
                            accountSubdomain = subObj?.get("subdomain")?.asString ?: ""
                        }
                    } catch (_: Exception) {}

                    val res = workersDeferred.await()
                    if (res.isSuccessful && res.body()?.success == true) {
                        val rawList = res.body()?.result ?: emptyList()
                        workers = rawList.mapNotNull { it.get("id")?.asString }

                        try {
                            val resDomains = domainsDeferred.await()
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
                        } catch (_: Exception) {}
                    } else {
                        val err = res.body()?.errors?.firstOrNull()?.message ?: ("HTTP " + res.code())
                        statusMsg = "Gagal memuat list worker: " + err
                    }
                }
            } catch (e: Exception) {
                statusMsg = "Error: " + e.message
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
        modifier = Modifier.fillMaxSize().padding(16.dp),
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
                    Text("Smart Wrangler Binding & Runtime Config", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
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
                    newWorkerCode = """
                    export default {
                      async fetch(request, env) {
                        return new Response("Halo dari Cloudflare Worker!");
                      }
                    };
                    """.trimIndent()
                    detectedBindings = SmartWranglerParser.parseScriptBindings(newWorkerCode)
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
                    Text(text = statusMsg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
                }
            }
        }

        item {
            Text("Daftar Worker Aktif (" + workers.size + "):", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        if (workers.isEmpty() && !isLoadingWorkers) {
            item {
                Text("Belum ada script worker di akun ini.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }

        items(workers) { wName ->
            val customDomain = workerDomainsMap[wName]
            val workersDevUrl = if (accountSubdomain.isNotBlank()) "https://" + wName + "." + accountSubdomain + ".workers.dev" else ""

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
                            Text("⚡ " + wName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))

                            if (workersDevUrl.isNotBlank()) {
                                Text(
                                    text = "🔗 " + workersDevUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    textDecoration = TextDecoration.Underline,
                                    modifier = Modifier.clickable { openUrl(workersDevUrl) }
                                )
                            }

                            if (!customDomain.isNullOrBlank()) {
                                val customUrl = "https://" + customDomain
                                Text(
                                    text = "🌐 " + customUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    textDecoration = TextDecoration.Underline,
                                    modifier = Modifier.clickable { openUrl(customUrl) }
                                )
                            }

                            if (workersDevUrl.isBlank() && customDomain.isNullOrBlank()) {
                                Text("Belum ada URL publik aktif", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
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
                                        withContext(Dispatchers.IO) {
                                            val res = ApiClient.api.getWorkerCode(accId, wName)
                                            if (res.isSuccessful) {
                                                var code = res.body()?.string() ?: ""
                                                if (code.contains("name=\"index.js\"")) {
                                                    code = code.replace(Regex("^--[\\s\\S]*?name=\"index.js\"[\\s\\S]*?\\r?\\n\\r?\\n"), "")
                                                               .replace(Regex("\\r?\\n--[a-f0-9]+--\\s*$"), "")
                                                } else if (code.contains("Content-Disposition: form-data")) {
                                                    code = code.replace(Regex("^--[\\s\\S]*?Content-Disposition[\\s\\S]*?\\r?\\n\\r?\\n"), "")
                                                               .replace(Regex("\\r?\\n--[a-f0-9]+--\\s*$"), "")
                                                }
                                                editingScriptCode = code.trim()
                                                detectedBindings = SmartWranglerParser.parseScriptBindings(editingScriptCode)
                                            } else {
                                                editingScriptCode = "/* Gagal mengunduh kode */"
                                            }

                                            try {
                                                val setRes = ApiClient.api.getWorkerSettings(accId, wName)
                                                if (setRes.isSuccessful && setRes.body()?.success == true) {
                                                    val resElement = setRes.body()?.result
                                                    val resObj = if (resElement?.isJsonObject == true) resElement.asJsonObject else null
                                                    val cDate = resObj?.get("compatibility_date")?.asString
                                                    if (!cDate.isNullOrBlank()) editCompatDate = cDate
                                                    val flags = resObj?.getAsJsonArray("compatibility_flags")?.map { it.asString } ?: emptyList()
                                                    editEnableNodeCompat = flags.contains("nodejs_compat")
                                                }
                                            } catch (_: Exception) {}
                                        }
                                    } catch (e: Exception) {
                                        editingScriptCode = "/* Error: " + e.message + " */"
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
                                routeSubdomainInput = ""
                                loadWorkerRoutes(wName)
                                fetchCfZones()
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

    // DIALOG EDIT WORKER
    if (showEditDialog && activeWorkerToEdit.isNotBlank()) {
        Dialog(
            onDismissRequest = { if (!isSavingEdit) showEditDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize().padding(12.dp),
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
                            Text("Target: " + activeWorkerToEdit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = {
                                    detectedBindings = SmartWranglerParser.parseScriptBindings(editingScriptCode)
                                    showWranglerModal = true
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("🧠 Wrangler (" + detectedBindings.size + ")")
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            IconButton(onClick = { showEditDialog = false }) { Text("❌") }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

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
                                        detectedBindings = SmartWranglerParser.parseScriptBindings(code)
                                        statusMsg = "✅ Script RAW dimuat!"
                                    } catch (e: Exception) {
                                        statusMsg = "Gagal tarik RAW: " + e.message
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
                                onValueChange = { 
                                    editingScriptCode = it
                                    detectedBindings = SmartWranglerParser.parseScriptBindings(it)
                                },
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
                                isSavingEdit = true
                                scope.launch {
                                    val accId = CfAccountHelper.ensureAccountId()
                                    deployWorkerScript(
                                        accId = accId,
                                        workerName = activeWorkerToEdit,
                                        codeStr = editingScriptCode,
                                        compatDate = editCompatDate,
                                        enableNodeCompat = editEnableNodeCompat,
                                        onSuccess = {
                                            isSavingEdit = false
                                            statusMsg = "✅ Worker '$activeWorkerToEdit' berhasil di-update & live!"
                                            showEditDialog = false
                                            loadWorkers()
                                        },
                                        onError = { err ->
                                            isSavingEdit = false
                                            statusMsg = "❌ $err"
                                        }
                                    )
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

    // DIALOG BUAT WORKER BARU
    if (showCreateDialog) {
        Dialog(
            onDismissRequest = { if (!isDeployingNew) showCreateDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize().padding(12.dp),
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = {
                                    detectedBindings = SmartWranglerParser.parseScriptBindings(newWorkerCode)
                                    showWranglerModal = true
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("🧠 Wrangler (" + detectedBindings.size + ")")
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
                                        detectedBindings = SmartWranglerParser.parseScriptBindings(code)
                                        statusMsg = "✅ Script RAW dimuat!"
                                    } catch (e: Exception) {
                                        statusMsg = "Gagal tarik RAW: " + e.message
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
                        onValueChange = { 
                            newWorkerCode = it
                            detectedBindings = SmartWranglerParser.parseScriptBindings(it)
                        },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
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
                                isDeployingNew = true
                                scope.launch {
                                    val accId = CfAccountHelper.ensureAccountId()
                                    deployWorkerScript(
                                        accId = accId,
                                        workerName = target,
                                        codeStr = newWorkerCode,
                                        compatDate = newCompatDate,
                                        enableNodeCompat = newEnableNodeCompat,
                                        onSuccess = {
                                            isDeployingNew = false
                                            statusMsg = "🎉 Worker '$target' berhasil dibuat & aktif!"
                                            showCreateDialog = false
                                            loadWorkers()
                                        },
                                        onError = { err ->
                                            isDeployingNew = false
                                            statusMsg = "❌ $err"
                                        }
                                    )
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
                    Text("Deteksi binding otomatis berdasarkan runtime script", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

                    Spacer(modifier = Modifier.height(12.dp))

                    if (detectedBindings.isEmpty()) {
                        Text("Tidak ditemukan binding (KV/R2/D1) di script ini.", style = MaterialTheme.typography.bodyMedium)
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

    // --- DIALOG MODAL: CUSTOM DOMAIN RUTE WORKER ---
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
                            Text("Worker: " + activeWorkerForRoute, style = MaterialTheme.typography.bodySmall, color = Color(0xFFEA580C))
                        }
                        IconButton(
                            onClick = {
                                loadWorkerRoutes(activeWorkerForRoute)
                                fetchCfZones()
                            },
                            enabled = !isLoadingRoutes && !isLoadingZones
                        ) {
                            Text(if (isLoadingRoutes || isLoadingZones) "⏳" else "🔄")
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // 1. Kolom Subdomain
                    OutlinedTextField(
                        value = routeSubdomainInput,
                        onValueChange = { routeSubdomainInput = it.lowercase().trim() },
                        label = { Text("Subdomain") },
                        placeholder = { Text("api / v2ray / @ (kosongkan jika apex)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // 2. Dropdown Pilih Domain Utama (CF Zones)
                    ExposedDropdownMenuBox(
                        expanded = isZoneDropdownExpanded,
                        onExpandedChange = { isZoneDropdownExpanded = !isZoneDropdownExpanded }
                    ) {
                        OutlinedTextField(
                            value = if (selectedZoneDomain.isBlank()) "Pilih Domain..." else selectedZoneDomain,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Pilih Domain Utama") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isZoneDropdownExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = isZoneDropdownExpanded,
                            onDismissRequest = { isZoneDropdownExpanded = false }
                        ) {
                            if (cfZonesList.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("Gagal/Belum ada domain di CF") },
                                    onClick = { isZoneDropdownExpanded = false }
                                )
                            } else {
                                cfZonesList.forEach { z ->
                                    DropdownMenuItem(
                                        text = { Text("🌐 $z", fontWeight = FontWeight.Bold) },
                                        onClick = {
                                            selectedZoneDomain = z
                                            isZoneDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Preview Target Domain
                    val fullDomainTarget = remember(routeSubdomainInput, selectedZoneDomain) {
                        val sub = routeSubdomainInput.trim().removeSuffix(".")
                        if (sub.isBlank() || sub == "@") selectedZoneDomain else "$sub.$selectedZoneDomain"
                    }

                    if (selectedZoneDomain.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Target: https://$fullDomainTarget",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF16A34A),
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = {
                            if (selectedZoneDomain.isBlank()) return@Button
                            scope.launch {
                                isAddingRoute = true
                                try {
                                    val accId = CfAccountHelper.ensureAccountId()
                                    val payload = mapOf(
                                        "hostname" to fullDomainTarget,
                                        "service" to activeWorkerForRoute,
                                        "environment" to "production"
                                    )
                                    val res = withContext(Dispatchers.IO) { ApiClient.api.putWorkerDomain(accId, payload) }
                                    if (res.isSuccessful && res.body()?.success == true) {
                                        statusMsg = "✅ Domain $fullDomainTarget terhubung!"
                                        Toast.makeText(context, "✅ Domain $fullDomainTarget terpasang!", Toast.LENGTH_SHORT).show()
                                        routeSubdomainInput = ""
                                        loadWorkerRoutes(activeWorkerForRoute)
                                        loadWorkers()
                                    } else {
                                        val err = res.body()?.errors?.firstOrNull()?.message ?: ("HTTP " + res.code())
                                        statusMsg = "Gagal: " + err
                                        Toast.makeText(context, "Gagal: $err", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    statusMsg = "Error: " + e.message
                                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                } finally {
                                    isAddingRoute = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isAddingRoute && selectedZoneDomain.isNotBlank()
                    ) {
                        Text(if (isAddingRoute) "Memproses..." else "Tambah Custom Domain")
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))

                    Text("Rute Terdaftar (" + activeWorkerRoutes.size + "):", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))

                    if (activeWorkerRoutes.isEmpty()) {
                        Text(
                            text = if (isLoadingRoutes) "Memuat..." else "Belum ada custom domain.",
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
                                                        val res = withContext(Dispatchers.IO) { ApiClient.api.deleteWorkerDomain(accId, r.id) }
                                                        if (res.isSuccessful && res.body()?.success == true) {
                                                            statusMsg = "🗑 Rute domain " + r.hostname + " dicopot!"
                                                            Toast.makeText(context, "🗑 Domain ${r.hostname} dicopot!", Toast.LENGTH_SHORT).show()
                                                            loadWorkerRoutes(activeWorkerForRoute)
                                                            loadWorkers()
                                                        }
                                                    } catch (e: Exception) {
                                                        statusMsg = "Error: " + e.message
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
            text = { Text("Yakin ingin menghapus worker '" + workerToDelete + "' secara permanen?") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    scope.launch {
                        statusMsg = "Menghapus worker '" + workerToDelete + "'..."
                        try {
                            val accId = CfAccountHelper.ensureAccountId()
                            val res = withContext(Dispatchers.IO) { ApiClient.api.deleteWorker(accId, workerToDelete) }
                            if (res.isSuccessful && res.body()?.success == true) {
                                statusMsg = "🗑 Worker '" + workerToDelete + "' berhasil dihapus!"
                                loadWorkers()
                            } else {
                                val err = res.body()?.errors?.firstOrNull()?.message ?: ("HTTP " + res.code())
                                statusMsg = "Gagal menghapus: $err"
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
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Batal") }
            }
        )
    }
}
