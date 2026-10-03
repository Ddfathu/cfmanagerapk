package com.cf.manager.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.window.Dialog
import androidx.documentfile.provider.DocumentFile
import com.cf.manager.data.AppConfig
import com.cf.manager.data.BindingType
import com.cf.manager.data.DetectedBinding
import com.cf.manager.data.SmartWranglerParser
import com.cf.manager.data.api.ApiClient
import com.cf.manager.data.api.CfAccountHelper
import com.cf.manager.data.local.AccountStorage
import com.cf.manager.data.local.PagesDeploySnapshot
import com.cf.manager.data.local.PagesHistoryStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

data class PagesCustomDomainItem(
    val id: String = "",
    val name: String = "",
    val status: String = "active",
    val sslStatus: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PagesScreen() {
    val context = LocalContext.current
    val storage = remember { AccountStorage(context) }
    val historyStorage = remember { PagesHistoryStorage(context) }
    val accounts = remember { storage.getAccounts() }
    val activeIdx = remember { storage.getActiveIndex() }
    val currAcc = accounts.getOrNull(activeIdx)
    val email = AppConfig.activeEmail.ifBlank { currAcc?.email ?: "" }
    val apiKey = AppConfig.activeApiKey.ifBlank { currAcc?.apiKey ?: "" }

    var selectedPagesTab by remember { mutableStateOf(0) }
    val pagesTabTitles = listOf("📋 Project", "➕ Buat Project", "🚀 Bulk Multi-Akun")

    var projects by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoadingProjects by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("") }

    // State Buat Project Baru Tunggal
    var newProjectName by remember { mutableStateOf("") }
    var newRawUrl by remember { mutableStateOf("") }
    var rawTargetTab by remember { mutableStateOf(0) }
    var isFetchingNewRaw by remember { mutableStateOf(false) }
    var isDeployingNew by remember { mutableStateOf(false) }

    // State Runtime Pages (Default 2024-01-01 & nodejs_compat)
    var pagesCompatDate by remember { mutableStateOf("2024-01-01") }
    var pagesEnableNodeCompat by remember { mutableStateOf(true) }

    var selectedFolderInfo by remember { mutableStateOf("") }
    var editorCodeTab by remember { mutableStateOf(0) }
    var htmlContent by remember { mutableStateOf("<!DOCTYPE html>\n<html>\n<head><title>My Pages</title></head>\n<body>\n  <h1>Live from Android Pages Studio!</h1>\n</body>\n</html>") }
    var workerScript by remember { mutableStateOf("export default {\n  async fetch(request, env) {\n    return new Response(\"Hello from Pages _worker.js!\");\n  }\n};") }

    // State Smart Wrangler AST Analysis
    var detectedBindings by remember { mutableStateOf<List<DetectedBinding>>(emptyList()) }
    var showWranglerModal by remember { mutableStateOf(false) }

    // State Pop-up Kelola Domain
    var showDomainDialog by remember { mutableStateOf(false) }
    var activeProjectForDomain by remember { mutableStateOf("") }
    var customDomainInput by remember { mutableStateOf("") }
    var isAddingDomain by remember { mutableStateOf(false) }
    var registeredDomains by remember { mutableStateOf<List<PagesCustomDomainItem>>(emptyList()) }
    var isLoadingDomains by remember { mutableStateOf(false) }

    // State Dialog Hapus Project
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var projectToDelete by remember { mutableStateOf("") }

    // State Bulk Multi-Akun
    var selectedAccountIndices by remember { mutableStateOf(setOf<Int>()) }
    var bulkProjectName by remember { mutableStateOf("") }
    var isBulkRunning by remember { mutableStateOf(false) }
    var bulkLogs by remember { mutableStateOf<List<String>>(emptyList()) }

    val scope = rememberCoroutineScope()

    fun loadCustomDomains(projectName: String) {
        if (projectName.isBlank() || email.isBlank() || apiKey.isBlank()) return
        scope.launch {
            isLoadingDomains = true
            try {
                val accId = CfAccountHelper.ensureAccountId()
                val res = ApiClient.api.listPagesCustomDomains(accId, projectName)
                if (res.isSuccessful && res.body()?.success == true) {
                    val resultArr = res.body()?.result ?: emptyList()
                    val list = mutableListOf<PagesCustomDomainItem>()
                    resultArr.forEach { obj ->
                        val id = obj.get("id")?.asString ?: ""
                        val name = obj.get("name")?.asString ?: ""
                        val status = obj.get("status")?.asString ?: "active"
                        val sslObj = obj.getAsJsonObject("ssl")
                        val sslStatus = sslObj?.get("status")?.asString
                        list.add(PagesCustomDomainItem(id, name, status, sslStatus))
                    }
                    registeredDomains = list
                } else {
                    registeredDomains = emptyList()
                }
            } catch (_: Exception) {
                registeredDomains = emptyList()
            } finally {
                isLoadingDomains = false
            }
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val root = DocumentFile.fromTreeUri(context, treeUri)
                    if (root != null && root.isDirectory) {
                        val files = root.listFiles()
                        var foundHtml = false
                        var foundWorker = false
                        var fileCount = 0

                        files.forEach { file ->
                            fileCount++
                            if (file.name.equals("index.html", ignoreCase = true)) {
                                context.contentResolver.openInputStream(file.uri)?.use { stream ->
                                    htmlContent = stream.bufferedReader().readText()
                                }
                                foundHtml = true
                            }
                            if (file.name.equals("_worker.js", ignoreCase = true)) {
                                context.contentResolver.openInputStream(file.uri)?.use { stream ->
                                    workerScript = stream.bufferedReader().readText()
                                }
                                foundWorker = true
                            }
                        }

                        val dirName = root.name ?: "ProjectFolder"
                        if (newProjectName.isBlank()) {
                            newProjectName = dirName.lowercase().replace("[^a-z0-9-]".toRegex(), "-")
                        }
                        if (bulkProjectName.isBlank()) {
                            bulkProjectName = newProjectName
                        }

                        selectedFolderInfo = "📁 Folder '$dirName' ($fileCount file) dipilih."
                        statusMsg = buildString {
                            append("Berhasil memilih folder '$dirName'! ")
                            if (foundHtml) append("index.html termuat. ")
                            if (foundWorker) append("_worker.js termuat. ")
                        }
                    }
                } catch (e: Exception) {
                    statusMsg = "Gagal memindai folder: ${e.message}"
                }
            }
        }
    }

    val singleHtmlPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { fileUri: Uri? ->
        if (fileUri != null) {
            try {
                val content = context.contentResolver.openInputStream(fileUri)?.bufferedReader().use { it?.readText() } ?: ""
                if (content.isNotBlank()) {
                    htmlContent = content
                    editorCodeTab = 0
                    statusMsg = "📄 File HTML berhasil dimuat ke editor!"
                }
            } catch (e: Exception) {
                statusMsg = "Gagal baca file: ${e.message}"
            }
        }
    }

    val singleWorkerPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { fileUri: Uri? ->
        if (fileUri != null) {
            try {
                val content = context.contentResolver.openInputStream(fileUri)?.bufferedReader().use { it?.readText() } ?: ""
                if (content.isNotBlank()) {
                    workerScript = content
                    editorCodeTab = 1
                    statusMsg = "⚡ File _worker.js berhasil dimuat ke editor!"
                }
            } catch (e: Exception) {
                statusMsg = "Gagal baca script: ${e.message}"
            }
        }
    }

    fun loadProjects() {
        if (email.isBlank() || apiKey.isBlank()) {
            statusMsg = "⚠️ Isi Email & API Key di tab Akun terlebih dahulu!"
            return
        }
        scope.launch {
            isLoadingProjects = true
            try {
                val accId = CfAccountHelper.ensureAccountId()
                val res = ApiClient.api.listPagesProjects(accId)
                if (res.isSuccessful && res.body()?.success == true) {
                    val resultList = res.body()?.result ?: emptyList()
                    val list = mutableListOf<String>()
                    resultList.forEach {
                        val name = it.get("name")?.asString
                        if (!name.isNullOrBlank()) list.add(name)
                    }
                    projects = list
                } else {
                    val err = res.body()?.errors?.firstOrNull()?.message ?: "HTTP ${res.code()}"
                    statusMsg = "Gagal memuat project: $err"
                }
            } catch (e: Exception) {
                statusMsg = "Error: ${e.message}"
            } finally {
                isLoadingProjects = false
            }
        }
    }

    LaunchedEffect(email, apiKey) {
        if (email.isNotEmpty() && apiKey.isNotEmpty()) {
            loadProjects()
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
                    Text("📄 Cloudflare Pages", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Snapshot Restore, Runtime Node & Smart Wrangler", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                IconButton(onClick = { loadProjects() }, enabled = !isLoadingProjects) {
                    Text(if (isLoadingProjects) "⏳" else "🔄")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            PrimaryTabRow(selectedTabIndex = selectedPagesTab) {
                pagesTabTitles.forEachIndexed { idx, title ->
                    Tab(
                        selected = selectedPagesTab == idx,
                        onClick = { selectedPagesTab = idx },
                        text = { Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall) }
                    )
                }
            }
        }

        // SUB-TAB 0: DAFTAR PROJECT PAGES (DENGAN RESTORE SNAPSHOT TERAKHIR & RUNTIME)
        if (selectedPagesTab == 0) {
            item {
                Text("Daftar Project Pages Aktif (${projects.size}):", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            if (projects.isEmpty() && !isLoadingProjects) {
                item {
                    Text("Belum ada project Pages.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }

            items(items = projects, key = { it }) { pName ->
                val snapshot = historyStorage.getSnapshot(pName)

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
                                Text("📄 $pName", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("🔗 https://$pName.pages.dev", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                if (snapshot != null) {
                                    Text("💾 Deploy: ${snapshot.deployedAt} (Compat: ${snapshot.compatDate})", style = MaterialTheme.typography.labelSmall, color = Color(0xFF16A34A))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    if (snapshot != null) {
                                        newProjectName = snapshot.projectName
                                        htmlContent = snapshot.htmlContent
                                        workerScript = snapshot.workerScript
                                        pagesCompatDate = snapshot.compatDate
                                        pagesEnableNodeCompat = snapshot.enableNodeCompat
                                        selectedPagesTab = 1
                                        statusMsg = "📥 Berhasil menarik deploy terakhir '$pName' beserta Runtime ${snapshot.compatDate}!"
                                    } else {
                                        statusMsg = "⚠️ Belum ada riwayat snapshot deploy lokal untuk '$pName'."
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text("📥 Tarik Terakhir")
                            }

                            Button(
                                onClick = {
                                    activeProjectForDomain = pName
                                    customDomainInput = ""
                                    loadCustomDomains(pName)
                                    showDomainDialog = true
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text("🌐 Kelola Domain")
                            }

                            Button(
                                onClick = {
                                    projectToDelete = pName
                                    showDeleteConfirm = true
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text("🗑")
                            }
                        }
                    }
                }
            }
        }

        // SUB-TAB 1: FORMULIR BUAT PROJECT DENGAN RUNTIME, SMART WRANGLER & SNAPSHOT LOKAL
        if (selectedPagesTab == 1) {
            item {
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
                            Column {
                                Text("🚀 Studio Editor Pages", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("Konfigurasi Runtime & Simpan Snapshot Lokal", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            }
                            Button(
                                onClick = {
                                    val detected = SmartWranglerParser.parseScriptBindings(workerScript)
                                    detectedBindings = detected
                                    showWranglerModal = true
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("🧠 Smart Wrangler")
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                                .clickable { folderPickerLauncher.launch(null) }
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("📂 Ketuk untuk Impor Folder Web", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text("Membaca index.html & _worker.js sekaligus", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            }
                        }

                        if (selectedFolderInfo.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(selectedFolderInfo, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = newProjectName,
                            onValueChange = { newProjectName = it.lowercase().trim() },
                            label = { Text("Nama Project (.pages.dev)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // SETELAN RUNTIME PAGES (COMPATIBILITY DATE & NODE COMPAT)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = pagesCompatDate,
                                onValueChange = { pagesCompatDate = it.trim() },
                                label = { Text("Runtime Date (YYYY-MM-DD)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = pagesEnableNodeCompat,
                                    onCheckedChange = { pagesEnableNodeCompat = it }
                                )
                                Text("nodejs_compat", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = newRawUrl,
                                onValueChange = { newRawUrl = it.trim() },
                                label = { Text("URL Raw Script / HTML") },
                                placeholder = { Text("https://raw.github.../code") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    if (newRawUrl.isBlank()) return@Button
                                    scope.launch {
                                        isFetchingNewRaw = true
                                        try {
                                            val fetched = withContext(Dispatchers.IO) { URL(newRawUrl).readText() }
                                            if (rawTargetTab == 0) {
                                                htmlContent = fetched
                                                editorCodeTab = 0
                                                statusMsg = "✅ Berhasil menarik kode ke index.html!"
                                            } else {
                                                workerScript = fetched
                                                editorCodeTab = 1
                                                statusMsg = "✅ Berhasil menarik kode ke _worker.js!"
                                            }
                                        } catch (e: Exception) {
                                            statusMsg = "Gagal fetch RAW: ${e.message}"
                                        } finally {
                                            isFetchingNewRaw = false
                                        }
                                    }
                                },
                                enabled = !isFetchingNewRaw && newRawUrl.isNotBlank()
                            ) {
                                Text(if (isFetchingNewRaw) "..." else "Tarik")
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Target Tarik:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            Spacer(modifier = Modifier.width(8.dp))
                            RadioButton(selected = (rawTargetTab == 0), onClick = { rawTargetTab = 0 })
                            Text("index.html", style = MaterialTheme.typography.bodySmall)
                            Spacer(modifier = Modifier.width(10.dp))
                            RadioButton(selected = (rawTargetTab == 1), onClick = { rawTargetTab = 1 })
                            Text("_worker.js", style = MaterialTheme.typography.bodySmall)
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(10.dp))

                        SecondaryTabRow(selectedTabIndex = editorCodeTab) {
                            Tab(selected = editorCodeTab == 0, onClick = { editorCodeTab = 0 }, text = { Text("📄 index.html", fontWeight = FontWeight.Bold) })
                            Tab(selected = editorCodeTab == 1, onClick = { editorCodeTab = 1 }, text = { Text("⚡ _worker.js", fontWeight = FontWeight.Bold) })
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = if (editorCodeTab == 0) "Frontend Source:" else "Pages Functions Script:", style = MaterialTheme.typography.labelMedium)
                            TextButton(onClick = {
                                if (editorCodeTab == 0) singleHtmlPickerLauncher.launch("text/html")
                                else singleWorkerPickerLauncher.launch("*/*")
                            }) {
                                Text(if (editorCodeTab == 0) "📄 File HTML" else "⚡ File _worker.js")
                            }
                        }

                        if (editorCodeTab == 0) {
                            OutlinedTextField(
                                value = htmlContent,
                                onValueChange = { htmlContent = it },
                                modifier = Modifier.fillMaxWidth().height(140.dp),
                                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                            )
                        } else {
                            OutlinedTextField(
                                value = workerScript,
                                onValueChange = { workerScript = it },
                                modifier = Modifier.fillMaxWidth().height(140.dp),
                                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Button(
                            onClick = {
                                val target = newProjectName.trim().lowercase()
                                if (target.isBlank()) {
                                    statusMsg = "Nama project tidak boleh kosong!"
                                    return@Button
                                }
                                scope.launch {
                                    isDeployingNew = true
                                    statusMsg = "Mendaftarkan project Pages '$target' di Cloudflare..."
                                    try {
                                        val accId = CfAccountHelper.ensureAccountId()
                                        val flagsList = if (pagesEnableNodeCompat) listOf("nodejs_compat") else emptyList()

                                        val payload = mapOf(
                                            "name" to target,
                                            "production_branch" to "main",
                                            "deployment_configs" to mapOf(
                                                "production" to mapOf(
                                                    "compatibility_date" to pagesCompatDate,
                                                    "compatibility_flags" to flagsList
                                                )
                                            )
                                        )

                                        val res = ApiClient.api.createPagesProject(accId, payload)
                                        if (res.isSuccessful && res.body()?.success == true) {
                                            // SIMPAN KE SNAPSHOT LOKAL APK BESERTA RUNTIME
                                            historyStorage.saveSnapshot(
                                                PagesDeploySnapshot(
                                                    projectName = target,
                                                    htmlContent = htmlContent,
                                                    workerScript = workerScript,
                                                    compatDate = pagesCompatDate,
                                                    enableNodeCompat = pagesEnableNodeCompat
                                                )
                                            )
                                            statusMsg = "🎉 Pages '$target' berhasil dibuat dengan Runtime $pagesCompatDate!"
                                            selectedPagesTab = 0
                                            loadProjects()
                                        } else {
                                            val err = res.body()?.errors?.firstOrNull()?.message ?: "HTTP ${res.code()}"
                                            statusMsg = "Gagal: $err"
                                        }
                                    } catch (e: Exception) {
                                        statusMsg = "Error: ${e.message}"
                                    } finally {
                                        isDeployingNew = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isDeployingNew && newProjectName.isNotBlank()
                        ) {
                            Text(if (isDeployingNew) "Mendaftarkan..." else "🚀 Simpan Snapshot & Buat Pages")
                        }
                    }
                }
            }
        }

        // SUB-TAB 2: BULK MULTI-AKUN (MENYERTAKAN RUNTIME CONFIG)
        if (selectedPagesTab == 2) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("🚀 Bulk Deploy Pages Multi-Akun", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = bulkProjectName,
                            onValueChange = { bulkProjectName = it.lowercase().trim() },
                            label = { Text("Nama Project Pages") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Akun Target (${selectedAccountIndices.size}/${accounts.size}):", fontWeight = FontWeight.Bold)
                            Row {
                                TextButton(onClick = { selectedAccountIndices = accounts.indices.toSet() }) { Text("Semua") }
                                TextButton(onClick = { selectedAccountIndices = emptySet() }) { Text("Kosongkan") }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            accounts.forEachIndexed { idx, acc ->
                                val isChecked = selectedAccountIndices.contains(idx)
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedAccountIndices = if (isChecked) selectedAccountIndices - idx else selectedAccountIndices + idx
                                        },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isChecked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = { chk ->
                                                selectedAccountIndices = if (chk) selectedAccountIndices + idx else selectedAccountIndices - idx
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(acc.alias.ifBlank { "Akun #${idx + 1}" }, fontWeight = FontWeight.Bold)
                                            Text(acc.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                val pName = bulkProjectName.trim().lowercase()
                                if (pName.isBlank() || selectedAccountIndices.isEmpty()) return@Button

                                scope.launch {
                                    isBulkRunning = true
                                    bulkLogs = emptyList()
                                    val currentEmail = AppConfig.activeEmail
                                    val currentKey = AppConfig.activeApiKey

                                    try {
                                        val targets = selectedAccountIndices.map { accounts[it] }
                                        targets.forEachIndexed { i, acc ->
                                            val logPrefix = "[${i + 1}/${targets.size}] ${acc.alias}: "
                                            try {
                                                AppConfig.activeEmail = acc.email
                                                AppConfig.activeApiKey = acc.apiKey
                                                CfAccountHelper.clearCachedAccountId()

                                                val accId = CfAccountHelper.ensureAccountId()
                                                if (accId.isBlank()) {
                                                    bulkLogs = bulkLogs + (logPrefix + "❌ Gagal mendeteksi Account ID")
                                                    return@forEachIndexed
                                                }

                                                val payload = mapOf(
                                                    "name" to pName,
                                                    "production_branch" to "main",
                                                    "deployment_configs" to mapOf(
                                                        "production" to mapOf(
                                                            "compatibility_date" to "2024-01-01",
                                                            "compatibility_flags" to listOf("nodejs_compat")
                                                        )
                                                    )
                                                )
                                                val res = ApiClient.api.createPagesProject(accId, payload)
                                                if (res.isSuccessful && res.body()?.success == true) {
                                                    bulkLogs = bulkLogs + (logPrefix + "✅ Sukses (https://$pName.pages.dev)")
                                                } else {
                                                    val err = res.body()?.errors?.firstOrNull()?.message ?: "HTTP ${res.code()}"
                                                    bulkLogs = bulkLogs + (logPrefix + "⚠️ Gagal: $err")
                                                }
                                            } catch (e: Exception) {
                                                bulkLogs = bulkLogs + (logPrefix + "❌ Error: ${e.message}")
                                            }
                                        }
                                    } finally {
                                        AppConfig.activeEmail = currentEmail
                                        AppConfig.activeApiKey = currentKey
                                        CfAccountHelper.clearCachedAccountId()
                                        isBulkRunning = false
                                        statusMsg = "🎉 Bulk Deploy Pages selesai!"
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isBulkRunning && bulkProjectName.isNotBlank() && selectedAccountIndices.isNotEmpty()
                        ) {
                            Text(if (isBulkRunning) "Mengeksekusi..." else "🚀 Jalankan Bulk Deploy (${selectedAccountIndices.size} Akun)")
                        }

                        if (bulkLogs.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                bulkLogs.forEach { log ->
                                    Text(log, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                }
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
                    Text(text = statusMsg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
                }
            }
        }
    }

    // MODAL SMART WRANGLER RESULT
    if (showWranglerModal) {
        Dialog(onDismissRequest = { showWranglerModal = false }) {
            Surface(
                modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("🧠 Smart Wrangler AST Analysis", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Deteksi presisi tipe binding berdasarkan method runtime", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

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

    // POP-UP KELOLA DOMAIN
    if (showDomainDialog && activeProjectForDomain.isNotBlank()) {
        Dialog(onDismissRequest = { showDomainDialog = false }) {
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
                            Text("🌐 Custom Domain", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Project: $activeProjectForDomain", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { loadCustomDomains(activeProjectForDomain) }, enabled = !isLoadingDomains) {
                            Text(if (isLoadingDomains) "⏳" else "🔄")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = customDomainInput,
                            onValueChange = { customDomainInput = it.lowercase().trim() },
                            label = { Text("Domain (cth: blog.domain.com)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                if (customDomainInput.isBlank()) return@Button
                                scope.launch {
                                    isAddingDomain = true
                                    try {
                                        val accId = CfAccountHelper.ensureAccountId()
                                        val payload = mapOf("name" to customDomainInput)
                                        val res = ApiClient.api.addPagesCustomDomain(accId, activeProjectForDomain, payload)
                                        if (res.isSuccessful && res.body()?.success == true) {
                                            statusMsg = "✅ Custom domain $customDomainInput ditambahkan!"
                                            customDomainInput = ""
                                            loadCustomDomains(activeProjectForDomain)
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
                            enabled = !isAddingDomain && customDomainInput.isNotBlank()
                        ) {
                            Text(if (isAddingDomain) "..." else "Tambah")
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(10.dp))

                    Text("Domain Terhubung (${registeredDomains.size}):", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))

                    if (registeredDomains.isEmpty()) {
                        Text(
                            text = if (isLoadingDomains) "Memeriksa status domain di Cloudflare..." else "Belum ada domain terhubung pada project ini.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            registeredDomains.forEach { dom ->
                                val isDomainActive = dom.status.equals("active", ignoreCase = true)
                                val isDomainPending = dom.status.contains("pending", ignoreCase = true) || dom.status.contains("initializing", ignoreCase = true)

                                val statusColor = when {
                                    isDomainActive -> Color(0xFF16A34A)
                                    isDomainPending -> Color(0xFFD97706)
                                    else -> Color(0xFFDC2626)
                                }

                                val statusLabel = when {
                                    isDomainActive -> "Aktif"
                                    isDomainPending -> "Pending DNS"
                                    else -> "Mati / Error"
                                }

                                Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(dom.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(statusColor))
                                                Spacer(modifier = Modifier.width(5.dp))
                                                Text(statusLabel, color = statusColor, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                            }
                                        }

                                        IconButton(onClick = {
                                            scope.launch {
                                                try {
                                                    val accId = CfAccountHelper.ensureAccountId()
                                                    val res = ApiClient.api.deletePagesCustomDomain(accId, activeProjectForDomain, dom.name)
                                                    if (res.isSuccessful && res.body()?.success == true) {
                                                        statusMsg = "🗑 Domain ${dom.name} dicopot!"
                                                        loadCustomDomains(activeProjectForDomain)
                                                    }
                                                } catch (e: Exception) {
                                                    statusMsg = "Error: ${e.message}"
                                                }
                                            }
                                        }, modifier = Modifier.size(32.dp)) {
                                            Text("🗑")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showDomainDialog = false }) { Text("Tutup") }
                    }
                }
            }
        }
    }

    // DIALOG HAPUS
    if (showDeleteConfirm && projectToDelete.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Hapus Project Pages?") },
            text = { Text("Yakin ingin menghapus project '$projectToDelete' secara permanen?") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    scope.launch {
                        statusMsg = "Menghapus project '$projectToDelete'..."
                        try {
                            val accId = CfAccountHelper.ensureAccountId()
                            val res = ApiClient.api.deletePagesProject(accId, projectToDelete)
                            if (res.isSuccessful && res.body()?.success == true) {
                                statusMsg = "🗑 Project '$projectToDelete' berhasil dihapus!"
                                loadProjects()
                            } else {
                                val err = res.body()?.errors?.firstOrNull()?.message ?: "HTTP ${res.code()}"
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
