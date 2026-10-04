package com.cf.dfathu.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
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
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.Base64

data class CfPagesProjectItem(
    val name: String,
    val subdomain: String,
    val rawJsonObject: JsonObject
)

data class NodeLocDomainItem(
    val id: Long,
    val fullDomain: String,
    val status: String = "ACTIVE",
    val overallHealth: String = "-",
    val dnsStatus: String = "-",
    val httpStatus: String = "-",
    val sslStatus: String = "-",
    val uptime: String = "-",
    val regDate: String = "-",
    val expDate: String = "-"
)

data class NodeLocDnsRecordItem(
    val id: String,
    val type: String,
    val name: String,
    val content: String,
    val ttl: Int = 300
)

data class KvOptionItem(val id: String, val title: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PagesNodeLocScreen() {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
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

    // State Resources KV & R2
    var kvOptions by remember { mutableStateOf<List<KvOptionItem>>(emptyList()) }
    var r2Options by remember { mutableStateOf<List<String>>(emptyList()) }

    // Dialog Create Pages Project
    var showCreateProjectDialog by remember { mutableStateOf(false) }
    var newProjectName by remember { mutableStateOf("") }
    var newCompatDate by remember { mutableStateOf("2024-01-01") }
    var checkSubdomainResult by remember { mutableStateOf("") }

    // Dialog Deploy Studio
    var showDeployDialog by remember { mutableStateOf(false) }
    var targetDeployProject by remember { mutableStateOf("") }
    var rawFetchUrlInput by remember { mutableStateOf("") }
    var targetFetchInject by remember { mutableStateOf("worker") } // worker / html
    var rawWorkerScriptInput by remember { mutableStateOf("") }
    var rawHtmlInput by remember { mutableStateOf("<!DOCTYPE html>\n<html><body><h1>Pages Active</h1></body></html>") }

    // Dialog Config Project & Bindings
    var showConfigDialog by remember { mutableStateOf(false) }
    var targetConfigProject by remember { mutableStateOf("") }
    var configCompatDate by remember { mutableStateOf("2024-01-01") }
    var configNodejsCompat by remember { mutableStateOf(true) }
    var placementMode by remember { mutableStateOf("off") } // off, smart, manual
    var manualRegionCode by remember { mutableStateOf("ap-southeast-1") }
    var autoRedeployConfig by remember { mutableStateOf(true) }

    // Dynamic Lists buat Config
    val envList = remember { mutableStateListOf<Pair<String, String>>() }
    val kvBindingList = remember { mutableStateListOf<Pair<String, String>>() }
    val r2BindingList = remember { mutableStateListOf<Pair<String, String>>() }

    // Dialog Custom Domain Manager
    var showDomainModal by remember { mutableStateOf(false) }
    var targetDomainProject by remember { mutableStateOf("") }
    var targetDomainSubdomain by remember { mutableStateOf("") }
    var domainAddTab by remember { mutableStateOf("single") } // single / bulk
    var singleDomainInput by remember { mutableStateOf("") }
    var bulkDomainInput by remember { mutableStateOf("") }
    var autoSyncNodeLocCname by remember { mutableStateOf(true) }
    var pagesDomainList by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var domainSyncLog by remember { mutableStateOf("") }

    // --- STATE NODELOC DNS ---
    var nodeLocTokenInput by remember { mutableStateOf(storage.getAccounts().firstOrNull()?.apiKey ?: "") }
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

    fun openBrowserUrl(urlStr: String) {
        try {
            val fullUrl = if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) "https://$urlStr" else urlStr
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl))
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Gagal membuka URL: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // --- FETCH RESOURCES (KV & R2) ---
    fun fetchResources() {
        if (email.isBlank() || apiKey.isBlank()) return
        scope.launch {
            try {
                val accId = CfAccountHelper.ensureAccountId()
                // KV Namespaces
                val kvReq = Request.Builder()
                    .url("https://api.cloudflare.com/client/v4/accounts/$accId/storage/kv/namespaces?per_page=100")
                    .header("X-Auth-Email", email)
                    .header("X-Auth-Key", apiKey)
                    .get().build()
                val kvResp = withContext(Dispatchers.IO) { httpClient.newCall(kvReq).execute() }
                val kvJson = gson.fromJson(kvResp.body?.string() ?: "", JsonObject::class.java)
                val kvArr = kvJson?.getAsJsonArray("result") ?: JsonArray()
                val kvs = mutableListOf<KvOptionItem>()
                kvArr.forEach { el ->
                    val obj = el.asJsonObject
                    kvs.add(KvOptionItem(obj.get("id")?.asString ?: "", obj.get("title")?.asString ?: ""))
                }
                kvOptions = kvs

                // R2 Buckets
                val r2Req = Request.Builder()
                    .url("https://api.cloudflare.com/client/v4/accounts/$accId/r2/buckets")
                    .header("X-Auth-Email", email)
                    .header("X-Auth-Key", apiKey)
                    .get().build()
                val r2Resp = withContext(Dispatchers.IO) { httpClient.newCall(r2Req).execute() }
                val r2Json = gson.fromJson(r2Resp.body?.string() ?: "", JsonObject::class.java)
                val r2Arr = r2Json?.getAsJsonObject("result")?.getAsJsonArray("buckets") ?: r2Json?.getAsJsonArray("result") ?: JsonArray()
                val r2s = mutableListOf<String>()
                r2Arr.forEach { el ->
                    val name = if (el.isJsonObject) el.asJsonObject.get("name")?.asString ?: "" else el.asString
                    if (name.isNotBlank()) r2s.add(name)
                }
                r2Options = r2s
            } catch (_: Exception) {}
        }
    }

    // --- LOAD PAGES PROJECTS ---
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
                    .get().build()

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
                            list.add(CfPagesProjectItem(name, sub, obj))
                        }
                    }
                    pagesProjects = list
                    statusMsg = "✅ Memuat ${list.size} project Pages."
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

    // --- LOAD PAGES DOMAINS ---
    fun loadPagesDomains(projectName: String) {
        if (email.isBlank() || apiKey.isBlank()) return
        scope.launch {
            try {
                val accId = CfAccountHelper.ensureAccountId()
                val req = Request.Builder()
                    .url("https://api.cloudflare.com/client/v4/accounts/$accId/pages/projects/$projectName/domains")
                    .header("X-Auth-Email", email)
                    .header("X-Auth-Key", apiKey)
                    .get().build()

                val resp = withContext(Dispatchers.IO) { httpClient.newCall(req).execute() }
                val json = gson.fromJson(resp.body?.string() ?: "", JsonObject::class.java)
                val arr = json?.getAsJsonArray("result") ?: JsonArray()
                val list = mutableListOf<JsonObject>()
                arr.forEach { list.add(it.asJsonObject) }
                pagesDomainList = list
            } catch (_: Exception) {}
        }
    }

    // --- LOAD NODELOC DOMAINS & RECORDS ---
    fun loadNodeLocDomains() {
        if (nodeLocTokenInput.isBlank()) {
            statusMsg = "⚠️️ Masukkan Token JWT NodeLoc terlebih dahulu!"
            return
        }
        scope.launch {
            isProcessing = true
            try {
                val req = Request.Builder()
                    .url("https://domain.nodeloc.com/api/domains")
                    .header("Authorization", "Bearer $nodeLocTokenInput")
                    .get().build()

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
                    val scan = obj.getAsJsonObject("scan_summary")
                    val overall = scan?.get("overall_health")?.asString ?: "-"
                    val dnsS = scan?.get("dns_status")?.asString ?: "-"
                    val httpS = scan?.get("http_status")?.asString ?: "-"
                    val sslS = scan?.get("ssl_status")?.asString ?: "-"
                    val upt = scan?.get("uptime_percentage")?.asString ?: "-"
                    val regD = obj.get("registered_at")?.asString ?: "-"
                    val expD = obj.get("expires_at")?.asString ?: "-"

                    if (id != 0L) list.add(NodeLocDomainItem(id, name, status, overall, dnsS, httpS, sslS, upt, regD, expD))
                }
                nodeLocDomains = list
                if (list.isNotEmpty()) selectedNlDomain = list[0]
                statusMsg = "✅ Memuat ${list.size} domain NodeLoc."
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
                    .get().build()

                val resp = withContext(Dispatchers.IO) { httpClient.newCall(req).execute() }
                val json = gson.fromJson(resp.body?.string() ?: "", JsonObject::class.java)

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
            fetchResources()
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
                                            .delete().build()

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

                        // URL BISA DIKLIK & DISALIN
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "https://${p.subdomain}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFEA580C),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable { openBrowserUrl(p.subdomain) }
                                    .weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString("https://${p.subdomain}"))
                                    Toast.makeText(context, "URL Tersalin!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Text("📋", style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // TOMBOL FITUR LENGKAP
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Button(
                                onClick = {
                                    targetDeployProject = p.name
                                    showDeployDialog = true
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEA580C))
                            ) {
                                Text("🚀 Deploy", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = {
                                    targetConfigProject = p.name
                                    envList.clear()
                                    kvBindingList.clear()
                                    r2BindingList.clear()

                                    val prodCfg = p.rawJsonObject.getAsJsonObject("deployment_configs")?.getAsJsonObject("production")
                                    configCompatDate = prodCfg?.get("compatibility_date")?.asString ?: "2024-01-01"

                                    val flags = prodCfg?.getAsJsonArray("compatibility_flags")
                                    configNodejsCompat = flags?.any { it.asString == "nodejs_compat" } == true

                                    val envs = prodCfg?.getAsJsonObject("env_vars")
                                    envs?.keySet()?.forEach { k ->
                                        val v = envs.getAsJsonObject(k)?.get("value")?.asString ?: ""
                                        envList.add(Pair(k, v))
                                    }

                                    val kvs = prodCfg?.getAsJsonObject("kv_namespaces")
                                    kvs?.keySet()?.forEach { k ->
                                        val nsId = kvs.getAsJsonObject(k)?.get("namespace_id")?.asString ?: ""
                                        kvBindingList.add(Pair(k, nsId))
                                    }

                                    val r2s = prodCfg?.getAsJsonObject("r2_buckets")
                                    r2s?.keySet()?.forEach { k ->
                                        val bName = r2s.getAsJsonObject(k)?.get("name")?.asString ?: ""
                                        r2BindingList.add(Pair(k, bName))
                                    }

                                    showConfigDialog = true
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("⚙️ Config", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = {
                                    targetDomainProject = p.name
                                    targetDomainSubdomain = p.subdomain
                                    loadPagesDomains(p.name)
                                    showDomainModal = true
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("🌐 Domain", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
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
                                    value = selectedNlDomain?.fullDomain ?: "Pilih Domain...",
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
                                            text = { Text("🌐 ${d.fullDomain}") },
                                            onClick = {
                                                selectedNlDomain = d
                                                nlDomainExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            // NODELOC HEALTH INSPECTOR CARD
                            selectedNlDomain?.let { d ->
                                Spacer(modifier = Modifier.height(10.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surface
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("Status: ${d.status}", fontWeight = FontWeight.Bold, color = Color(0xFF16A34A), style = MaterialTheme.typography.bodySmall)
                                            Text("Health: ${d.overallHealth}", fontWeight = FontWeight.Bold, color = Color(0xFF0284C7), style = MaterialTheme.typography.bodySmall)
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("DNS: ${d.dnsStatus} | HTTP: ${d.httpStatus} | SSL: ${d.sslStatus}", style = MaterialTheme.typography.labelSmall)
                                        Text("Uptime: ${d.uptime}%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF0284C7))
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
                                        statusMsg = "⚠ Host dan Content wajib diisi!"
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
                    val fullUrl = if (r.name == "@" || r.name.isBlank()) selectedNlDomain?.fullDomain ?: "" else "${r.name}.${selectedNlDomain?.fullDomain}"
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = fullUrl,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF0284C7),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.clickable { openBrowserUrl(fullUrl) }
                                    )
                                    IconButton(
                                        onClick = {
                                            clipboardManager.setText(AnnotatedString("https://$fullUrl"))
                                            Toast.makeText(context, "Domain Tersalin!", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(20.dp)
                                    ) {
                                        Text("📋", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                Text("[${r.type}] Target: ${r.content}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
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
                                                .delete().build()

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

    // --- POP-UP MODAL: DEPLOY STUDIO (100% PROSES LENGKAP) ---
    if (showDeployDialog) {
        AlertDialog(
            onDismissRequest = { if (!isProcessing) showDeployDialog = false },
            title = { Text("🚀 Deploy Studio: $targetDeployProject", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Fetch Script dari Raw URL (GitHub / Pastebin):", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedTextField(
                            value = rawFetchUrlInput,
                            onValueChange = { rawFetchUrlInput = it.trim() },
                            label = { Text("URL Raw Script") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = {
                            if (rawFetchUrlInput.isBlank()) return@Button
                            scope.launch {
                                isProcessing = true
                                statusMsg = "Mengambil raw script..."
                                try {
                                    val req = Request.Builder().url(rawFetchUrlInput).get().build()
                                    val resp = withContext(Dispatchers.IO) { httpClient.newCall(req).execute() }
                                    val scriptText = resp.body?.string() ?: ""
                                    if (targetFetchInject == "worker") {
                                        rawWorkerScriptInput = scriptText
                                    } else {
                                        rawHtmlInput = scriptText
                                    }
                                    statusMsg = "✅ Script berhasil dimasukkan ke editor!"
                                } catch (e: Exception) {
                                    statusMsg = "Gagal fetch raw: " + e.message
                                } finally {
                                    isProcessing = false
                                }
                            }
                        }) {
                            Text("Fetch", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Text("⚡ _worker.js (Backend Script):", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF16A34A))
                    OutlinedTextField(
                        value = rawWorkerScriptInput,
                        onValueChange = { rawWorkerScriptInput = it },
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = MaterialTheme.typography.bodySmall.fontSize)
                    )

                    Text("🌐 index.html (Frontend Asset):", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF0284C7))
                    OutlinedTextField(
                        value = rawHtmlInput,
                        onValueChange = { rawHtmlInput = it },
                        modifier = Modifier.fillMaxWidth().height(90.dp),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = MaterialTheme.typography.bodySmall.fontSize)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            isProcessing = true
                            statusMsg = "Memulai alur deployment ke Pages..."
                            try {
                                val accId = CfAccountHelper.ensureAccountId()
                                // 1. Ambil Upload Token JWT
                                val tokenReq = Request.Builder()
                                    .url("https://api.cloudflare.com/client/v4/accounts/$accId/pages/projects/$targetDeployProject/upload-token")
                                    .header("X-Auth-Email", email)
                                    .header("X-Auth-Key", apiKey)
                                    .get().build()
                                val tokenResp = withContext(Dispatchers.IO) { httpClient.newCall(tokenReq).execute() }
                                val tokenJson = gson.fromJson(tokenResp.body?.string() ?: "", JsonObject::class.java)
                                val jwt = tokenJson?.getAsJsonObject("result")?.get("jwt")?.asString

                                if (jwt.isNullOrBlank()) {
                                    statusMsg = "❌ Gagal generate upload token"
                                    return@launch
                                }

                                // 2. Bikin Hash HTML Asset
                                val htmlBytes = rawHtmlInput.toByteArray()
                                val md = MessageDigest.getInstance("MD5")
                                val md5Bytes = md.digest(htmlBytes)
                                val hashHex = md5Bytes.joinToString("") { "%02x".format(it) }

                                val manifestObj = JsonObject().apply { addProperty("/index.html", hashHex) }
                                val hashesArray = JsonArray().apply { add(hashHex) }

                                // 3. Check Missing Assets
                                val checkReq = Request.Builder()
                                    .url("https://api.cloudflare.com/client/v4/pages/assets/check-missing")
                                    .header("Authorization", "Bearer $jwt")
                                    .header("Content-Type", "application/json")
                                    .post(JsonObject().apply { add("hashes", hashesArray) }.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                                    .build()

                                val checkResp = withContext(Dispatchers.IO) { httpClient.newCall(checkReq).execute() }
                                val checkJson = gson.fromJson(checkResp.body?.string() ?: "", JsonObject::class.java)
                                val missingHashes = checkJson?.getAsJsonArray("result") ?: JsonArray()

                                // 4. Upload Missing Assets
                                if (missingHashes.size() > 0) {
                                    val b64Value = Base64.getEncoder().encodeToString(htmlBytes)
                                    val uploadItem = JsonObject().apply {
                                        addProperty("key", hashHex)
                                        addProperty("value", b64Value)
                                        add("metadata", JsonObject().apply { addProperty("contentType", "text/html; charset=utf-8") })
                                        addProperty("base64", true)
                                    }
                                    val uploadArray = JsonArray().apply { add(uploadItem) }

                                    val upReq = Request.Builder()
                                        .url("https://api.cloudflare.com/client/v4/pages/assets/upload")
                                        .header("Authorization", "Bearer $jwt")
                                        .header("Content-Type", "application/json")
                                        .post(uploadArray.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                                        .build()
                                    withContext(Dispatchers.IO) { httpClient.newCall(upReq).execute() }

                                    val upsertReq = Request.Builder()
                                        .url("https://api.cloudflare.com/client/v4/pages/assets/upsert-hashes")
                                        .header("Authorization", "Bearer $jwt")
                                        .header("Content-Type", "application/json")
                                        .post(JsonObject().apply { add("hashes", hashesArray) }.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                                        .build()
                                    withContext(Dispatchers.IO) { httpClient.newCall(upsertReq).execute() }
                                }

                                // 5. Deploy via Multipart Form Data
                                val formBodyBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
                                    .addFormDataPart("manifest", manifestObj.toString())

                                if (rawWorkerScriptInput.isNotBlank()) {
                                    formBodyBuilder.addFormDataPart(
                                        "_worker.js",
                                        "_worker.js",
                                        rawWorkerScriptInput.toByteArray().toRequestBody("application/javascript".toMediaTypeOrNull())
                                    )
                                }

                                val deployReq = Request.Builder()
                                    .url("https://api.cloudflare.com/client/v4/accounts/$accId/pages/projects/$targetDeployProject/deployments")
                                    .header("X-Auth-Email", email)
                                    .header("X-Auth-Key", apiKey)
                                    .post(formBodyBuilder.build())
                                    .build()

                                val deployResp = withContext(Dispatchers.IO) { httpClient.newCall(deployReq).execute() }
                                val deployJson = gson.fromJson(deployResp.body?.string() ?: "", JsonObject::class.java)

                                if (deployJson != null && deployJson.get("success")?.asBoolean == true) {
                                    statusMsg = "🚀 DEPLOY BERHASIL! Script & Frontend aktif."
                                    showDeployDialog = false
                                    loadPagesProjects()
                                } else {
                                    statusMsg = "❌ Deploy gagal: " + deployResp.code
                                }
                            } catch (e: Exception) {
                                statusMsg = "Error Deploy: " + e.message
                            } finally {
                                isProcessing = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEA580C)),
                    enabled = !isProcessing
                ) {
                    Text(if (isProcessing) "Deploying..." else "🚀 Deploy Sekarang")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeployDialog = false }) { Text("Batal") }
            }
        )
    }

    // --- POP-UP MODAL: CONFIG & BINDINGS ---
    if (showConfigDialog) {
        AlertDialog(
            onDismissRequest = { if (!isProcessing) showConfigDialog = false },
            title = { Text("⚙️ Config Project: $targetConfigProject", fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth().height(350.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        OutlinedTextField(
                            value = configCompatDate,
                            onValueChange = { configCompatDate = it.trim() },
                            label = { Text("Compatibility Date") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = configNodejsCompat, onCheckedChange = { configNodejsCompat = it })
                            Text("Aktifkan flag 'nodejs_compat'", style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    // ENV VARIABLES
                    item {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("🔑 Environment Variables:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            Text("+ Tambah Baris", color = Color(0xFFEA580C), style = MaterialTheme.typography.labelSmall, modifier = Modifier.clickable { envList.add(Pair("", "")) })
                        }
                    }
                    items(envList.size) { idx ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedTextField(
                                value = envList[idx].first,
                                onValueChange = { envList[idx] = envList[idx].copy(first = it) },
                                placeholder = { Text("KEY") }, singleLine = true, modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = envList[idx].second,
                                onValueChange = { envList[idx] = envList[idx].copy(second = it) },
                                placeholder = { Text("VALUE") }, singleLine = true, modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { envList.removeAt(idx) }) { Text("❌") }
                        }
                    }

                    // KV BINDINGS
                    item {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("📦 KV Namespaces:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            Text("+ Tambah KV", color = Color(0xFFEA580C), style = MaterialTheme.typography.labelSmall, modifier = Modifier.clickable { kvBindingList.add(Pair("", "")) })
                        }
                    }
                    items(kvBindingList.size) { idx ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedTextField(
                                value = kvBindingList[idx].first,
                                onValueChange = { kvBindingList[idx] = kvBindingList[idx].copy(first = it) },
                                placeholder = { Text("BINDING_NAME") }, singleLine = true, modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = kvBindingList[idx].second,
                                onValueChange = { kvBindingList[idx] = kvBindingList[idx].copy(second = it) },
                                placeholder = { Text("KV Namespace ID") }, singleLine = true, modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { kvBindingList.removeAt(idx) }) { Text("❌") }
                        }
                    }

                    // R2 BINDINGS
                    item {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("🪣 R2 Buckets:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            Text("+ Tambah R2", color = Color(0xFFEA580C), style = MaterialTheme.typography.labelSmall, modifier = Modifier.clickable { r2BindingList.add(Pair("", "")) })
                        }
                    }
                    items(r2BindingList.size) { idx ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedTextField(
                                value = r2BindingList[idx].first,
                                onValueChange = { r2BindingList[idx] = r2BindingList[idx].copy(first = it) },
                                placeholder = { Text("BINDING_NAME") }, singleLine = true, modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = r2BindingList[idx].second,
                                onValueChange = { r2BindingList[idx] = r2BindingList[idx].copy(second = it) },
                                placeholder = { Text("Bucket Name") }, singleLine = true, modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { r2BindingList.removeAt(idx) }) { Text("❌") }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            isProcessing = true
                            statusMsg = "Memperbarui config & bindings..."
                            try {
                                val accId = CfAccountHelper.ensureAccountId()

                                val envObj = JsonObject()
                                envList.forEach { p ->
                                    if (p.first.isNotBlank()) envObj.add(p.first, JsonObject().apply { addProperty("value", p.second) })
                                }

                                val kvObj = JsonObject()
                                kvBindingList.forEach { p ->
                                    if (p.first.isNotBlank()) kvObj.add(p.first, JsonObject().apply { addProperty("namespace_id", p.second) })
                                }

                                val r2Obj = JsonObject()
                                r2BindingList.forEach { p ->
                                    if (p.first.isNotBlank()) r2Obj.add(p.first, JsonObject().apply { addProperty("name", p.second) })
                                }

                                val flags = JsonArray().apply { if (configNodejsCompat) add("nodejs_compat") }

                                val prodObj = JsonObject().apply {
                                    addProperty("compatibility_date", configCompatDate)
                                    add("compatibility_flags", flags)
                                    add("env_vars", envObj)
                                    add("kv_namespaces", kvObj)
                                    add("r2_buckets", r2Obj)
                                }

                                val payload = JsonObject().apply {
                                    add("deployment_configs", JsonObject().apply {
                                        add("production", prodObj)
                                        add("preview", prodObj)
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
                                    statusMsg = "✅ Config & Bindings berhasil disimpan!"
                                    showConfigDialog = false
                                    loadPagesProjects()
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
                    Text(if (isProcessing) "Saving..." else "Simpan & Terapkan")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfigDialog = false }) { Text("Batal") }
            }
        )
    }

    // --- POP-UP MODAL: CUSTOM DOMAIN MANAGER & AUTO NODELOC SYNC ---
    if (showDomainModal) {
        AlertDialog(
            onDismissRequest = { showDomainModal = false },
            title = { Text("🌐 Custom Domain: $targetDomainProject", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = { domainAddTab = "single" },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = if (domainAddTab == "single") Color(0xFFEA580C) else Color.Gray)
                        ) { Text("1 Domain", style = MaterialTheme.typography.labelSmall) }

                        Button(
                            onClick = { domainAddTab = "bulk" },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = if (domainAddTab == "bulk") Color(0xFFEA580C) else Color.Gray)
                        ) { Text("⚡ Bulk Massal", style = MaterialTheme.typography.labelSmall) }
                    }

                    if (domainAddTab == "single") {
                        OutlinedTextField(
                            value = singleDomainInput,
                            onValueChange = { singleDomainInput = it.trim() },
                            label = { Text("Nama Domain / Subdomain") },
                            placeholder = { Text("vpn.domain.com") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        OutlinedTextField(
                            value = bulkDomainInput,
                            onValueChange = { bulkDomainInput = it },
                            label = { Text("Multi-line Domains (1 Per Baris)") },
                            placeholder = { Text("v1.domain.com\nv2.domain.com") },
                            modifier = Modifier.fillMaxWidth().height(90.dp)
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = autoSyncNodeLocCname, onCheckedChange = { autoSyncNodeLocCname = it })
                        Text("⚡ Auto Add CNAME Record ke NodeLoc", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color(0xFF0284C7))
                    }

                    if (domainSyncLog.isNotBlank()) {
                        Text(domainSyncLog, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = Color(0xFF16A34A))
                    }

                    Text("Domain Terpasang:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(100.dp)) {
                        items(pagesDomainList) { dObj ->
                            val dName = dObj.get("name")?.asString ?: ""
                            val dStatus = dObj.get("status")?.asString ?: "pending"
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(dName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                Text(dStatus, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val dList = if (domainAddTab == "single") listOf(singleDomainInput) else bulkDomainInput.split("\n").map { it.trim() }.filter { it.isNotBlank() }
                        if (dList.isEmpty()) return@Button

                        scope.launch {
                            isProcessing = true
                            var logText = ""
                            try {
                                val accId = CfAccountHelper.ensureAccountId()
                                dList.forEach { dom ->
                                    val payload = JsonObject().apply { addProperty("name", dom) }
                                    val req = Request.Builder()
                                        .url("https://api.cloudflare.com/client/v4/accounts/$accId/pages/projects/$targetDomainProject/domains")
                                        .header("X-Auth-Email", email)
                                        .header("X-Auth-Key", apiKey)
                                        .header("Content-Type", "application/json")
                                        .post(payload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                                        .build()

                                    val resp = withContext(Dispatchers.IO) { httpClient.newCall(req).execute() }
                                    if (resp.isSuccessful) {
                                        logText += "✅ Pages: $dom\n"

                                        // Auto NodeLoc Sync CNAME
                                        if (autoSyncNodeLocCname && nodeLocTokenInput.isNotBlank() && selectedNlDomain != null) {
                                            val subHost = dom.replace(".${selectedNlDomain?.fullDomain}", "")
                                            val nlPayload = JsonObject().apply {
                                                addProperty("type", "CNAME")
                                                addProperty("name", subHost)
                                                addProperty("content", targetDomainSubdomain)
                                                addProperty("ttl", 300)
                                            }
                                            val nlReq = Request.Builder()
                                                .url("https://domain.nodeloc.com/api/dns/${selectedNlDomain?.id}/records")
                                                .header("Authorization", "Bearer $nodeLocTokenInput")
                                                .header("Content-Type", "application/json")
                                                .post(nlPayload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                                                .build()
                                            withContext(Dispatchers.IO) { httpClient.newCall(nlReq).execute() }
                                            logText += "🟢 NodeLoc CNAME: $subHost OK\n"
                                        }
                                    }
                                }
                                domainSyncLog = logText
                                singleDomainInput = ""
                                bulkDomainInput = ""
                                loadPagesDomains(targetDomainProject)
                            } catch (e: Exception) {
                                domainSyncLog = "Error: " + e.message
                            } finally {
                                isProcessing = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEA580C)),
                    enabled = !isProcessing
                ) {
                    Text(if (isProcessing) "Proses..." else "Proses Domain")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDomainModal = false }) { Text("Tutup") }
            }
        )
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
                                        .get().build()

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
