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
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

data class R2BucketItem(val name: String, val creationDate: String = "")
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
    val tabTitles = listOf("🪣 Bucket R2", "🔗 Binding Worker")

    var buckets by remember { mutableStateOf<List<R2BucketItem>>(emptyList()) }
    var workers by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedWorker by remember { mutableStateOf("") }
    var workerExpanded by remember { mutableStateOf(false) }

    var bindingList by remember { mutableStateOf<List<WorkerBindingUiItem>>(emptyList()) }
    var isLoadingBindings by remember { mutableStateOf(false) }

    // Form Tambah Bucket
    var newBucketName by remember { mutableStateOf("") }
    var isCreatingBucket by remember { mutableStateOf(false) }

    // Form Tambah Binding (Sesuai SC Web: R2 & KV)
    var bindingType by remember { mutableStateOf("r2_bucket") } // "r2_bucket" atau "kv_namespace"
    var bindingVarName by remember { mutableStateOf("MY_R2") }
    var selectedBucketForBind by remember { mutableStateOf("") }
    var bucketDropdownExpanded by remember { mutableStateOf(false) }
    var manualTargetId by remember { mutableStateOf("") }
    var isDeployingMultipart by remember { mutableStateOf(false) }

    var statusMsg by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val gson = remember { Gson() }

    fun loadBuckets() {
        if (email.isBlank() || apiKey.isBlank()) return
        scope.launch {
            isLoading = true
            try {
                val accId = CfAccountHelper.ensureAccountId()
                val res = ApiClient.api.listR2Buckets(accId)
                val bodyJson = res.body()
                val isSuccess = bodyJson?.get("success")?.asBoolean == true
                if (res.isSuccessful && isSuccess) {
                    val resultObj = bodyJson?.getAsJsonObject("result")
                    val bucketsArr = resultObj?.getAsJsonArray("buckets") ?: JsonArray()
                    val list = mutableListOf<R2BucketItem>()
                    bucketsArr.forEach {
                        val bObj = it.asJsonObject
                        val name = bObj.get("name")?.asString ?: ""
                        val date = bObj.get("creation_date")?.asString ?: ""
                        if (name.isNotBlank()) list.add(R2BucketItem(name, date))
                    }
                    buckets = list
                    if (selectedBucketForBind.isBlank() && list.isNotEmpty()) {
                        selectedBucketForBind = list[0].name
                    }
                }
            } catch (e: Exception) {
                statusMsg = "Error load buckets: " + e.message
            } finally {
                isLoading = false
            }
        }
    }

    fun loadBindings(workerName: String) {
        if (workerName.isBlank() || email.isBlank() || apiKey.isBlank()) return
        scope.launch {
            isLoadingBindings = true
            try {
                val accId = CfAccountHelper.ensureAccountId()
                val res = ApiClient.api.getWorkerBindings(accId, workerName)
                if (res.isSuccessful && res.body()?.success == true) {
                    val rawList = res.body()?.result ?: emptyList()
                    val parsed = mutableListOf<WorkerBindingUiItem>()
                    rawList.forEach { obj ->
                        val type = obj.get("type")?.asString ?: ""
                        val name = obj.get("name")?.asString ?: ""
                        if (type == "r2_bucket") {
                            val bucketName = obj.get("bucket_name")?.asString ?: ""
                            parsed.add(WorkerBindingUiItem(name, "r2_bucket", bucketName))
                        } else if (type == "kv_namespace") {
                            val nsId = obj.get("namespace_id")?.asString ?: ""
                            parsed.add(WorkerBindingUiItem(name, "kv_namespace", nsId))
                        }
                    }
                    bindingList = parsed
                } else {
                    bindingList = emptyList()
                }
            } catch (e: Exception) {
                bindingList = emptyList()
                statusMsg = "Gagal memuat binding: " + e.message
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
            loadBuckets()
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
                    Text("💿 Cloudflare R2 & Bindings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Manajemen Bucket & Binding Worker (Persis SC Web)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                IconButton(
                    onClick = {
                        loadBuckets()
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

        // --- SUB-TAB 0: DAFTAR & BUAT BUCKET R2 ---
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
                                        statusMsg = "Membuat bucket '" + newBucketName + "'..."
                                        try {
                                            val accId = CfAccountHelper.ensureAccountId()
                                            val payload = mapOf("name" to newBucketName)
                                            val res = ApiClient.api.createR2Bucket(accId, payload)
                                            if (res.isSuccessful && res.body()?.success == true) {
                                                statusMsg = "✅ Bucket '" + newBucketName + "' berhasil dibuat!"
                                                newBucketName = ""
                                                loadBuckets()
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

            item {
                Text("Daftar Bucket R2 Aktif (" + buckets.size + "):", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            if (buckets.isEmpty() && !isLoading) {
                item {
                    Text("Belum ada bucket R2 di akun ini.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
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
                            Text("💿 " + b.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            if (b.creationDate.isNotBlank()) {
                                Text(b.creationDate.take(10), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
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
                                        loadBuckets()
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
        }

        // --- SUB-TAB 1: WORKER BINDINGS VIA ATOMIC MULTIPART ---
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
                                value = selectedWorker.ifBlank { "Pilih Worker..." },
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Worker") },
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
                            OutlinedTextField(
                                value = manualTargetId,
                                onValueChange = { manualTargetId = it.trim() },
                                label = { Text("ID Namespace KV (32 Karakter Hex)") },
                                placeholder = { Text("Contoh: 0f2b38c...") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
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
                                if (bindingType == "kv_namespace" && manualTargetId.isBlank()) {
                                    statusMsg = "ID Namespace KV tidak boleh kosong!"
                                    return@Button
                                }

                                scope.launch {
                                    isDeployingMultipart = true
                                    statusMsg = "Menyimpan binding ke worker '" + w + "'..."
                                    try {
                                        val accId = CfAccountHelper.ensureAccountId()

                                        val getRes = ApiClient.api.getWorkerBindings(accId, w)
                                        val existingBindings = if (getRes.isSuccessful && getRes.body()?.success == true) {
                                            getRes.body()?.result?.toMutableList() ?: mutableListOf()
                                        } else {
                                            mutableListOf()
                                        }

                                        val filteredList = existingBindings.filter {
                                            val name = it.get("name")?.asString
                                            !(name == bindingVarName)
                                        }.toMutableList()

                                        val newBindingObj = JsonObject().apply {
                                            addProperty("type", bindingType)
                                            addProperty("name", bindingVarName)
                                            if (bindingType == "r2_bucket") {
                                                addProperty("bucket_name", selectedBucketForBind)
                                            } else {
                                                addProperty("namespace_id", manualTargetId)
                                            }
                                        }
                                        filteredList.add(newBindingObj)

                                        val settingsMap = mapOf("bindings" to filteredList)
                                        val settingsJsonStr = gson.toJson(settingsMap)
                                        val settingsRequestBody = settingsJsonStr.toRequestBody("application/json".toMediaTypeOrNull())
                                        val settingsPart = MultipartBody.Part.createFormData("settings", "settings.json", settingsRequestBody)

                                        val patchRes = ApiClient.api.patchWorkerSettingsMultipart(accId, w, settingsPart)
                                        if (patchRes.isSuccessful && patchRes.body()?.success == true) {
                                            statusMsg = "✅ Sukses pasang binding env." + bindingVarName + " di '" + w + "'!"
                                            loadBindings(w)
                                            manualTargetId = ""
                                        } else {
                                            val err = patchRes.body()?.errors?.firstOrNull()?.message ?: ("HTTP " + patchRes.code())
                                            statusMsg = "Gagal binding: " + err
                                        }
                                    } catch (e: Exception) {
                                        statusMsg = "Error: " + e.message
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
                        text = "Daftar Binding Aktif di Worker '" + selectedWorker + "' (" + bindingList.size + "):",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = { if (selectedWorker.isNotBlank()) loadBindings(selectedWorker) }, enabled = !isLoadingBindings) {
                        Text(if (isLoadingBindings) "⏳" else "🔄")
                    }
                }
            }

            if (bindingList.isEmpty()) {
                item {
                    Text(
                        text = if (isLoadingBindings) "Memuat binding worker..." else "Tidak ada binding R2 atau KV yang terpasang di worker ini.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            items(bindingList) { b ->
                val isR2 = (b.type == "r2_bucket")
                val badgeColor = if (isR2) Color(0xFFEA580C) else Color(0xFF9333EA)
                val badgeBg = if (isR2) Color(0xFFFFF7ED) else Color(0xFFFAF5FF)
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
                                    val getRes = ApiClient.api.getWorkerBindings(accId, selectedWorker)
                                    if (getRes.isSuccessful && getRes.body()?.success == true) {
                                        val list = getRes.body()?.result?.toMutableList() ?: mutableListOf()
                                        val filtered = list.filter { !(it.get("name")?.asString == b.name) }

                                        val settingsMap = mapOf("bindings" to filtered)
                                        val settingsJsonStr = gson.toJson(settingsMap)
                                        val settingsRequestBody = settingsJsonStr.toRequestBody("application/json".toMediaTypeOrNull())
                                        val settingsPart = MultipartBody.Part.createFormData("settings", "settings.json", settingsRequestBody)

                                        val patchRes = ApiClient.api.patchWorkerSettingsMultipart(accId, selectedWorker, settingsPart)
                                        if (patchRes.isSuccessful && patchRes.body()?.success == true) {
                                            statusMsg = "🗑 Binding env." + b.name + " berhasil dicopot!"
                                            loadBindings(selectedWorker)
                                        } else {
                                            val err = patchRes.body()?.errors?.firstOrNull()?.message ?: ("HTTP " + patchRes.code())
                                            statusMsg = "Gagal mencopot: " + err
                                        }
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
