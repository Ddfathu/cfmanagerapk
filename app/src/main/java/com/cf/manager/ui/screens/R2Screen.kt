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
import androidx.compose.ui.text.font.FontFamily
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

data class R2BucketItem(val name: String, val creationDate: String = "")

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

    var currentRawBindings by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var isLoadingBindings by remember { mutableStateOf(false) }

    // Form Tambah Bucket
    var newBucketName by remember { mutableStateOf("") }
    var isCreatingBucket by remember { mutableStateOf(false) }

    // Form Tambah Binding (Atomic Multipart Pattern)
    var bindingType by remember { mutableStateOf("r2_bucket") } // r2_bucket / kv_namespace
    var bindingVarName by remember { mutableStateOf("MY_BUCKET") }
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
                statusMsg = "Error: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    // Tarik daftar binding aktif dari Worker dengan parser objek aman
    fun loadBindings(workerName: String) {
        if (workerName.isBlank()) return
        scope.launch {
            isLoadingBindings = true
            try {
                val accId = CfAccountHelper.ensureAccountId()
                val res = ApiClient.api.getWorkerBindings(accId, workerName)
                if (res.isSuccessful && res.body()?.success == true) {
                    currentRawBindings = res.body()?.result ?: emptyList()
                } else {
                    currentRawBindings = emptyList()
                }
            } catch (_: Exception) {
                currentRawBindings = emptyList()
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
                    Text("🪣 Cloudflare R2 & Bindings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Teknik Atomic Multipart (Sama persis Web Dashboard)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                IconButton(onClick = { loadBuckets(); loadWorkers() }, enabled = !isLoading) {
                    Text(if (isLoading) "⏳" else "🔄")
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
                                        statusMsg = "Membuat bucket '$newBucketName'..."
                                        try {
                                            val accId = CfAccountHelper.ensureAccountId()
                                            val payload = mapOf("name" to newBucketName)
                                            val res = ApiClient.api.createR2Bucket(accId, payload)
                                            if (res.isSuccessful && res.body()?.success == true) {
                                                statusMsg = "✅ Bucket '$newBucketName' berhasil dibuat!"
                                                newBucketName = ""
                                                loadBuckets()
                                            } else {
                                                val err = res.body()?.errors?.firstOrNull()?.message ?: "HTTP ${res.code()}"
                                                statusMsg = "Gagal: $err"
                                            }
                                        } catch (e: Exception) {
                                            statusMsg = "Error: ${e.message}"
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
                Text("Daftar Bucket R2 Aktif (${buckets.size}):", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                            Text("🪣 ${b.name}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            if (b.creationDate.isNotBlank()) {
                                Text(b.creationDate.take(10), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            }
                        }

                        IconButton(onClick = {
                            scope.launch {
                                statusMsg = "Menghapus bucket ${b.name}..."
                                try {
                                    val accId = CfAccountHelper.ensureAccountId()
                                    val res = ApiClient.api.deleteR2Bucket(accId, b.name)
                                    if (res.isSuccessful && res.body()?.success == true) {
                                        statusMsg = "🗑 Bucket '${b.name}' berhasil dihapus!"
                                        loadBuckets()
                                    } else {
                                        val err = res.body()?.errors?.firstOrNull()?.message ?: "HTTP ${res.code()}"
                                        statusMsg = "Gagal hapus: $err"
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
                        Text("Target Worker:", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(6.dp))

                        if (workers.isNotEmpty()) {
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
                                    workers.forEach { w ->
                                        DropdownMenuItem(
                                            text = { Text(w) },
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

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(10.dp))

                        Text("➕ Tambah / Pasang Binding Baru", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = (bindingType == "r2_bucket"),
                                onClick = { bindingType = "r2_bucket"; bindingVarName = "MY_BUCKET" }
                            )
                            Text("R2 Bucket", style = MaterialTheme.typography.bodySmall)
                            Spacer(modifier = Modifier.width(16.dp))
                            RadioButton(
                                selected = (bindingType == "kv_namespace"),
                                onClick = { bindingType = "kv_namespace"; bindingVarName = "MY_KV" }
                            )
                            Text("KV Namespace", style = MaterialTheme.typography.bodySmall)
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = bindingVarName,
                            onValueChange = { bindingVarName = it.trim() },
                            label = { Text("Nama Variabel Binding (cth: MY_BUCKET / MY_KV)") },
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
                                        value = selectedBucketForBind,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("Pilih Target Bucket R2") },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = bucketDropdownExpanded) },
                                        modifier = Modifier.menuAnchor().fillMaxWidth()
                                    )
                                    ExposedDropdownMenu(
                                        expanded = bucketDropdownExpanded,
                                        onDismissRequest = { bucketDropdownExpanded = false }
                                    ) {
                                        buckets.forEach { b ->
                                            DropdownMenuItem(
                                                text = { Text(b.name) },
                                                onClick = {
                                                    selectedBucketForBind = b.name
                                                    bucketDropdownExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            } else {
                                Text("⚠️ Buat bucket R2 terlebih dahulu di tab sebelah.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                        } else {
                            OutlinedTextField(
                                value = manualTargetId,
                                onValueChange = { manualTargetId = it.trim() },
                                label = { Text("ID Namespace KV (32 Karakter Hex)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Button(
                            onClick = {
                                if (selectedWorker.isBlank()) {
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
                                    statusMsg = "Menyiapkan Atomic Multipart Binding ke '$selectedWorker'..."
                                    try {
                                        val accId = CfAccountHelper.ensureAccountId()

                                        // 1. Tarik binding eksisting (GET Settings)
                                        val getRes = ApiClient.api.getWorkerBindings(accId, selectedWorker)
                                        val existingBindings = if (getRes.isSuccessful && getRes.body()?.success == true) {
                                            getRes.body()?.result?.toMutableList() ?: mutableListOf()
                                        } else {
                                            mutableListOf()
                                        }

                                        // 2. Filter binding lama dengan nama & tipe yang sama (mencegah duplikat)
                                        val filteredList = existingBindings.filter {
                                            val name = it.get("name")?.asString
                                            val type = it.get("type")?.asString
                                            !(name == bindingVarName && type == bindingType)
                                        }.toMutableList()

                                        // 3. Buat objek binding baru sesuai tipe
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

                                        // 4. Susun Multipart Body PATCH Settings persis seperti Web Backend
                                        val settingsMap = mapOf("bindings" to filteredList)
                                        val settingsJsonStr = gson.toJson(settingsMap)
                                        val settingsRequestBody = settingsJsonStr.toRequestBody("application/json".toMediaTypeOrNull())
                                        val settingsPart = MultipartBody.Part.createFormData("settings", "settings.json", settingsRequestBody)

                                        val patchRes = ApiClient.api.patchWorkerSettingsMultipart(accId, selectedWorker, settingsPart)
                                        if (patchRes.isSuccessful && patchRes.body()?.success == true) {
                                            statusMsg = "✅ Sukses pasang binding '$bindingVarName' di worker '$selectedWorker'!"
                                            loadBindings(selectedWorker)
                                            manualTargetId = ""
                                        } else {
                                            val err = patchRes.body()?.errors?.firstOrNull()?.message ?: "HTTP ${patchRes.code()}"
                                            statusMsg = "Gagal binding: $err"
                                        }
                                    } catch (e: Exception) {
                                        statusMsg = "Error: ${e.message}"
                                    } finally {
                                        isDeployingMultipart = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isDeployingMultipart && selectedWorker.isNotBlank()
                        ) {
                            Text(if (isDeployingMultipart) "Mengirim Multipart..." else "🔗 Pasang Binding (Atomic Multipart)")
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text("Daftar Binding Aktif di Worker '$selectedWorker':", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            if (currentRawBindings.isEmpty()) {
                item {
                    Text(
                        text = if (isLoadingBindings) "Memuat binding worker..." else "Tidak ada binding R2 atau KV yang terpasang di worker ini.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            items(currentRawBindings) { bObj ->
                val bType = bObj.get("type")?.asString ?: ""
                if (bType == "r2_bucket" || bType == "kv_namespace") {
                    val bName = bObj.get("name")?.asString ?: ""
                    val targetName = if (bType == "r2_bucket") bObj.get("bucket_name")?.asString else bObj.get("namespace_id")?.asString

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
                                Text("env.$bName", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                Text("Tipe: ${bType.uppercase()} | Target: $targetName", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            }

                            IconButton(onClick = {
                                scope.launch {
                                    statusMsg = "Mencopot binding env.$bName..."
                                    try {
                                        val accId = CfAccountHelper.ensureAccountId()
                                        val getRes = ApiClient.api.getWorkerBindings(accId, selectedWorker)
                                        if (getRes.isSuccessful && getRes.body()?.success == true) {
                                            val list = getRes.body()?.result?.toMutableList() ?: mutableListOf()
                                            val filtered = list.filter { !(it.get("name")?.asString == bName && it.get("type")?.asString == bType) }

                                            val settingsMap = mapOf("bindings" to filtered)
                                            val settingsJsonStr = gson.toJson(settingsMap)
                                            val settingsRequestBody = settingsJsonStr.toRequestBody("application/json".toMediaTypeOrNull())
                                            val settingsPart = MultipartBody.Part.createFormData("settings", "settings.json", settingsRequestBody)

                                            val patchRes = ApiClient.api.patchWorkerSettingsMultipart(accId, selectedWorker, settingsPart)
                                            if (patchRes.isSuccessful && patchRes.body()?.success == true) {
                                                statusMsg = "🗑 Binding env.$bName berhasil dicopot!"
                                                loadBindings(selectedWorker)
                                            }
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
