package com.cf.dfathu.ui.screens

import android.content.Intent
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.documentfile.provider.DocumentFile
import com.cf.dfathu.data.AppConfig
import com.cf.dfathu.data.BindingType
import com.cf.dfathu.data.DetectedBinding
import com.cf.dfathu.data.SmartWranglerParser
import com.cf.dfathu.data.api.ApiClient
import com.cf.dfathu.data.api.CfAccountHelper
import com.cf.dfathu.data.local.AccountStorage
import com.cf.dfathu.data.local.PagesDeploySnapshot
import com.cf.dfathu.data.local.PagesHistoryStorage
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
    val pagesTabTitles = listOf("📋 Project", "➕ Buat Project", "🚀 Deploy Studio", "⚡ Bulk Akun")

    var projects by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoadingProjects by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("") }

    // --- TAB 1: FORM BUAT PROJECT BARU (DOH 1.1.1.1 PERSIS SC WEB) ---
    var newCreateProjectName by remember { mutableStateOf("") }
    var newCreateCompatDate by remember { mutableStateOf("2024-01-01") }
    var newCreateEnableNodeCompat by remember { mutableStateOf(true) }
    var isCheckingSubdomain by remember { mutableStateOf(false) }
    var subdomainCheckMsg by remember { mutableStateOf("") }
    var isSubdomainAvailable by remember { mutableStateOf<Boolean?>(null) }
    var isCreatingNewProject by remember { mutableStateOf(false) }

    // --- TAB 2: DEPLOY STUDIO (PERSIS SC WEB) ---
    var targetProjectName by remember { mutableStateOf("") }
    var deployCompatDate by remember { mutableStateOf("2024-01-01") }
    var deployEnableNodeCompat by remember { mutableStateOf(true) }
    var selectedFolderInfo by remember { mutableStateOf("") }
    var editorCodeTab by remember { mutableStateOf(0) }
    var htmlContent by remember { mutableStateOf("<!DOCTYPE html>\n<html>\n<head><title>My Pages</title></head>\n<body>\n  <h1>Live from Android Pages Studio!</h1>\n</body>\n</html>") }
    var workerScript by remember { mutableStateOf("export default {\n  async fetch(request, env) {\n    return new Response(\"Hello from Pages _worker.js!\");\n  }\n};") }
    var workerRawUrl by remember { mutableStateOf("") }
    var rawTargetTab by remember { mutableStateOf(0) }
    var isFetchingRaw by remember { mutableStateOf(false) }
    var isDeploying by remember { mutableStateOf(false) }
    var isPullingLatest by remember { mutableStateOf(false) }

    // Smart Wrangler
    var detectedBindings by remember { mutableStateOf<List<DetectedBinding>>(emptyList()) }
    var showWranglerModal by remember { mutableStateOf(false) }

    // Pop-up Domain
    var showDomainDialog by remember { mutableStateOf(false) }
    var activeProjectForDomain by remember { mutableStateOf("") }
    var customDomainInput by remember { mutableStateOf("") }
    var isAddingDomain by remember { mutableStateOf(false) }
    var registeredDomains by remember { mutableStateOf<List<PagesCustomDomainItem>>(emptyList()) }
    var isLoadingDomains by remember { mutableStateOf(false) }

    // Dialog Hapus
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var projectToDelete by remember { mutableStateOf("") }

    // Bulk Multi-Akun
    var selectedAccountIndices by remember { mutableStateOf(setOf<Int>()) }
    var bulkProjectName by remember { mutableStateOf("") }
    var isBulkRunning by remember { mutableStateOf(false) }
    var bulkLogs by remember { mutableStateOf<List<String>>(emptyList()) }

    val scope = rememberCoroutineScope()
    val gson = remember { Gson() }

    fun openBrowser(urlStr: String) {
        try {
            val validUrl = if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) "https://$urlStr" else urlStr
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(validUrl)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    fun checkSubdomainWithDoh(nameInput: String) {
        val name = nameInput.trim().lowercase()
        if (name.isBlank()) {
            subdomainCheckMsg = "Ketik nama project terlebih dahulu!"
            isSubdomainAvailable = null
            return
        }
        val targetDomain = "$name.pages.dev"
        scope.launch {
            isCheckingSubdomain = true
            subdomainCheckMsg = "⏳ Mengecek ketersediaan $targetDomain via Cloudflare DNS..."
            isSubdomainAvailable = null
            try {
                val client = OkHttpClient()
                val request = Request.Builder()
                    .url("https://1.1.1.1/dns-query?name=" + targetDomain + "&type=A")
                    .header("Accept", "application/dns-json")
                    .build()

                val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
                val respText = response.body?.string() ?: ""
                val json = gson.fromJson(respText, JsonObject::class.java)
                val status = json.get("Status")?.asInt ?: 0

                if (status == 3) {
                    isSubdomainAvailable = true
                    subdomainCheckMsg = "🟢 " + targetDomain + " TERSEDIA! URL aman, langsung klik Buat Project."
                } else {
                    isSubdomainAvailable = false
                    subdomainCheckMsg = "🔴 " + targetDomain + " SUDAH TERPAKAI! Cloudflare bakal acak URL jadi " + name + "-xyz.pages.dev. Pakai nama lain."
                }
            } catch (e: Exception) {
                isSubdomainAvailable = null
                subdomainCheckMsg = "Gagal memeriksa DNS: " + e.message
            } finally {
                isCheckingSubdomain = false
            }
        }
    }

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

    fun pullLatestDeployment(pName: String) {
        if (pName.isBlank()) {
            statusMsg = "Pilih atau isi nama project terlebih dahulu!"
            return
        }
        val snapshot = historyStorage.getSnapshot(pName)
        if (snapshot != null) {
            targetProjectName = snapshot.projectName
            htmlContent = snapshot.htmlContent
            workerScript = snapshot.workerScript
            deployCompatDate = snapshot.compatDate
            deployEnableNodeCompat = snapshot.enableNodeCompat
            detectedBindings = SmartWranglerParser.parseScriptBindings(snapshot.workerScript)
            statusMsg = "📥 Berhasil menarik snapshot deploy terakhir '$pName'!"
        } else {
            scope.launch {
                isPullingLatest = true
                try {
                    val accId = CfAccountHelper.ensureAccountId()
                    val res = ApiClient.api.listPagesProjects(accId)
                    if (res.isSuccessful && res.body()?.success == true) {
                        val matched = res.body()?.result?.find { it.get("name")?.asString.equals(pName, ignoreCase = true) }
                        if (matched != null) {
                            targetProjectName = pName
                            val prodCfg = matched.getAsJsonObject("deployment_configs")?.getAsJsonObject("production")
                            val cDate = prodCfg?.get("compatibility_date")?.asString ?: "2024-01-01"
                            val flags = prodCfg?.getAsJsonArray("compatibility_flags")?.map { it.asString } ?: emptyList()
                            deployCompatDate = cDate
                            deployEnableNodeCompat = flags.contains("nodejs_compat")
                            statusMsg = "📥 Berhasil memuat project '$pName' dari Cloudflare!"
                        } else {
                            statusMsg = "Project '$pName' tidak ditemukan di Cloudflare."
                        }
                    }
                } catch (e: Exception) {
                    statusMsg = "Gagal tarik data: " + e.message
                } finally {
                    isPullingLatest = false
                }
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
                                    val ws = stream.bufferedReader().readText()
                                    workerScript = ws
                                    detectedBindings = SmartWranglerParser.parseScriptBindings(ws)
                                }
                                foundWorker = true
                            }
                        }

                        val dirName = root.name ?: "ProjectFolder"
                        if (targetProjectName.isBlank()) {
                            targetProjectName = dirName.lowercase().replace("[^a-z0-9-]".toRegex(), "-")
                        }
                        if (bulkProjectName.isBlank()) {
                            bulkProjectName = targetProjectName
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
                    detectedBindings = SmartWranglerParser.parseScriptBindings(content)
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
                    val err = res.body()?.errors?.firstOrNull()?.message ?: ("HTTP " + res.code())
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
                    Text("📄 Cloudflare Pages Hub", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Pengecekan DoH 1.1.1.1, Deploy Studio & Bulk", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
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

        // ==================== SUB-TAB 0: DAFTAR PROJECT ====================
        if (selectedPagesTab == 0) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Daftar Project Pages Aktif (" + projects.size + "):", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = { selectedPagesTab = 1 },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("+ Buat Baru")
                    }
                }
            }

            if (projects.isEmpty() && !isLoadingProjects) {
                item {
                    Text("Belum ada project Pages.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }

            items(items = projects, key = { it }) { pName ->
                val snapshot = historyStorage.getSnapshot(pName)
                val fullUrl = "https://" + pName + ".pages.dev"

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
                                Text("📄 " + pName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = "🔗 " + fullUrl + " ↗",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    textDecoration = TextDecoration.Underline,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable { openBrowser(fullUrl) }
                                )
                                if (snapshot != null) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text("💾 Deploy: " + snapshot.deployedAt + " (Compat: " + snapshot.compatDate + ")", style = MaterialTheme.typography.labelSmall, color = Color(0xFF16A34A))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Button(
                                onClick = {
                                    pullLatestDeployment(pName)
                                    selectedPagesTab = 2
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEA580C)),
                                modifier = Modifier.weight(1.1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text("🚀 Deploy")
                            }

                            OutlinedButton(
                                onClick = {
                                    pullLatestDeployment(pName)
                                    selectedPagesTab = 2
                                },
                                modifier = Modifier.weight(1.3f),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
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
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                modifier = Modifier.weight(1.1f),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                            ) {
                                Text("🌐 Domain")
                            }

                            IconButton(
                                onClick = {
                                    projectToDelete = pName
                                    showDeleteConfirm = true
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Text("🗑")
                            }
                        }
                    }
                }
            }
        }

        // ==================== SUB-TAB 1: KHUSUS BUAT PROJECT BARU (DOH 1.1.1.1 PERSIS SC WEB) ====================
        if (selectedPagesTab == 1) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("➕ Buat Project Pages Baru", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Periksa ketersediaan nama subdomain via DoH Cloudflare 1.1.1.1 sebelum membuat project", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = newCreateProjectName,
                                onValueChange = {
                                    newCreateProjectName = it.lowercase().trim()
                                    subdomainCheckMsg = ""
                                    isSubdomainAvailable = null
                                },
                                label = { Text("Nama Subdomain Project") },
                                placeholder = { Text("contoh: namaku-web") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = { checkSubdomainWithDoh(newCreateProjectName) },
                                enabled = !isCheckingSubdomain && newCreateProjectName.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(if (isCheckingSubdomain) "..." else "🔍 Cek Unik")
                            }
                        }

                        if (subdomainCheckMsg.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            val resultColor = when (isSubdomainAvailable) {
                                true -> Color(0xFF16A34A)
                                false -> Color(0xFFDC2626)
                                else -> MaterialTheme.colorScheme.primary
                            }
                            Text(
                                text = subdomainCheckMsg,
                                color = resultColor,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = newCreateCompatDate,
                                onValueChange = { newCreateCompatDate = it.trim() },
                                label = { Text("Compatibility Date") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = newCreateEnableNodeCompat,
                                    onCheckedChange = { newCreateEnableNodeCompat = it }
                                )
                                Text("nodejs_compat", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Button(
                            onClick = {
                                val target = newCreateProjectName.trim().lowercase()
                                if (target.isBlank()) {
                                    statusMsg = "Nama project tidak boleh kosong!"
                                    return@Button
                                }
                                scope.launch {
                                    isCreatingNewProject = true
                                    statusMsg = "Mendaftarkan project '$target' di Cloudflare Pages..."
                                    try {
                                        val accId = CfAccountHelper.ensureAccountId()
                                        val flagsList = if (newCreateEnableNodeCompat) listOf("nodejs_compat") else emptyList()
                                        val payload = mapOf(
                                            "name" to target,
                                            "production_branch" to "main",
                                            "deployment_configs" to mapOf(
                                                "production" to mapOf(
                                                    "compatibility_date" to newCreateCompatDate,
                                                    "compatibility_flags" to flagsList
                                                )
                                            )
                                        )
                                        val res = ApiClient.api.createPagesProject(accId, payload)
                                        if (res.isSuccessful && res.body()?.success == true) {
                                            statusMsg = "🎉 Project Pages '" + target + ".pages.dev' berhasil dibuat!"
                                            targetProjectName = target
                                            deployCompatDate = newCreateCompatDate
                                            deployEnableNodeCompat = newCreateEnableNodeCompat
                                            loadProjects()
                                            selectedPagesTab = 2
                                        } else {
                                            val err = res.body()?.errors?.firstOrNull()?.message ?: ("HTTP " + res.code())
                                            statusMsg = "Gagal membuat project: $err"
                                        }
                                    } catch (e: Exception) {
                                        statusMsg = "Error: ${e.message}"
                                    } finally {
                                        isCreatingNewProject = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                            enabled = !isCreatingNewProject && newCreateProjectName.isNotBlank()
                        ) {
                            Text(if (isCreatingNewProject) "Membuat Project..." else "✅ Buat Project & Masuk ke Deploy Studio")
                        }
                    }
                }
            }
        }

        // ==================== SUB-TAB 2: DEPLOY STUDIO ====================
        if (selectedPagesTab == 2) {
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
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (targetProjectName.isNotBlank()) "🚀 Deploy: " + targetProjectName else "🚀 Studio Deploy Pages",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text("Kelola kode index.html & _worker.js", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            }
                            Button(
                                onClick = {
                                    detectedBindings = SmartWranglerParser.parseScriptBindings(workerScript)
                                    showWranglerModal = true
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("🧠 Wrangler (" + detectedBindings.size + ")")
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(76.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                                .clickable { folderPickerLauncher.launch(null) }
                                .padding(10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("📂 Ketuk untuk Impor Folder Web Utuh", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text("Membaca index.html & _worker.js sekaligus", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            }
                        }

                        if (selectedFolderInfo.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(selectedFolderInfo, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = targetProjectName,
                                onValueChange = { targetProjectName = it.lowercase().trim() },
                                label = { Text("Project Target (.pages.dev)") },
                                placeholder = { Text("nama-project") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = { pullLatestDeployment(targetProjectName) },
                                enabled = !isPullingLatest && targetProjectName.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF475569)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Text(if (isPullingLatest) "..." else "📥 Tarik Terakhir")
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = deployCompatDate,
                                onValueChange = { deployCompatDate = it.trim() },
                                label = { Text("Runtime Date") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = deployEnableNodeCompat,
                                    onCheckedChange = { deployEnableNodeCompat = it }
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
                                value = workerRawUrl,
                                onValueChange = { workerRawUrl = it.trim() },
                                label = { Text("URL Raw Script / HTML") },
                                placeholder = { Text("https://raw.github.../code") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    if (workerRawUrl.isBlank()) return@Button
                                    scope.launch {
                                        isFetchingRaw = true
                                        try {
                                            val fetched = withContext(Dispatchers.IO) { URL(workerRawUrl).readText() }
                                            if (rawTargetTab == 0) {
                                                htmlContent = fetched
                                                editorCodeTab = 0
                                                statusMsg = "✅ Berhasil memuat kode ke index.html!"
                                            } else {
                                                workerScript = fetched
                                                detectedBindings = SmartWranglerParser.parseScriptBindings(fetched)
                                                editorCodeTab = 1
                                                statusMsg = "✅ Berhasil memuat kode ke _worker.js!"
                                            }
                                        } catch (e: Exception) {
                                            statusMsg = "Gagal fetch RAW: ${e.message}"
                                        } finally {
                                            isFetchingRaw = false
                                        }
                                    }
                                },
                                enabled = !isFetchingRaw && workerRawUrl.isNotBlank()
                            ) {
                                Text(if (isFetchingRaw) "..." else "Tarik")
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

                        Spacer(modifier = Modifier.height(10.dp))
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
                                onValueChange = {
                                    workerScript = it
                                    detectedBindings = SmartWranglerParser.parseScriptBindings(it)
                                },
                                modifier = Modifier.fillMaxWidth().height(140.dp),
                                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Button(
                            onClick = {
                                val target = targetProjectName.trim().lowercase()
                                if (target.isBlank()) {
                                    statusMsg = "Nama project tidak boleh kosong!"
                                    return@Button
                                }
                                scope.launch {
                                    isDeploying = true
                                    statusMsg = "Mendeploy aset Pages ke '$target'..."
                                    try {
                                        val accId = CfAccountHelper.ensureAccountId()
                                        val flagsList = if (deployEnableNodeCompat) listOf("nodejs_compat") else emptyList()

                                        val payload = mapOf(
                                            "name" to target,
                                            "production_branch" to "main",
                                            "deployment_configs" to mapOf(
                                                "production" to mapOf(
                                                    "compatibility_date" to deployCompatDate,
                                                    "compatibility_flags" to flagsList
                                                )
                                            )
                                        )

                                        val res = ApiClient.api.createPagesProject(accId, payload)
                                        if (res.isSuccessful && res.body()?.success == true) {
                                            historyStorage.saveSnapshot(
                                                PagesDeploySnapshot(
                                                    projectName = target,
                                                    htmlContent = htmlContent,
                                                    workerScript = workerScript,
                                                    compatDate = deployCompatDate,
                                                    enableNodeCompat = deployEnableNodeCompat
                                                )
                                            )
                                            statusMsg = "🎉 Pages '$target' berhasil dideploy!"
                                            selectedPagesTab = 0
                                            loadProjects()
                                        } else {
                                            val err = res.body()?.errors?.firstOrNull()?.message ?: ("HTTP " + res.code())
                                            statusMsg = "Gagal deploy: $err"
                                        }
                                    } catch (e: Exception) {
                                        statusMsg = "Error: ${e.message}"
                                    } finally {
                                        isDeploying = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEA580C)),
                            enabled = !isDeploying && targetProjectName.isNotBlank()
                        ) {
                            Text(if (isDeploying) "Deploying..." else "🚀 Simpan Snapshot & Deploy Pages")
                        }
                    }
                }
            }
        }

        // ==================== SUB-TAB 3: BULK MULTI-AKUN ====================
        if (selectedPagesTab == 3) {
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
                            Text("Akun Target (" + selectedAccountIndices.size + "/" + accounts.size + "):", fontWeight = FontWeight.Bold)
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
                                            Text(acc.alias.ifBlank { "Akun #" + (idx + 1) }, fontWeight = FontWeight.Bold)
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
                                            val logPrefix = "[" + (i + 1) + "/" + targets.size + "] " + acc.alias + ": "
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
                                                    bulkLogs = bulkLogs + (logPrefix + "✅ Sukses (https://" + pName + ".pages.dev)")
                                                } else {
                                                    val err = res.body()?.errors?.firstOrNull()?.message ?: ("HTTP " + res.code())
                                                    bulkLogs = bulkLogs + (logPrefix + "⚠️ Gagal: " + err)
                                                }
                                            } catch (e: Exception) {
                                                bulkLogs = bulkLogs + (logPrefix + "❌ Error: " + e.message)
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
                            Text(if (isBulkRunning) "Mengeksekusi..." else "🚀 Jalankan Bulk Deploy (" + selectedAccountIndices.size + " Akun)")
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
                            Text("Project: " + activeProjectForDomain, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
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
                                            statusMsg = "✅ Custom domain " + customDomainInput + " ditambahkan!"
                                            customDomainInput = ""
                                            loadCustomDomains(activeProjectForDomain)
                                        } else {
                                            val err = res.body()?.errors?.firstOrNull()?.message ?: ("HTTP " + res.code())
                                            statusMsg = "Gagal: " + err
                                        }
                                    } catch (e: Exception) {
                                        statusMsg = "Error: " + e.message
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

                    Text("Domain Terhubung (" + registeredDomains.size + "):", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
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
                                                        statusMsg = "🗑 Domain " + dom.name + " dicopot!"
                                                        loadCustomDomains(activeProjectForDomain)
                                                    }
                                                } catch (e: Exception) {
                                                    statusMsg = "Error: " + e.message
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
            text = { Text("Yakin ingin menghapus project '" + projectToDelete + "' secara permanen?") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    scope.launch {
                        statusMsg = "Menghapus project '" + projectToDelete + "'..."
                        try {
                            val accId = CfAccountHelper.ensureAccountId()
                            val res = ApiClient.api.deletePagesProject(accId, projectToDelete)
                            if (res.isSuccessful && res.body()?.success == true) {
                                statusMsg = "🗑 Project '" + projectToDelete + "' berhasil dihapus!"
                                loadProjects()
                            } else {
                                val err = res.body()?.errors?.firstOrNull()?.message ?: ("HTTP " + res.code())
                                statusMsg = "Gagal menghapus: " + err
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
