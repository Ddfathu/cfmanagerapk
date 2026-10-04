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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class CfPagesProjectItem(
    val name: String,
    val subdomain: String,
    val compatDate: String = "2024-01-01"
)

data class NodeLocDomainItem(
    val id: Long,
    val domainName: String,
    val status: String = "ACTIVE"
)

data class NodeLocDnsRecordItem(
    val id: String,
    val type: String,
    val name: String,
    val content: String,
    val ttl: Int = 300
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PagesNodeLocScreen() {
    val context = LocalContext.current
    val storage = remember { AccountStorage(context) }
    val accounts = remember { storage.getAccounts() }
    val activeIdx = remember { storage.getActiveIndex() }
    val currAcc = accounts.getOrNull(activeIdx)
    val email = AppConfig.activeEmail.ifBlank { currAcc?.email ?: "" }
    val apiKey = AppConfig.activeApiKey.ifBlank { currAcc?.apiKey ?: "" }

    var mainTab by remember { mutableStateOf(0) } // 0: CF Pages, 1: NodeLoc DNS
    val tabTitles = listOf("🚀 CF Pages", "🌐 NodeLoc DNS")

    // --- STATE CF PAGES ---
    var pagesProjects by remember { mutableStateOf<List<CfPagesProjectItem>>(emptyList()) }
    var isLoadingPages by remember { mutableStateOf(false) }

    // Dialog Create Pages Project
    var showCreateProjectDialog by remember { mutableStateOf(false) }
    var newProjectName by remember { mutableStateOf("") }
    var newCompatDate by remember { mutableStateOf("2024-01-01") }
    var checkSubdomainResult by remember { mutableStateOf("") }

    // Dialog Deploy Script
    var showDeployDialog by remember { mutableStateOf(false) }
    var targetDeployProject by remember { mutableStateOf("") }
    var rawWorkerScriptInput by remember { mutableStateOf("") }
    var rawHtmlInput by remember { mutableStateOf("<!DOCTYPE html><html><body><h1>Pages Active</h1></body></html>") }

    // Dialog Config Project
    var showConfigDialog by remember { mutableStateOf(false) }
    var targetConfigProject by remember { mutableStateOf("") }
    var configCompatDate by remember { mutableStateOf("2024-01-01") }
    var configNodejsCompat by remember { mutableStateOf(true) }
    var configEnvKey by remember { mutableStateOf("") }
    var configEnvVal by remember { mutableStateOf("") }

    // --- STATE NODELOC DNS ---
    var nodeLocTokenInput by remember { mutableStateOf("") }
    var nodeLocDomains by remember { mutableStateOf<List<NodeLocDomainItem>>(emptyList()) }
    var selectedNlDomain by remember { mutableStateOf<NodeLocDomainItem?>(null) }
    var nlDomainExpanded by remember { mutableStateOf(false) }

    var nlDnsRecords by remember { mutableStateOf<List<NodeLocDnsRecordItem>>(emptyList()) }
    var isLoadingNlDns by remember { mutableStateOf(false) }

    // Form Tambah DNS NodeLoc
    val nlRecordTypes = listOf("CNAME", "A", "TXT", "AAAA", "MX")
    var nlTypeInput by remember { mutableStateOf("CNAME") }
    var nlTypeExpanded by remember { mutableStateOf(false) }
    var nlNameInput by remember { mutableStateOf("") }
    var nlContentInput by remember { mutableStateOf("") }

    // Dialog Edit DNS NodeLoc
    var showEditNlDnsDialog by remember { mutableStateOf(false) }
    var editNlRecordId by remember { mutableStateOf("") }
    var editNlType by remember { mutableStateOf("CNAME") }
    var editNlTypeExpanded by remember { mutableStateOf(false) }
    var editNlName by remember { mutableStateOf("") }
    var editNlContent by remember { mutableStateOf("") }

    var statusMsg by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val gson = remember { Gson() }
    val httpClient = remember { OkHttpClient() }

    // --- HELPER FUNCTION: LOAD CF PAGES ---
    fun loadPagesProjects() {
        if (email.isBlank() || apiKey.isBlank()) return
        scope.launch {
            isLoadingPages = true
            try {
                val accId = CfAccountHelper.ensureAccountId()
                val req = Request.Builder()
                    .url("https://api.cloudflare.com/client/v4/accounts/$accId/pages/projects")
                    .header("X-Auth-Email", email)
                    .header("X-Auth-Key", apiKey)
                    .get()
                    .build()

                val resp = withContext(Dispatchers.IO) { httpClient.newCall(req).execute() }
                val respStr = resp.body?.string() ?: ""
                val json = gson.fromJson(respStr, JsonObject::class.java)

                if (json != null && json.get("success")?.asBoolean == true) {
                    val arr = json.getAsJsonArray("result") ?: JsonArray()
                    val list = mutableListOf<CfPagesProjectItem>()
                    arr.forEach { el ->
                        val obj = el.asJsonObject
                        val name = obj.get("name")?.asString ?: ""
                        val sub = obj.get("subdomain")?.asString ?: "$name.pages.dev"
                        if (name.isNotBlank()) {
                            list.add(CfPagesProjectItem(name, sub))
                        }
                    }
                    pagesProjects = list
                    statusMsg = "✅ Berhasil memuat ${list.size} project Pages."
                } else {
                    val err = json?.getAsJsonArray("errors")?.firstOrNull()?.asJsonObject?.get("message")?.asString ?: ("HTTP " + resp.code)
                    statusMsg = "❌ Gagal memuat Pages: $err"
                }
            } catch (e: Exception) {
                statusMsg = "❌ Error Pages: " + e.message
            } finally {
                isLoadingPages = false
            }
        }
    }

    // --- HELPER FUNCTION: LOAD NODELOC DOMAINS & RECORDS ---
    fun loadNodeLocDomains() {
        if (nodeLocTokenInput.isBlank()) {
            statusMsg = "⚠️ Masukkan Token JWT NodeLoc terlebih dahulu!"
            return
        }
        scope.launch {
            isProcessing = true
            try {
                val req = Request.Builder()
                    .url("https://domain.nodeloc.com/api/domains")
                    .header("Authorization", "Bearer $nodeLocTokenInput")
                    .get()
                    .build()

                val resp = withContext(Dispatchers.IO) { httpClient.newCall(req).execute() }
                val respStr = resp.body?.string() ?: ""
                val json = gson.fromJson(respStr, JsonObject::class.java)

                val arr = json?.getAsJsonArray("domains") ?: json?.getAsJsonArray("data") ?: JsonArray()
                val list = mutableListOf<NodeLocDomainItem>()
                arr.forEach { el ->
                    val obj = el.asJsonObject
                    val id = obj.get("id")?.asLong ?: 0L
                    val name = obj.get("full_domain")?.asString ?: obj.get("domain")?.asString ?: "Domain #$id"
                    val status = obj.get("status")?.asString ?: "ACTIVE"
                    if (id != 0L) list.add(NodeLocDomainItem(id, name, status))
                }
                nodeLocDomains = list
                if (list.isNotEmpty()) {
                    selectedNlDomain = list[0]
                }
                statusMsg = "✅ Berhasil memuat ${list.size} domain NodeLoc."
            } catch (e: Exception) {
                statusMsg = "❌ Error NodeLoc: " + e.message
            } finally {
                isProcessing = false
            }
        }
    }

    fun loadNodeLocRecords(domainId: Long) {
        if (nodeLocTokenInput.isBlank() || domainId == 0L) return
        scope.launch {
            isLoadingNlDns = true
            try {
                val req = Request.Builder()
                    .url("https://domain.nodeloc.com/api/dns/$domainId/records")
                    .header("Authorization", "Bearer $nodeLocTokenInput")
                    .get()
                    .build()

                val resp = withContext(Dispatchers.IO) { httpClient.newCall(req).execute() }
                val respStr = resp.body?.string() ?: ""
                val json = gson.fromJson(respStr, JsonObject::class.java)

                val arr = json?.getAsJsonArray("records") ?: json?.getAsJsonArray("data") ?: JsonArray()
                val list = mutableListOf<NodeLocDnsRecordItem>()
                arr.forEach { el ->
                    val obj = el.asJsonObject
                    val id = obj.get("id")?.asString ?: ""
                    val type = obj.get("type")?.asString ?: "CNAME"
                    val name = obj.get("name")?.asString ?: "@"
                    val content = obj.get("content")?.asString ?: obj.get("value")?.asString ?: ""
                    val ttl = obj.get("ttl")?.asInt ?: 300
                    if (id.isNotBlank()) list.add(NodeLocDnsRecordItem(id, type, name, content, ttl))
                }
                nlDnsRecords = list
            } catch (e: Exception) {
                statusMsg = "❌ Error load DNS NodeLoc: " + e.message
            } finally {
                isLoadingNlDns = false
            }
        }
    }

    LaunchedEffect(email, apiKey) {
        if (email.isNotEmpty() && apiKey.isNotEmpty()) {
            loadPagesProjects()
        }
    }

    LaunchedEffect(selectedNlDomain) {
        selectedNlDomain?.let { loadNodeLocRecords(it.id) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // --- HEADER UTAMA & SUB-TAB ---
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("⚡ Pages & NodeLoc Hub", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Kelola CF Pages & NodeLoc DNS Manager", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                IconButton(
                    onClick = {
                        if (mainTab == 0) loadPagesProjects()
                        else selectedNlDomain?.let { loadNodeLocRecords(it.id) }
                    },
                    enabled = !isLoadingPages && !isLoadingNlDns
                ) {
                    Text(if (isLoadingPages || isLoadingNlDns) "⏳" else "🔄")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            PrimaryTabRow(selectedTabIndex = mainTab) {
                tabTitles.forEachIndexed { idx, title ->
                    Tab(
                        selected = mainTab == idx,
                        onClick = { mainTab = idx },
                        text = { Text(title, fontWeight = FontWeight.SemiBold) }
                    )
                }
            }
        }

        // ==========================================
        // SUB-TAB 0: CLOUDFLARE PAGES
        // ==========================================
        if (mainTab == 0) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Daftar Project Pages (${pagesProjects.size}):", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = { showCreateProjectDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEA580C))
                    ) {
                        Text("+ Project", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (pagesProjects.isEmpty() && !isLoadingPages) {
                item {
                    Text("Belum ada project Pages di akun Cloudflare ini.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }

            items(pagesProjects) { p ->
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
                            Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            IconButton(onClick = {
                                scope.launch {
                                    statusMsg = "Menghapus project ${p.name}..."
                                    try {
                                        val accId = CfAccountHelper.ensureAccountId()
                                        val req = Request.Builder()
                                            .url("https://api.cloudflare.com/client/v4/accounts/$accId/pages/projects/${p.name}")
                                            .header("X-Auth-Email", email)
                                            .header("X-Auth-Key", apiKey)
                                            .delete()
                                            .build()

                                        val resp = withContext(Dispatchers.IO) { httpClient.newCall(req).execute() }
                                        if (resp.isSuccessful) {
                                            statusMsg = "🗑 Project ${p.name} dihapus!"
                                            loadPagesProjects()
                                        } else {
                                            statusMsg = "❌ Gagal hapus Pages"
                                        }
                                    } catch (e: Exception) {
                                        statusMsg = "Error: " + e.message
                                    }
                                }
                            }) {
                                Text("🗑")
                            }
                        }

                        Text("https://${p.subdomain}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFEA580C), fontFamily = FontFamily.Monospace)

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    targetDeployProject = p.name
                                    showDeployDialog = true
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("🚀 Deploy", style = MaterialTheme.typography.labelSmall)
                            }

                            OutlinedButton(
                                onClick = {
                                    targetConfigProject = p.name
                                    showConfigDialog = true
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("⚙️ Config", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }

        // ==========================================
        // SUB-TAB 1: NODELOC DNS MANAGER
        // ==========================================
        if (mainTab == 1) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("🔑 Autentikasi NodeLoc JWT", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = nodeLocTokenInput,
                                onValueChange = { nodeLocTokenInput = it.trim() },
                                label = { Text("JWT Token NodeLoc") },
                                placeholder = { Text("eyJhbGciOi...") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = { loadNodeLocDomains() },
                                enabled = !isProcessing && nodeLocTokenInput.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                            ) {
                                Text(if (isProcessing) "..." else "Load")
                            }
                        }
                    }
                }
            }

            if (nodeLocDomains.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("Pilih Domain NodeLoc:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(6.dp))

                            ExposedDropdownMenuBox(
                                expanded = nlDomainExpanded,
                                onExpandedChange = { nlDomainExpanded = !nlDomainExpanded }
                            ) {
                                OutlinedTextField(
                                    value = selectedNlDomain?.domainName ?: "Pilih Domain...",
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Domain Target") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = nlDomainExpanded) },
                                    modifier = Modifier.menuAnchor().fillMaxWidth()
                                )
                                ExposedDropdownMenu(
                                    expanded = nlDomainExpanded,
                                    onDismissRequest = { nlDomainExpanded = false }
                                ) {
                                    nodeLocDomains.forEach { d ->
                                        DropdownMenuItem(
                                            text = { Text("🌐 ${d.domainName}") },
                                            onClick = {
                                                selectedNlDomain = d
                                                nlDomainExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // FORM TAMBAH DNS NODELOC
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("➕ Tambah DNS Record ke NodeLoc", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ExposedDropdownMenuBox(
                                    expanded = nlTypeExpanded,
                                    onExpandedChange = { nlTypeExpanded = !nlTypeExpanded },
                                    modifier = Modifier.width(100.dp)
                                ) {
                                    OutlinedTextField(
                                        value = nlTypeInput,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("Tipe") },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = nlTypeExpanded) },
                                        modifier = Modifier.menuAnchor()
                                    )
                                    ExposedDropdownMenu(
                                        expanded = nlTypeExpanded,
                                        onDismissRequest = { nlTypeExpanded = false }
                                    ) {
                                        nlRecordTypes.forEach { t ->
                                            DropdownMenuItem(
                                                text = { Text(t, fontWeight = FontWeight.Bold) },
                                                onClick = {
                                                    nlTypeInput = t
                                                    nlTypeExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }

                                OutlinedTextField(
                                    value = nlNameInput,
                                    onValueChange = { nlNameInput = it.trim() },
                                    label = { Text("Host / Sub") },
                                    placeholder = { Text("v2ray atau @") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = nlContentInput,
                                onValueChange = { nlContentInput = it.trim() },
                                label = { Text("Content / Target IP / Pages URL") },
                                placeholder = { Text("103.x.x.x atau project.pages.dev") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Button(
                                onClick = {
                                    val domain = selectedNlDomain ?: return@Button
                                    if (nlNameInput.isBlank() || nlContentInput.isBlank()) {
                                        statusMsg = "⚠️️ Host dan Content wajib diisi!"
                                        return@Button
                                    }
                                    scope.launch {
                                        isProcessing = true
                                        statusMsg = "Mengirim DNS Record ke NodeLoc..."
                                        try {
                                            val payload = JsonObject().apply {
                                                addProperty("type", nlTypeInput)
                                                addProperty("name", nlNameInput)
                                                addProperty("content", nlContentInput)
                                                addProperty("ttl", 300)
                                            }

                                            val req = Request.Builder()
                                                .url("https://domain.nodeloc.com/api/dns/${domain.id}/records")
                                                .header("Authorization", "Bearer $nodeLocTokenInput")
                                                .header("Content-Type", "application/json")
                                                .post(payload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                                                .build()

                                            val resp = withContext(Dispatchers.IO) { httpClient.newCall(req).execute() }
                                            if (resp.isSuccessful) {
                                                statusMsg = "✅ Berhasil tambah DNS ke NodeLoc!"
                                                nlNameInput = ""
                                                nlContentInput = ""
                                                loadNodeLocRecords(domain.id)
                                            } else {
                                                statusMsg = "❌ Gagal tambah DNS NodeLoc"
                                            }
                                        } catch (e: Exception) {
                                            statusMsg = "Error: " + e.message
                                        } finally {
                                            isProcessing = false
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isProcessing && selectedNlDomain != null && nlNameInput.isNotBlank() && nlContentInput.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                            ) {
                                Text(if (isProcessing) "Mengirim..." else "Kirim DNS Record")
                            }
                        }
                    }
                }

                item {
                    Text("Daftar Record DNS NodeLoc (${nlDnsRecords.size}):", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }

                items(nlDnsRecords) { r ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("[${r.type}] ${r.name}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text(r.content, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }

                            Row {
                                IconButton(onClick = {
                                    editNlRecordId = r.id
                                    editNlType = r.type
                                    editNlName = r.name
                                    editNlContent = r.content
                                    showEditNlDnsDialog = true
                                }) {
                                    Text("✏️")
                                }

                                IconButton(onClick = {
                                    val domain = selectedNlDomain ?: return@IconButton
                                    scope.launch {
                                        statusMsg = "Menghapus DNS ${r.name}..."
                                        try {
                                            val req = Request.Builder()
                                                .url("https://domain.nodeloc.com/api/dns/${domain.id}/records/${r.id}")
                                                .header("Authorization", "Bearer $nodeLocTokenInput")
                                                .delete()
                                                .build()

                                            val resp = withContext(Dispatchers.IO) { httpClient.newCall(req).execute() }
                                            if (resp.isSuccessful) {
                                                statusMsg = "🗑 Record dihapus!"
                                                loadNodeLocRecords(domain.id)
                                            } else {
                                                statusMsg = "❌ Gagal hapus DNS NodeLoc"
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

    // --- POP-UP MODAL: BUAT PROJECT PAGES BARU ---
    if (showCreateProjectDialog) {
        AlertDialog(
            onDismissRequest = { if (!isProcessing) showCreateProjectDialog = false },
            title = { Text("➕ Buat Project Pages Baru", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newProjectName,
                            onValueChange = {
                                newProjectName = it.lowercase().trim()
                                checkSubdomainResult = "Target: ${newProjectName.ifBlank { "..." }}.pages.dev"
                            },
                            label = { Text("Nama Project") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = {
                            if (newProjectName.isBlank()) return@Button
                            scope.launch {
                                try {
                                    val targetDom = "$newProjectName.pages.dev"
                                    val req = Request.Builder()
                                        .url("https://1.1.1.1/dns-query?name=$targetDom&type=A")
                                        .header("Accept", "application/dns-json")
                                        .get()
                                        .build()

                                    val resp = withContext(Dispatchers.IO) { httpClient.newCall(req).execute() }
                                    val respStr = resp.body?.string() ?: ""
                                    val json = gson.fromJson(respStr, JsonObject::class.java)
                                    val status = json?.get("Status")?.asInt ?: -1

                                    checkSubdomainResult = if (status == 3) "🟢 $targetDom TERSEDIA!" else "🔴 $targetDom SUDAH TERPAKAI!"
                                } catch (e: Exception) {
                                    checkSubdomainResult = "Error cek: " + e.message
                                }
                            }
                        }) {
                            Text("Cek", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Text(checkSubdomainResult, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

                    OutlinedTextField(
                        value = newCompatDate,
                        onValueChange = { newCompatDate = it.trim() },
                        label = { Text("Compatibility Date") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newProjectName.isBlank()) return@Button
                        scope.launch {
                            isProcessing = true
                            statusMsg = "Membuat project Pages '$newProjectName'..."
                            try {
                                val accId = CfAccountHelper.ensureAccountId()
                                val payload = JsonObject().apply {
                                    addProperty("name", newProjectName)
                                    addProperty("production_branch", "main")
                                }

                                val req = Request.Builder()
                                    .url("https://api.cloudflare.com/client/v4/accounts/$accId/pages/projects")
                                    .header("X-Auth-Email", email)
                                    .header("X-Auth-Key", apiKey)
                                    .header("Content-Type", "application/json")
                                    .post(payload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                                    .build()

                                val resp = withContext(Dispatchers.IO) { httpClient.newCall(req).execute() }
                                val respStr = resp.body?.string() ?: ""
                                val json = gson.fromJson(respStr, JsonObject::class.java)

                                if (json != null && json.get("success")?.asBoolean == true) {
                                    statusMsg = "✅ Project Pages '$newProjectName' berhasil dibuat!"
                                    showCreateProjectDialog = false
                                    newProjectName = ""
                                    loadPagesProjects()
                                } else {
                                    val err = json?.getAsJsonArray("errors")?.firstOrNull()?.asJsonObject?.get("message")?.asString ?: ("HTTP " + resp.code)
                                    statusMsg = "Gagal: $err"
                                }
                            } catch (e: Exception) {
                                statusMsg = "Error: " + e.message
                            } finally {
                                isProcessing = false
                            }
                        }
                    },
                    enabled = !isProcessing && newProjectName.isNotBlank()
                ) {
                    Text(if (isProcessing) "Memproses..." else "Buat Project")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateProjectDialog = false }) { Text("Batal") }
            }
        )
    }

    // --- POP-UP MODAL: CONFIG PROJECT ---
    if (showConfigDialog) {
        AlertDialog(
            onDismissRequest = { if (!isProcessing) showConfigDialog = false },
            title = { Text("⚙️ Config Project: $targetConfigProject", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = configCompatDate,
                        onValueChange = { configCompatDate = it.trim() },
                        label = { Text("Compatibility Date") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = configNodejsCompat,
                            onCheckedChange = { configNodejsCompat = it }
                        )
                        Text("Aktifkan flag 'nodejs_compat'", style = MaterialTheme.typography.bodyMedium)
                    }

                    Text("Tambah Env Variable (Opsional):", style = MaterialTheme.typography.labelMedium)
                    OutlinedTextField(
                        value = configEnvKey,
                        onValueChange = { configEnvKey = it.trim() },
                        label = { Text("Env KEY") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = configEnvVal,
                        onValueChange = { configEnvVal = it },
                        label = { Text("Env VALUE") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            isProcessing = true
                            statusMsg = "Memperbarui config '$targetConfigProject'..."
                            try {
                                val accId = CfAccountHelper.ensureAccountId()
                                val flags = JsonArray().apply {
                                    if (configNodejsCompat) add("nodejs_compat")
                                }

                                val envObj = JsonObject()
                                if (configEnvKey.isNotBlank()) {
                                    val item = JsonObject().apply { addProperty("value", configEnvVal) }
                                    envObj.add(configEnvKey, item)
                                }

                                val prodObj = JsonObject().apply {
                                    addProperty("compatibility_date", configCompatDate)
                                    add("compatibility_flags", flags)
                                    if (configEnvKey.isNotBlank()) add("env_vars", envObj)
                                }

                                val payload = JsonObject().apply {
                                    add("deployment_configs", JsonObject().apply {
                                        add("production", prodObj)
                                    })
                                }

                                val req = Request.Builder()
                                    .url("https://api.cloudflare.com/client/v4/accounts/$accId/pages/projects/$targetConfigProject")
                                    .header("X-Auth-Email", email)
                                    .header("X-Auth-Key", apiKey)
                                    .header("Content-Type", "application/json")
                                    .patch(payload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                                    .build()

                                val resp = withContext(Dispatchers.IO) { httpClient.newCall(req).execute() }
                                if (resp.isSuccessful) {
                                    statusMsg = "✅ Config '$targetConfigProject' diperbarui!"
                                    showConfigDialog = false
                                } else {
                                    statusMsg = "❌ Gagal update config"
                                }
                            } catch (e: Exception) {
                                statusMsg = "Error: " + e.message
                            } finally {
                                isProcessing = false
                            }
                        }
                    },
                    enabled = !isProcessing
                ) {
                    Text(if (isProcessing) "Menyimpan..." else "Simpan Config")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfigDialog = false }) { Text("Batal") }
            }
        )
    }

    // --- POP-UP MODAL: EDIT DNS NODELOC ---
    if (showEditNlDnsDialog) {
        AlertDialog(
            onDismissRequest = { if (!isProcessing) showEditNlDnsDialog = false },
            title = { Text("✏️ Edit DNS NodeLoc", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExposedDropdownMenuBox(
                        expanded = editNlTypeExpanded,
                        onExpandedChange = { editNlTypeExpanded = !editNlTypeExpanded }
                    ) {
                        OutlinedTextField(
                            value = editNlType,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Tipe") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = editNlTypeExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = editNlTypeExpanded,
                            onDismissRequest = { editNlTypeExpanded = false }
                        ) {
                            nlRecordTypes.forEach { t ->
                                DropdownMenuItem(
                                    text = { Text(t, fontWeight = FontWeight.Bold) },
                                    onClick = {
                                        editNlType = t
                                        editNlTypeExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = editNlName,
                        onValueChange = { editNlName = it.trim() },
                        label = { Text("Host / Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = editNlContent,
                        onValueChange = { editNlContent = it.trim() },
                        label = { Text("Content / Target Value") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val domain = selectedNlDomain ?: return@Button
                        scope.launch {
                            isProcessing = true
                            statusMsg = "Memperbarui DNS NodeLoc..."
                            try {
                                val payload = JsonObject().apply {
                                    addProperty("type", editNlType)
                                    addProperty("name", editNlName)
                                    addProperty("content", editNlContent)
                                    addProperty("ttl", 300)
                                }

                                val req = Request.Builder()
                                    .url("https://domain.nodeloc.com/api/dns/${domain.id}/records/$editNlRecordId")
                                    .header("Authorization", "Bearer $nodeLocTokenInput")
                                    .header("Content-Type", "application/json")
                                    .put(payload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                                    .build()

                                val resp = withContext(Dispatchers.IO) { httpClient.newCall(req).execute() }
                                if (resp.isSuccessful) {
                                    statusMsg = "✅ DNS NodeLoc berhasil diperbarui!"
                                    showEditNlDnsDialog = false
                                    loadNodeLocRecords(domain.id)
                                } else {
                                    statusMsg = "❌ Gagal edit DNS NodeLoc"
                                }
                            } catch (e: Exception) {
                                statusMsg = "Error: " + e.message
                            } finally {
                                isProcessing = false
                            }
                        }
                    },
                    enabled = !isProcessing && editNlName.isNotBlank() && editNlContent.isNotBlank()
                ) {
                    Text(if (isProcessing) "Memproses..." else "Simpan")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditNlDnsDialog = false }) { Text("Batal") }
            }
        )
    }
}
