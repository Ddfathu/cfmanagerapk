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
import androidx.documentfile.provider.DocumentFile
import com.cf.manager.data.AppConfig
import com.cf.manager.data.api.ApiClient
import com.cf.manager.data.api.CfAccountHelper
import com.cf.manager.data.local.AccountStorage
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
    val accounts = remember { storage.getAccounts() }
    val activeIdx = remember { storage.getActiveIndex() }
    val currAcc = accounts.getOrNull(activeIdx)
    val email = AppConfig.activeEmail.ifBlank { currAcc?.email ?: "" }
    val apiKey = AppConfig.activeApiKey.ifBlank { currAcc?.apiKey ?: "" }

    var selectedPagesTab by remember { mutableStateOf(0) }

    var projects by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoadingProjects by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("") }

    // State Tambah / Buat Project Baru
    var newProjectName by remember { mutableStateOf("") }
    var newRawUrl by remember { mutableStateOf("") }
    var isFetchingNewRaw by remember { mutableStateOf(false) }
    var isCheckingDomain by remember { mutableStateOf(false) }
    var domainAvailableMsg by remember { mutableStateOf("") }
    var isDeployingNew by remember { mutableStateOf(false) }

    var selectedFolderInfo by remember { mutableStateOf("") }
    var htmlContent by remember { mutableStateOf("<!DOCTYPE html>\n<html>\n<head><title>My Pages</title></head>\n<body>\n  <h1>Live from Android Pages Studio!</h1>\n</body>\n</html>") }
    var workerScript by remember { mutableStateOf("export default {\n  async fetch(req, env) {\n    return env.ASSETS.fetch(req);\n  }\n};") }

    // State Custom Domain Terdaftar untuk Project Terpilih
    var selectedProjectForDomain by remember { mutableStateOf("") }
    var customDomainInput by remember { mutableStateOf("") }
    var isAddingDomain by remember { mutableStateOf(false) }
    var registeredDomains by remember { mutableStateOf<List<PagesCustomDomainItem>>(emptyList()) }
    var isLoadingDomains by remember { mutableStateOf(false) }

    // State Dialog Hapus Project
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var projectToDelete by remember { mutableStateOf("") }

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
                }
            } catch (_: Exception) {} finally {
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

    val singleFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { fileUri: Uri? ->
        if (fileUri != null) {
            try {
                val content = context.contentResolver.openInputStream(fileUri)?.bufferedReader().use { it?.readText() } ?: ""
                if (content.isNotBlank()) {
                    htmlContent = content
                    selectedFolderInfo = "📄 File HTML mandiri berhasil dimuat."
                    statusMsg = "HTML berhasil diimpor!"
                }
            } catch (e: Exception) {
                statusMsg = "Gagal baca file: ${e.message}"
            }
        }
    }

    fun loadProjects() {
        if (email.isBlank() || apiKey.isBlank()) {
            statusMsg = "⚠️️ Isi Email & API Key di tab Akun terlebih dahulu!"
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
                    if (list.isNotEmpty()) {
                        if (selectedProjectForDomain.isEmpty() || !list.contains(selectedProjectForDomain)) {
                            selectedProjectForDomain = list[0]
                        }
                        loadCustomDomains(selectedProjectForDomain)
                    }
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

    LaunchedEffect(selectedProjectForDomain) {
        if (selectedProjectForDomain.isNotEmpty()) {
            loadCustomDomains(selectedProjectForDomain)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // --- 1. HEADER & TAB SWITCHER ---
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("📄 Cloudflare Pages (Direct)", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Kelola project statis & custom domain native", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                IconButton(onClick = { loadProjects() }, enabled = !isLoadingProjects) {
                    Text(if (isLoadingProjects) "⏳" else "🔄")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            PrimaryTabRow(selectedTabIndex = selectedPagesTab) {
                Tab(
                    selected = selectedPagesTab == 0,
                    onClick = { selectedPagesTab = 0 },
                    text = { Text("📋 Daftar Project (${projects.size})", fontWeight = FontWeight.SemiBold) }
                )
                Tab(
                    selected = selectedPagesTab == 1,
                    onClick = { selectedPagesTab = 1 },
                    text = { Text("➕ Buat Project Baru", fontWeight = FontWeight.SemiBold) }
                )
            }
        }

        // --- SUB-TAB 0: DAFTAR & KELOLA PROJECT PAGES ---
        if (selectedPagesTab == 0) {
            // SECTION PASANG CUSTOM DOMAIN
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("🌐 Custom Domain untuk: ${selectedProjectForDomain.ifBlank { "(Pilih project di bawah)" }}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("Hubungkan nama domain ke project Pages yang dipilih", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

                        Spacer(modifier = Modifier.height(8.dp))

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
                                    val target = selectedProjectForDomain
                                    if (target.isBlank() || customDomainInput.isBlank()) {
                                        statusMsg = "Pilih project di bawah dan isi domain!"
                                        return@Button
                                    }
                                    scope.launch {
                                        isAddingDomain = true
                                        statusMsg = "Menghubungkan domain ke Pages '$target' di Cloudflare..."
                                        try {
                                            val accId = CfAccountHelper.ensureAccountId()
                                            val payload = mapOf("name" to customDomainInput)
                                            val res = ApiClient.api.addPagesCustomDomain(accId, target, payload)
                                            if (res.isSuccessful && res.body()?.success == true) {
                                                statusMsg = "✅ Custom domain $customDomainInput ditambahkan!"
                                                customDomainInput = ""
                                                loadCustomDomains(target)
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
                                enabled = !isAddingDomain && selectedProjectForDomain.isNotBlank() && customDomainInput.isNotBlank()
                            ) {
                                Text(if (isAddingDomain) "..." else "Hubungkan")
                            }
                        }

                        // DAFTAR DOMAIN TERDAFTAR BESERTA STATUS AKTIF / PENDING
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Domain Terdaftar di '$selectedProjectForDomain' (${registeredDomains.size}):",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        if (registeredDomains.isEmpty()) {
                            Text(
                                text = if (isLoadingDomains) "Memeriksa domain..." else "Belum ada custom domain yang terhubung ke project ini.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                        isDomainPending -> "Pending DNS / SSL"
                                        else -> dom.status
                                    }

                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surface
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("🔗 ${dom.name}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(7.dp)
                                                            .clip(CircleShape)
                                                            .background(statusColor)
                                                    )
                                                    Spacer(modifier = Modifier.width(5.dp))
                                                    Text(
                                                        text = statusLabel,
                                                        color = statusColor,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }

                                            IconButton(
                                                onClick = {
                                                    scope.launch {
                                                        statusMsg = "Mencopot domain ${dom.name}..."
                                                        try {
                                                            val accId = CfAccountHelper.ensureAccountId()
                                                            val res = ApiClient.api.deletePagesCustomDomain(accId, selectedProjectForDomain, dom.name)
                                                            if (res.isSuccessful && res.body()?.success == true) {
                                                                statusMsg = "🗑 Domain ${dom.name} berhasil dicopot!"
                                                                loadCustomDomains(selectedProjectForDomain)
                                                            } else {
                                                                val err = res.body()?.errors?.firstOrNull()?.message ?: "HTTP ${res.code()}"
                                                                statusMsg = "Gagal copot: $err"
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
                    }
                }
            }

            // LIST KARTU PROJECT PAGES
            item {
                Text("Daftar Project Pages Aktif (${projects.size}):", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            if (projects.isEmpty() && !isLoadingProjects) {
                item {
                    Text("Belum ada project Pages. Pindah ke tab '➕ Buat Project Baru' di atas!", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }

            items(items = projects, key = { it }) { pName ->
                val isSelected = (selectedProjectForDomain == pName)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("📄 $pName", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("🔗 https://$pName.pages.dev", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    selectedProjectForDomain = pName
                                    loadCustomDomains(pName)
                                    statusMsg = "Project '$pName' dipilih untuk pengaturan custom domain."
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
                                Text("🗑 Hapus")
                            }
                        }
                    }
                }
            }
        }

        // --- SUB-TAB 1: FORMULIR BUAT PROJECT PAGES BARU ---
        if (selectedPagesTab == 1) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("🚀 Buat Project Pages Baru", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Daftarkan nama project Pages langsung di Cloudflare", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

                        Spacer(modifier = Modifier.height(12.dp))

                        // KOTAK DROP-DRAG & FOLDER PICKER ANDROID
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .border(
                                    width = 1.5.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable { folderPickerLauncher.launch(null) }
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("📂 Ketuk untuk Pilih Folder Proyek Web (Drop/Drag)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Otomatis membaca nama folder & file aset", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
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

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = newRawUrl,
                                onValueChange = { newRawUrl = it.trim() },
                                label = { Text("URL Raw HTML (GitHub / Link)") },
                                placeholder = { Text("https://raw.githubusercontent.com/.../index.html") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    if (newRawUrl.isBlank()) return@Button
                                    scope.launch {
                                        isFetchingNewRaw = true
                                        statusMsg = "Mengunduh template..."
                                        try {
                                            val fetched = withContext(Dispatchers.IO) { URL(newRawUrl).readText() }
                                            htmlContent = fetched
                                            statusMsg = "✅ Template HTML berhasil ditarik!"
                                        } catch (e: Exception) {
                                            statusMsg = "Gagal fetch RAW: ${e.message}"
                                        } finally {
                                            isFetchingNewRaw = false
                                        }
                                    }
                                },
                                enabled = !isFetchingNewRaw && newRawUrl.isNotBlank(),
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                Text(if (isFetchingNewRaw) "..." else "Tarik")
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Isi index.html:", style = MaterialTheme.typography.labelMedium)
                            TextButton(onClick = { singleFilePickerLauncher.launch("text/html") }) {
                                Text("📄 Pilih File HTML Mandiri")
                            }
                        }

                        OutlinedTextField(
                            value = htmlContent,
                            onValueChange = { htmlContent = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp),
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                        )

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
                                        val payload = mapOf(
                                            "name" to target,
                                            "production_branch" to "main"
                                        )
                                        val res = ApiClient.api.createPagesProject(accId, payload)
                                        if (res.isSuccessful && res.body()?.success == true) {
                                            statusMsg = "🎉 Berhasil membuat Pages: https://$target.pages.dev"
                                            newProjectName = ""
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
                            Text(if (isDeployingNew) "Mendaftarkan..." else "🚀 Buat Project Pages Sekarang")
                        }
                    }
                }
            }
        }

        // --- STATUS BOX ---
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

    if (showDeleteConfirm && projectToDelete.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Hapus Project Pages?") },
            text = { Text("Yakin ingin menghapus project '$projectToDelete' secara permanen dari Cloudflare?") },
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
                                if (selectedProjectForDomain == projectToDelete) selectedProjectForDomain = ""
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
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Batal")
                }
            }
        )
    }
}
