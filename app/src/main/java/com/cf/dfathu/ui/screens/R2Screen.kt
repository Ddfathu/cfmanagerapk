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
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class R2BucketItem(val name: String, val creationDate: String = "")
data class KvNamespaceItem(val id: String, val title: String)
data class WorkerBindingUiItem(val name: String, val type: String, val target: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun R2Screen() {
    val context = LocalContext.current
    val storage = remember { AccountStorage(context) }
    val accounts = remember { storage.getAccounts() }
    val activeIdx = remember { storage.getActiveIndex() }
    val currAcc = accounts.getOrNull(activeIdx)
    val email = AppConfig.activeEmail.ifBlank { currAcc?.email ?: "" }
    val apiKey = AppConfig.activeApiKey.ifBlank { currAcc?.apiKey ?: "" }

    var selectedTab by remember { mutableStateOf(0) }
    val tabTitles = listOf("🪣 Storage (R2 & KV)", "🔗 Binding Worker")

    var buckets by remember { mutableStateOf<List<R2BucketItem>>(emptyList()) }
    var kvNamespaces by remember { mutableStateOf<List<KvNamespaceItem>>(emptyList()) }
    var workers by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedWorker by remember { mutableStateOf("") }
    var workerExpanded by remember { mutableStateOf(false) }

    var bindingList by remember { mutableStateOf<List<WorkerBindingUiItem>>(emptyList()) }
    var rawBindingsList by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var isLoadingBindings by remember { mutableStateOf(false) }

    // Form Tambah Bucket R2 & KV Namespace
    var newBucketName by remember { mutableStateOf("") }
    var newKvTitle by remember { mutableStateOf("") }
    var isCreatingBucket by remember { mutableStateOf(false) }
    var isCreatingKv by remember { mutableStateOf(false) }

    // Form Tambah Binding
    var bindingType by remember { mutableStateOf("r2_bucket") }
    var bindingVarName by remember { mutableStateOf("MY_R2") }
    var selectedBucketForBind by remember { mutableStateOf("") }
    var selectedKvForBind by remember { mutableStateOf("") }
    var bucketDropdownExpanded by remember { mutableStateOf(false) }
    var kvDropdownExpanded by remember { mutableStateOf(false) }
    var isDeployingMultipart by remember { mutableStateOf(false) }

    var statusMsg by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val gson = remember { Gson() }

    fun loadBucketsAndKv() {
        if (email.isBlank() || apiKey.isBlank()) return
        scope.launch {
            isLoading = true
            try {
                val accId = CfAccountHelper.ensureAccountId()
                
                // 1. Load R2 Buckets
                val resR2 = ApiClient.api.listR2Buckets(accId)
                val bodyR2 = resR2.body()
                if (resR2.isSuccessful && bodyR2?.get("success")?.asBoolean == true) {
                    val bucketsArr = bodyR2.getAsJsonObject("result")?.getAsJsonArray("buckets") ?: JsonArray()
                    val listR2 = mutableListOf<R2BucketItem>()
                    bucketsArr.forEach {
                        val bObj = it.asJsonObject
                        val name = bObj.get("name")?.asString ?: ""
                        val date = bObj.get("creation_date")?.asString ?: ""
                        if (name.isNotBlank()) listR2.add(R2BucketItem(name, date))
                    }
                    buckets = listR2
                    if (selectedBucketForBind.isBlank() && listR2.isNotEmpty()) {
                        selectedBucketForBind = listR2[0].name
                    }
                }

                // 2. Load KV Namespaces
                val client = OkHttpClient()
                val kvReq = Request.Builder()
                    .url("https://api.cloudflare.com/client/v4/accounts/$accId/storage/kv/namespaces")
                    .header("X-Auth-Email", email)
                    .header("X-Auth-Key", apiKey)
                    .get()
                    .build()

                val kvResp = withContext(Dispatchers.IO) { client.newCall(kvReq).execute() }
                val kvRespStr = kvResp.body?.string() ?: ""
                val kvJson = gson.fromJson(kvRespStr, JsonObject::class.java)

                if (kvJson != null && kvJson.get("success")?.asBoolean == true) {
                    val kvArr = kvJson.getAsJsonArray("result") ?: JsonArray()
                    val listKv = mutableListOf<KvNamespaceItem>()
                    kvArr.forEach {
                        val kObj = it.asJsonObject
                        val id = kObj.get("id")?.asString ?: ""
                        val title = kObj.get("title")?.asString ?: ""
                        if (id.isNotBlank()) listKv.add(KvNamespaceItem(id, title))
                    }
                    kvNamespaces = listKv
                    if (selectedKvForBind.isBlank() && listKv.isNotEmpty()) {
                        selectedKvForBind = listKv[0].id
                    }
                }

            } catch (e: Exception) {
                statusMsg = "Error load R2/KV: " + e.message
            } finally {
                isLoading = false
            }
        }
    }

    fun loadBindings(workerName: String) {
        if (workerName.isBlank() || email.isBlank() || apiKey.isBlank()) return
        scope.launch {
            isLoadingBindings = true
            statusMsg = "⏳ Mengambil daftar binding R2/KV dari '$workerName'..."
            try {
                val accId = CfAccountHelper.ensureAccountId()
                val client = OkHttpClient()
                var rawResultList = mutableListOf<JsonObject>()

                val bindingsReq = Request.Builder()
                    .url("https://api.cloudflare.com/client/v4/accounts/$accId/workers/scripts/$workerName/bindings")
                    .header("X-Auth-Email", email)
                    .header("X-Auth-Key", apiKey)
                    .get()
                    .build()

                val bResp = withContext(Dispatchers.IO) { client.newCall(bindingsReq).execute() }
                val bRespStr = bResp.body?.string() ?: ""
                val bJson = gson.fromJson(bRespStr, JsonObject::class.java)

                if (bJson != null && bJson.get("success")?.asBoolean == true) {
                    val resArr = bJson.getAsJsonArray("result")
                    resArr?.forEach { element ->
                        if (element.isJsonObject) rawResultList.add(element.asJsonObject)
                    }
                }

                if (rawResultList.isEmpty()) {
                    val settingsReq = Request.Builder()
                        .url("https://api.cloudflare.com/client/v4/accounts/$accId/workers/scripts/$workerName/settings")
                        .header("X-Auth-Email", email)
                        .header("X-Auth-Key", apiKey)
                        .get()
                        .build()

                    val sResp = withContext(Dispatchers.IO) { client.newCall(settingsReq).execute() }
                    val sRespStr = sResp.body?.string() ?: ""
                    val sJson = gson.fromJson(sRespStr, JsonObject::class.java)

                    if (sJson != null && sJson.get("success")?.asBoolean == true) {
                        val bindingsArr = sJson.getAsJsonObject("result")?.getAsJsonArray("bindings")
                        bindingsArr?.forEach { element ->
                            if (element.isJsonObject) rawResultList.add(element.asJsonObject)
                        }
                    }
                }

                rawBindingsList = rawResultList
                val parsed = mutableListOf<WorkerBindingUiItem>()

                rawResultList.forEach { obj ->
                    val type = (obj.get("type")?.asString ?: "").lowercase()
                    val name = obj.get("name")?.asString ?: ""

                    if (type == "r2_bucket") {
                        val target = obj.get("bucket_name")?.asString ?: ""
                        parsed.add(WorkerBindingUiItem(name, "r2_bucket", target))
                    } else if (type == "kv_namespace") {
                        val target = obj.get("namespace_id")?.asString ?: ""
                        parsed.add(WorkerBindingUiItem(name, "kv_namespace", target))
                    }
                }

                bindingList = parsed
                if (parsed.isEmpty()) {
                    statusMsg = "Worker '$workerName' belum memiliki binding R2 atau KV."
                } else {
                    statusMsg = "✅ Berhasil memuat ${parsed.size} binding R2/KV dari '$workerName'."
                }

            } catch (e: Exception) {
                bindingList = emptyList()
                rawBindingsList = emptyList()
                statusMsg = "❌ Error load bindings: " + e.message
            } finally {
                isLoadingBindings = false
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
                        loadBindings(list[0])
                    }
                }
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(email, apiKey) {
        if (email.isNotEmpty() && apiKey.isNotEmpty()) {
            loadBucketsAndKv()
            loadWorkers()
        }
    }

    LaunchedEffect(selectedWorker, selectedTab) {
        if (selectedTab == 1 && selectedWorker.isNotBlank()) {
            loadBindings(selectedWorker)
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
                    Text("💿 Cloudflare R2 & KV Manager", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Kelola Storage & Binding Worker", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                IconButton(
                    onClick = {
                        loadBucketsAndKv()
                        loadWorkers()
                        if (selectedWorker.isNotBlank()) loadBindings(selectedWorker)
                    },
                    enabled = !isLoading && !isLoadingBindings
                ) {
                    Text(if (isLoading || isLoadingBindings) "⏳" else "🔄")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            PrimaryTabRow(selectedTabIndex = selectedTab) {
                tabTitles.forEachIndexed { idx, title ->
                    Tab(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx },
                        text = { Text(title, fontWeight = FontWeight.SemiBold) }
                    )
                }
            }
        }

        // --- SUB-TAB 0: DAFTAR & BUAT BUCKET R2 / KV NAMESPACE ---
        if (selectedTab == 0) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("➕ Buat Bucket R2 Baru", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = newBucketName,
                                onValueChange = { newBucketName = it.lowercase().trim() },
                                label = { Text("Nama Bucket (cth: storage-data)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    if (newBucketName.isBlank()) return@Button
                                    scope.launch {
                                        isCreatingBucket = true
                                        statusMsg = "Membuat bucket '$newBucketName'..."
                                        try {
                                            val accId = CfAccountHelper.ensureAccountId()
                                            val payload = mapOf("name" to newBucketName)
                                            val res = ApiClient.api.createR2Bucket(accId, payload)
                                            if (res.isSuccessful && res.body()?.success == true) {
                                                statusMsg = "✅ Bucket '$newBucketName' berhasil dibuat!"
                                                newBucketName = ""
                                                loadBucketsAndKv()
                                            } else {
                                                val err = res.body()?.errors?.firstOrNull()?.message ?: ("HTTP " + res.code())
                                                statusMsg = "Gagal: " + err
                                            }
                                        } catch (e: Exception) {
                                            statusMsg = "Error: " + e.message
                                        } finally {
                                            isCreatingBucket = false
                                        }
                                    }
                                },
                                enabled = !isCreatingBucket && newBucketName.isNotBlank()
                            ) {
                                Text(if (isCreatingBucket) "..." else "Buat")
                            }
                        }
                    }
                }
            }

            // FITUR BARU: FORM BUAT KV NAMESPACE
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("➕ Buat KV Namespace Baru", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = newKvTitle,
                                onValueChange = { newKvTitle = it.trim() },
                                label = { Text("Nama/Title KV Namespace") },
                                placeholder = { Text("cth: CONFIG_KV") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    if (newKvTitle.isBlank()) return@Button
                                    scope.launch {
                                        isCreatingKv = true
                                        statusMsg = "Membuat KV Namespace '$newKvTitle'..."
                                        try {
                                            val accId = CfAccountHelper.ensureAccountId()
                                            val client = OkHttpClient()
                                            val payload = JsonObject().apply { addProperty("title", newKvTitle) }

                                            val req = Request.Builder()
                                                .url("https://api.cloudflare.com/client/v4/accounts/$accId/storage/kv/namespaces")
                                                .header("X-Auth-Email", email)
                                                .header("X-Auth-Key", apiKey)
                                                .header("Content-Type", "application/json")
                                                .post(payload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                                                .build()

                                            val resp = withContext(Dispatchers.IO) { client.newCall(req).execute() }
                                            val respStr = resp.body?.string() ?: ""
                                            val json = gson.fromJson(respStr, JsonObject::class.java)

                                            if (json != null && json.get("success")?.asBoolean == true) {
                                                statusMsg = "✅ KV Namespace '$newKvTitle' berhasil dibuat!"
                                                newKvTitle = ""
                                                loadBucketsAndKv()
                                            } else {
                                                val err = json?.getAsJsonArray("errors")?.firstOrNull()?.asJsonObject?.get("message")?.asString ?: ("HTTP " + resp.code)
                                                statusMsg = "Gagal: " + err
                                            }
                                        } catch (e: Exception) {
                                            statusMsg = "Error: " + e.message
                                        } finally {
                                            isCreatingKv = false
                                        }
                                    }
                                },
                                enabled = !isCreatingKv && newKvTitle.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9333EA))
                            ) {
                                Text(if (isCreatingKv) "..." else "Buat KV")
                            }
                        }
                    }
                }
            }

            item {
                Text("Daftar Storage Aktif:", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            items(buckets) { b ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("💿 R2: " + b.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            if (b.creationDate.isNotBlank()) {
                                Text("Dibuat: " + b.creationDate.take(10), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            }
                        }

                        IconButton(onClick = {
                            scope.launch {
                                statusMsg = "Menghapus bucket " + b.name + "..."
                                try {
                                    val accId = CfAccountHelper.ensureAccountId()
                                    val res = ApiClient.api.deleteR2Bucket(accId, b.name)
                                    if (res.isSuccessful && res.body()?.success == true) {
                                        statusMsg = "🗑 Bucket '" + b.name + "' berhasil dihapus!"
                                        loadBucketsAndKv()
                                    } else {
                                        val err = res.body()?.errors?.firstOrNull()?.message ?: ("HTTP " + res.code())
                                        statusMsg = "Gagal hapus: " + err
                                    }
                                } catch (e: Exception) {
                                    statusMsg = "Error: " + e.message
                                }
                            }
                        }) {
                            Text("🗑")
                        }
                    }
                }
            }

            items(kvNamespaces) { kv ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("📦 KV: " + kv.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text("ID: " + kv.id, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.outline)
                        }

                        IconButton(onClick = {
                            scope.launch {
                                statusMsg = "Menghapus KV Namespace " + kv.title + "..."
                                try {
                                    val accId = CfAccountHelper.ensureAccountId()
                                    val client = OkHttpClient()

                                    val req = Request.Builder()
                                        .url("https://api.cloudflare.com/client/v4/accounts/$accId/storage/kv/namespaces/${kv.id}")
                                        .header("X-Auth-Email", email)
                                        .header("X-Auth-Key", apiKey)
                                        .delete()
                                        .build()

                                    val resp = withContext(Dispatchers.IO) { client.newCall(req).execute() }
                                    val respStr = resp.body?.string() ?: ""
                                    val json = gson.fromJson(respStr, JsonObject::class.java)

                                    if (json != null && json.get("success")?.asBoolean == true) {
                                        statusMsg = "🗑 KV Namespace '" + kv.title + "' dihapus!"
                                        loadBucketsAndKv()
                                    } else {
                                        val err = json?.getAsJsonArray("errors")?.firstOrNull()?.asJsonObject?.get("message")?.asString ?: ("HTTP " + resp.code)
                                        statusMsg = "Gagal hapus: " + err
                                    }
                                } catch (e: Exception) {
                                    statusMsg = "Error: " + e.message
                                }
                            }
                        }) {
                            Text("🗑")
                        }
                    }
                }
            }
        }

        // --- SUB-TAB 1: WORKER BINDINGS ---
        if (selectedTab == 1) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("Pilih Worker Target:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(6.dp))

                        ExposedDropdownMenuBox(
                            expanded = workerExpanded,
                            onExpandedChange = { workerExpanded = !workerExpanded }
                        ) {
                            OutlinedTextField(
                                value = selectedWorker.ifBlank { "Pilih Worker Target" },
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Worker Target") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = workerExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = workerExpanded,
                                onDismissRequest = { workerExpanded = false }
                            ) {
                                workers.forEach { w ->
                                    DropdownMenuItem(
                                        text = { Text("⚡ " + w) },
                                        onClick = {
                                            selectedWorker = w
                                            workerExpanded = false
                                            loadBindings(w)
                                        }
                                    )
                                }
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
                        Text("➕ Tambah / Pasang Binding Baru", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = (bindingType == "r2_bucket"),
                                onClick = { bindingType = "r2_bucket"; bindingVarName = "MY_R2" }
                            )
                            Text("R2 Bucket (💿)", style = MaterialTheme.typography.bodySmall)
                            Spacer(modifier = Modifier.width(16.dp))
                            RadioButton(
                                selected = (bindingType == "kv_namespace"),
                                onClick = { bindingType = "kv_namespace"; bindingVarName = "MY_KV" }
                            )
                            Text("KV Namespace (📦)", style = MaterialTheme.typography.bodySmall)
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = bindingVarName,
                            onValueChange = { bindingVarName = it.trim() },
                            label = { Text("Nama Variabel Binding (env.NAMA_INI)") },
                            placeholder = { Text("Contoh: MY_R2 atau DATA_STORE") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        if (bindingType == "r2_bucket") {
                            if (buckets.isNotEmpty()) {
                                ExposedDropdownMenuBox(
                                    expanded = bucketDropdownExpanded,
                                    onExpandedChange = { bucketDropdownExpanded = !bucketDropdownExpanded }
                                ) {
                                    OutlinedTextField(
                                        value = selectedBucketForBind.ifBlank { "Pilih Bucket R2..." },
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("Target Bucket R2") },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = bucketDropdownExpanded) },
                                        modifier = Modifier.menuAnchor().fillMaxWidth()
                                    )
                                    ExposedDropdownMenu(
                                        expanded = bucketDropdownExpanded,
                                        onDismissRequest = { bucketDropdownExpanded = false }
                                    ) {
                                        buckets.forEach { b ->
                                            DropdownMenuItem(
                                                text = { Text("💿 " + b.name) },
                                                onClick = {
                                                    selectedBucketForBind = b.name
                                                    bucketDropdownExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            } else {
                                Text("⚠️ Belum ada bucket R2. Buat bucket di tab sebelah dulu.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                        } else {
                            // AUTO DROPDOWN DARI KVM NAMESPACES BANYAK
                            if (kvNamespaces.isNotEmpty()) {
                                val selectedKvObj = kvNamespaces.find { it.id == selectedKvForBind }
                                val kvLabel = if (selectedKvObj != null) "${selectedKvObj.title} (${selectedKvObj.id.take(8)}...)" else "Pilih KV Namespace..."

                                ExposedDropdownMenuBox(
                                    expanded = kvDropdownExpanded,
                                    onExpandedChange = { kvDropdownExpanded = !kvDropdownExpanded }
                                ) {
                                    OutlinedTextField(
                                        value = kvLabel,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("Target KV Namespace") },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = kvDropdownExpanded) },
                                        modifier = Modifier.menuAnchor().fillMaxWidth()
                                    )
                                    ExposedDropdownMenu(
                                        expanded = kvDropdownExpanded,
                                        onDismissRequest = { kvDropdownExpanded = false }
                                    ) {
                                        kvNamespaces.forEach { kv ->
                                            DropdownMenuItem(
                                                text = { Text("📦 ${kv.title} (${kv.id.take(8)}...)") },
                                                onClick = {
                                                    selectedKvForBind = kv.id
                                                    kvDropdownExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            } else {
                                Text("⚠️ Belum ada KV Namespace. Buat KV Namespace terlebih dahulu.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Button(
                            onClick = {
                                val w = selectedWorker
                                if (w.isBlank()) {
                                    statusMsg = "Pilih worker target terlebih dahulu!"
                                    return@Button
                                }
                                if (bindingVarName.isBlank()) {
                                    statusMsg = "Nama variabel binding tidak boleh kosong!"
                                    return@Button
                                }
                                if (bindingType == "r2_bucket" && selectedBucketForBind.isBlank()) {
                                    statusMsg = "Pilih bucket R2 terlebih dahulu!"
                                    return@Button
                                }
                                if (bindingType == "kv_namespace" && selectedKvForBind.isBlank()) {
                                    statusMsg = "Pilih KV Namespace terlebih dahulu!"
                                    return@Button
                                }

                                scope.launch {
                                    isDeployingMultipart = true
                                    statusMsg = "⏳ Menyimpan binding ke worker '$w'..."
                                    try {
                                        val accId = CfAccountHelper.ensureAccountId()
                                        val client = OkHttpClient()

                                        val filteredList = rawBindingsList.filter {
                                            val name = it.get("name")?.asString
                                            !(name.equals(bindingVarName, ignoreCase = true))
                                        }.toMutableList()

                                        val newBindingObj = JsonObject().apply {
                                            addProperty("type", bindingType)
                                            addProperty("name", bindingVarName)
                                            if (bindingType == "r2_bucket") {
                                                addProperty("bucket_name", selectedBucketForBind)
                                            } else {
                                                addProperty("namespace_id", selectedKvForBind)
                                            }
                                        }
                                        filteredList.add(newBindingObj)

                                        val settingsObj = JsonObject().apply {
                                            add("bindings", gson.toJsonTree(filteredList))
                                        }

                                        val settingsRequestBody = settingsObj.toString()
                                            .toRequestBody("application/json".toMediaTypeOrNull())

                                        val multipartBody = MultipartBody.Builder()
                                            .setType(MultipartBody.FORM)
                                            .addFormDataPart("settings", "settings.json", settingsRequestBody)
                                            .build()

                                        val patchReq = Request.Builder()
                                            .url("https://api.cloudflare.com/client/v4/accounts/$accId/workers/scripts/$w/settings")
                                            .header("X-Auth-Email", email)
                                            .header("X-Auth-Key", apiKey)
                                            .patch(multipartBody)
                                            .build()

                                        val patchResp = withContext(Dispatchers.IO) { client.newCall(patchReq).execute() }
                                        val patchRespStr = patchResp.body?.string() ?: ""
                                        val patchJson = gson.fromJson(patchRespStr, JsonObject::class.java)

                                        if (patchJson != null && patchJson.get("success")?.asBoolean == true) {
                                            statusMsg = "✅ Sukses pasang binding env.$bindingVarName di '$w'!"
                                            loadBindings(w)
                                        } else {
                                            val err = patchJson?.getAsJsonArray("errors")?.firstOrNull()?.asJsonObject?.get("message")?.asString ?: ("HTTP " + patchResp.code)
                                            statusMsg = "❌ Gagal binding: $err"
                                        }
                                    } catch (e: Exception) {
                                        statusMsg = "❌ Error: " + e.message
                                    } finally {
                                        isDeployingMultipart = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                            enabled = !isDeployingMultipart && selectedWorker.isNotBlank()
                        ) {
                            Text(if (isDeployingMultipart) "Menyimpan Binding..." else "🔗 Pasang Binding ke Worker")
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Daftar Binding R2 & KV Aktif di Worker '" + selectedWorker + "' (" + bindingList.size + "):",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = { if (selectedWorker.isNotBlank()) loadBindings(selectedWorker) }, enabled = !isLoadingBindings) {
                        Text(if (isLoadingBindings) "⏳" else "🔄")
                    }
                }
            }

            if (bindingList.isEmpty() && !isLoadingBindings) {
                item {
                    Text(
                        text = if (selectedWorker.isBlank()) "Silakan pilih worker terlebih dahulu di atas." else "Tidak ada binding R2 atau KV yang terpasang di worker ini.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            items(bindingList) { b ->
                val isR2 = (b.type == "r2_bucket")
                val badgeColor = if (isR2) Color(0xFFEA580C) else Color(0xFF9333EA)
                val badgeBg = badgeColor.copy(alpha = 0.12f)
                val badgeText = if (isR2) "R2 BUCKET (💿)" else "KV NAMESPACE (📦)"

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = RoundedCornerShape(4.dp), color = badgeBg) {
                                    Text(
                                        text = badgeText,
                                        color = badgeColor,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "env." + b.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Target: " + b.target,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }

                        IconButton(onClick = {
                            scope.launch {
                                statusMsg = "Mencopot binding env." + b.name + "..."
                                try {
                                    val accId = CfAccountHelper.ensureAccountId()
                                    val client = OkHttpClient()

                                    val filtered = rawBindingsList.filter { 
                                        !(it.get("name")?.asString.equals(b.name, ignoreCase = true)) 
                                    }

                                    val settingsObj = JsonObject().apply {
                                        add("bindings", gson.toJsonTree(filtered))
                                    }

                                    val settingsRequestBody = settingsObj.toString()
                                        .toRequestBody("application/json".toMediaTypeOrNull())

                                    val multipartBody = MultipartBody.Builder()
                                        .setType(MultipartBody.FORM)
                                        .addFormDataPart("settings", "settings.json", settingsRequestBody)
                                        .build()

                                    val patchReq = Request.Builder()
                                        .url("https://api.cloudflare.com/client/v4/accounts/$accId/workers/scripts/$selectedWorker/settings")
                                        .header("X-Auth-Email", email)
                                        .header("X-Auth-Key", apiKey)
                                        .patch(multipartBody)
                                        .build()

                                    val patchResp = withContext(Dispatchers.IO) { client.newCall(patchReq).execute() }
                                    val patchRespStr = patchResp.body?.string() ?: ""
                                    val patchJson = gson.fromJson(patchRespStr, JsonObject::class.java)

                                    if (patchJson != null && patchJson.get("success")?.asBoolean == true) {
                                        statusMsg = "🗑 Binding env." + b.name + " berhasil dicopot!"
                                        loadBindings(selectedWorker)
                                    } else {
                                        val err = patchJson?.getAsJsonArray("errors")?.firstOrNull()?.asJsonObject?.get("message")?.asString ?: ("HTTP " + patchResp.code)
                                        statusMsg = "❌ Gagal mencopot: $err"
                                    }
                                } catch (e: Exception) {
                                    statusMsg = "Error: " + e.message
                                }
                            }
                        }) {
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
