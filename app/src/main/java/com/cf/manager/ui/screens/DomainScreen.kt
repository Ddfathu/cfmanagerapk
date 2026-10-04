package com.cf.manager.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import com.cf.manager.data.model.ZoneItem
import kotlinx.coroutines.launch

data class ZoneUiModel(
    val id: String,
    val name: String,
    val status: String,
    val nameServers: List<String> = emptyList()
)

data class CustomWorkerDomainUi(val id: String, val hostname: String, val service: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DomainScreen() {
    val context = LocalContext.current
    val storage = remember { AccountStorage(context) }
    val accounts = remember { storage.getAccounts() }
    val activeIdx = remember { storage.getActiveIndex() }
    val currAcc = accounts.getOrNull(activeIdx)
    val email = AppConfig.activeEmail.ifBlank { currAcc?.email ?: "" }
    val apiKey = AppConfig.activeApiKey.ifBlank { currAcc?.apiKey ?: "" }

    var selectedTab by remember { mutableStateOf(0) }
    val tabTitles = listOf("🌐 Domain Utama (Zones)", "⚡ Rute Domain Worker", "⚙️ Subdomain Akun")

    // State Zones
    var zones by remember { mutableStateOf<List<ZoneUiModel>>(emptyList()) }
    var isLoadingZones by remember { mutableStateOf(false) }
    var newDomainInput by remember { mutableStateOf("") }
    var isAddingDomain by remember { mutableStateOf(false) }
    var newlyAssignedNameservers by remember { mutableStateOf<List<String>>(emptyList()) }
    var showDeleteZoneDialog by remember { mutableStateOf(false) }
    var zoneToDelete by remember { mutableStateOf<ZoneUiModel?>(null) }

    // State Worker Domains (Rute ke Worker)
    var workers by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedWorkerForRoute by remember { mutableStateOf("") }
    var workerRouteDropdownExpanded by remember { mutableStateOf(false) }
    var selectedMainZoneForRoute by remember { mutableStateOf<ZoneUiModel?>(null) }
    var mainZoneDropdownExpanded by remember { mutableStateOf(false) }
    var routeSubdomainInput by remember { mutableStateOf("") }
    var customDomainsList by remember { mutableStateOf<List<CustomWorkerDomainUi>>(emptyList()) }
    var isLoadingCustomDomains by remember { mutableStateOf(false) }
    var isAddingWorkerDomain by remember { mutableStateOf(false) }

    // State Subdomain Akun (*.workers.dev)
    var accountSubdomain by remember { mutableStateOf("") }
    var newSubdomainInput by remember { mutableStateOf("") }
    var isUpdatingSubdomain by remember { mutableStateOf(false) }

    var statusMsg by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

    fun copyToClipboard(text: String, label: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        statusMsg = "📋 " + label + " disalin: " + text
    }

    fun cleanApexDomain(raw: String): String {
        var clean = raw.trim().lowercase()
        clean = clean.removePrefix("http://").removePrefix("https://")
        clean = clean.split("/")[0]
        if (clean.startsWith("www.")) {
            clean = clean.removePrefix("www.")
        }
        return clean
    }

    fun loadZones() {
        if (email.isBlank() || apiKey.isBlank()) {
            statusMsg = "⚠️️ Isi Email & API Key di tab Akun terlebih dahulu!"
            return
        }
        scope.launch {
            isLoadingZones = true
            try {
                val res = ApiClient.api.listZones()
                if (res.isSuccessful && res.body()?.success == true) {
                    val rawList = res.body()?.result ?: emptyList()
                    val parsed = rawList.map { z ->
                        ZoneUiModel(
                            id = z.id,
                            name = z.name,
                            status = z.status,
                            nameServers = emptyList()
                        )
                    }
                    zones = parsed
                    if (selectedMainZoneForRoute == null && parsed.isNotEmpty()) {
                        selectedMainZoneForRoute = parsed[0]
                    }
                } else {
                    val err = res.body()?.errors?.firstOrNull()?.message ?: ("HTTP " + res.code())
                    statusMsg = "Gagal memuat domain: " + err
                }
            } catch (e: Exception) {
                statusMsg = "Error: " + e.message
            } finally {
                isLoadingZones = false
            }
        }
    }

    fun loadWorkerDomainsAndList() {
        scope.launch {
            isLoadingCustomDomains = true
            try {
                val accId = CfAccountHelper.ensureAccountId()
                
                val wRes = ApiClient.api.listWorkers(accId)
                if (wRes.isSuccessful && wRes.body()?.success == true) {
                    val wList = wRes.body()?.result?.mapNotNull { it.get("id")?.asString } ?: emptyList()
                    workers = wList
                    if (selectedWorkerForRoute.isBlank() && wList.isNotEmpty()) {
                        selectedWorkerForRoute = wList[0]
                    }
                }

                val dRes = ApiClient.api.listWorkerDomains(accId)
                if (dRes.isSuccessful && dRes.body()?.success == true) {
                    val raw = dRes.body()?.result ?: emptyList()
                    val parsed = raw.mapNotNull { obj ->
                        val id = obj.get("id")?.asString ?: return@mapNotNull null
                        val host = obj.get("hostname")?.asString ?: ""
                        val srv = obj.get("service")?.asString ?: ""
                        CustomWorkerDomainUi(id, host, srv)
                    }
                    customDomainsList = parsed
                }
            } catch (_: Exception) {} finally {
                isLoadingCustomDomains = false
            }
        }
    }

    fun loadAccountSubdomain() {
        scope.launch {
            try {
                val accId = CfAccountHelper.ensureAccountId()
                val subRes = ApiClient.api.getAccountSubdomain(accId)
                if (subRes.isSuccessful && subRes.body()?.success == true) {
                    val subObj = subRes.body()?.result?.asJsonObject
                    val sub = subObj?.get("subdomain")?.asString ?: ""
                    accountSubdomain = sub
                    if (newSubdomainInput.isBlank()) newSubdomainInput = sub
                }
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(email, apiKey) {
        if (email.isNotEmpty() && apiKey.isNotEmpty()) {
            loadZones()
            loadWorkerDomainsAndList()
            loadAccountSubdomain()
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
                    Text("🌐 Manajemen Domain & Rute", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Kelola Zone, Custom Domain Worker & Subdomain", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                IconButton(
                    onClick = {
                        loadZones()
                        loadWorkerDomainsAndList()
                        loadAccountSubdomain()
                    },
                    enabled = !isLoadingZones && !isLoadingCustomDomains
                ) {
                    Text(if (isLoadingZones || isLoadingCustomDomains) "⏳" else "🔄")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            PrimaryTabRow(selectedTabIndex = selectedTab) {
                tabTitles.forEachIndexed { idx, title ->
                    Tab(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx },
                        text = { Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall) }
                    )
                }
            }
        }

        // ==================== SUB-TAB 0: DOMAIN UTAMA (ZONES) ====================
        if (selectedTab == 0) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("➕ Daftarkan Domain Baru (Add Zone)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("Masukkan nama root/apex domain (tanpa 'www' atau 'http')", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = newDomainInput,
                                onValueChange = { newDomainInput = it },
                                label = { Text("Nama Domain") },
                                placeholder = { Text("contoh: akuini.com") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    val target = cleanApexDomain(newDomainInput)
                                    if (target.isBlank() || !target.contains(".")) {
                                        statusMsg = "⚠️ Masukkan format domain yang benar (contoh: domainku.com, bukan www)"
                                        return@Button
                                    }
                                    scope.launch {
                                        isAddingDomain = true
                                        statusMsg = "Mendaftarkan domain " + target + " ke Cloudflare..."
                                        try {
                                            val accId = CfAccountHelper.ensureAccountId()
                                            val payload = mapOf(
                                                "name" to target,
                                                "account" to mapOf("id" to accId),
                                                "type" to "full"
                                            )
                                            val res = ApiClient.api.createZone(payload)
                                            if (res.isSuccessful && res.body()?.success == true) {
                                                val resObj = res.body()?.result?.asJsonObject
                                                val nsArr = resObj?.getAsJsonArray("name_servers")
                                                val nsList = nsArr?.map { it.asString } ?: emptyList()

                                                newlyAssignedNameservers = nsList
                                                statusMsg = "🎉 Domain '" + target + "' berhasil didaftarkan!"
                                                newDomainInput = ""
                                                loadZones()
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
                                enabled = !isAddingDomain && newDomainInput.isNotBlank()
                            ) {
                                Text(if (isAddingDomain) "..." else "Tambah")
                            }
                        }
                    }
                }
            }

            if (newlyAssignedNameservers.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("⚠️ Arahkan Domain ke Nameservers Cloudflare Ini:", fontWeight = FontWeight.Bold, color = Color(0xFF92400E))
                            Spacer(modifier = Modifier.height(6.dp))
                            newlyAssignedNameservers.forEachIndexed { i, ns ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text((i + 1).toString() + ". " + ns, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color(0xFF1E293B))
                                    TextButton(onClick = { copyToClipboard(ns, "Nameserver") }) {
                                        Text("Salin")
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("Ganti nameserver di registrar domain kamu ke 2 alamat di atas agar domain aktif.", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB45309))
                        }
                    }
                }
            }

            item {
                Text("Domain Terdaftar (" + zones.size + "):", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            if (zones.isEmpty() && !isLoadingZones) {
                item {
                    Text("Belum ada domain terdaftar di akun Cloudflare ini.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }

            items(zones) { z ->
                val isActive = z.status.equals("active", ignoreCase = true)
                val statusBadgeBg = if (isActive) Color(0xFFDCFCE7) else Color(0xFFFEF3C7)
                val statusBadgeText = if (isActive) Color(0xFF166534) else Color(0xFF92400E)

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🌐 " + z.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Surface(shape = RoundedCornerShape(16.dp), color = statusBadgeBg) {
                                Text(
                                    text = if (isActive) "🟢 Aktif" else "⚠️ " + z.status.uppercase(),
                                    color = statusBadgeText,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        if (z.nameServers.isNotEmpty()) {
                            Text("Nameservers Cloudflare:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (ns in z.nameServers) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        modifier = Modifier.padding(top = 2.dp)
                                    ) {
                                        Text(
                                            text = ns,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                        }

                        Text("Zone ID: " + z.id, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.outline)

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { copyToClipboard(z.id, "Zone ID") }) {
                                Text("Salin ID")
                            }

                            Button(
                                onClick = {
                                    zoneToDelete = z
                                    showDeleteZoneDialog = true
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text("🗑 Hapus")
                            }
                        }
                    }
                }
            }
        }

        // ==================== SUB-TAB 1: RUTE DOMAIN KE WORKER ====================
        if (selectedTab == 1) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("🔗 Hubungkan Custom Domain ke Worker", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("Buat rute domain atau subdomain khusus langsung ke script worker", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        Spacer(modifier = Modifier.height(10.dp))

                        ExposedDropdownMenuBox(
                            expanded = workerRouteDropdownExpanded,
                            onExpandedChange = { workerRouteDropdownExpanded = !workerRouteDropdownExpanded }
                        ) {
                            OutlinedTextField(
                                value = selectedWorkerForRoute.ifBlank { "Pilih Worker Target" },
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Worker Tujuan") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = workerRouteDropdownExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = workerRouteDropdownExpanded,
                                onDismissRequest = { workerRouteDropdownExpanded = false }
                            ) {
                                workers.forEach { w ->
                                    DropdownMenuItem(
                                        text = { Text("⚡ " + w) },
                                        onClick = {
                                            selectedWorkerForRoute = w
                                            workerRouteDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        ExposedDropdownMenuBox(
                            expanded = mainZoneDropdownExpanded,
                            onExpandedChange = { mainZoneDropdownExpanded = !mainZoneDropdownExpanded }
                        ) {
                            OutlinedTextField(
                                value = selectedMainZoneForRoute?.name ?: "Pilih Domain Utama",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Domain Utama CF (Zone)") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = mainZoneDropdownExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = mainZoneDropdownExpanded,
                                onDismissRequest = { mainZoneDropdownExpanded = false }
                            ) {
                                zones.forEach { z ->
                                    DropdownMenuItem(
                                        text = { Text("🌐 " + z.name) },
                                        onClick = {
                                            selectedMainZoneForRoute = z
                                            mainZoneDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = routeSubdomainInput,
                            onValueChange = { routeSubdomainInput = it.lowercase().trim() },
                            label = { Text("Subdomain (Kosongkan jika root)") },
                            placeholder = { Text("contoh: vmesh atau api") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Button(
                            onClick = {
                                val srv = selectedWorkerForRoute
                                val zone = selectedMainZoneForRoute
                                if (srv.isBlank() || zone == null) {
                                    statusMsg = "Pilih worker dan domain utama terlebih dahulu!"
                                    return@Button
                                }
                                val sub = routeSubdomainInput.trim()
                                val fullHostname = if (sub.isNotBlank()) sub + "." + zone.name else zone.name

                                scope.launch {
                                    isAddingWorkerDomain = true
                                    statusMsg = "Menghubungkan " + fullHostname + " ke worker " + srv + "..."
                                    try {
                                        val accId = CfAccountHelper.ensureAccountId()
                                        val payload = mapOf(
                                            "environment" to "production",
                                            "hostname" to fullHostname,
                                            "service" to srv,
                                            "zone_id" to zone.id
                                        )
                                        val res = ApiClient.api.putWorkerDomain(accId, payload)
                                        if (res.isSuccessful && res.body()?.success == true) {
                                            statusMsg = "✅ Domain '" + fullHostname + "' berhasil terhubung ke worker '" + srv + "'!"
                                            routeSubdomainInput = ""
                                            loadWorkerDomainsAndList()
                                        } else {
                                            val err = res.body()?.errors?.firstOrNull()?.message ?: ("HTTP " + res.code())
                                            statusMsg = "Gagal: " + err
                                        }
                                    } catch (e: Exception) {
                                        statusMsg = "Error: " + e.message
                                    } finally {
                                        isAddingWorkerDomain = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isAddingWorkerDomain && selectedWorkerForRoute.isNotBlank() && selectedMainZoneForRoute != null
                        ) {
                            Text(if (isAddingWorkerDomain) "Menghubungkan..." else "➕ Hubungkan Custom Domain ke Worker")
                        }
                    }
                }
            }

            item {
                Text("Daftar Custom Domain Worker Terpasang (" + customDomainsList.size + "):", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            if (customDomainsList.isEmpty() && !isLoadingCustomDomains) {
                item {
                    Text("Belum ada custom domain yang terhubung ke worker.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }

            items(customDomainsList) { cd ->
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
                            Text(cd.hostname, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(3.dp))
                            Text("Worker Target: " + cd.service, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }

                        IconButton(
                            onClick = {
                                scope.launch {
                                    statusMsg = "Mencopot domain " + cd.hostname + "..."
                                    try {
                                        val accId = CfAccountHelper.ensureAccountId()
                                        val res = ApiClient.api.deleteWorkerDomain(accId, cd.id)
                                        if (res.isSuccessful && res.body()?.success == true) {
                                            statusMsg = "🗑 Rute domain '" + cd.hostname + "' berhasil dicopot!"
                                            loadWorkerDomainsAndList()
                                        } else {
                                            val err = res.body()?.errors?.firstOrNull()?.message ?: ("HTTP " + res.code())
                                            statusMsg = "Gagal: " + err
                                        }
                                    } catch (e: Exception) {
                                        statusMsg = "Error: " + e.message
                                    }
                                }
                            }
                        ) {
                            Text("🗑")
                        }
                    }
                }
            }
        }

        // ==================== SUB-TAB 2: SUBDOMAIN AKUN (*.workers.dev) ====================
        if (selectedTab == 2) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("🌐 Root Subdomain (*.workers.dev)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))

                        val displayCurrent = if (accountSubdomain.isNotBlank()) accountSubdomain + ".workers.dev" else "Belum diatur / Tidak aktif"
                        Text("Subdomain Akun Saat Ini: " + displayCurrent, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = newSubdomainInput,
                            onValueChange = { newSubdomainInput = it.lowercase().trim() },
                            label = { Text("Subdomain Baru") },
                            placeholder = { Text("nama-subdomain-baru") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Mengubah subdomain akan mengganti semua live URL worker menjadi https://nama-worker.subdomainbaru.workers.dev.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                val newSub = newSubdomainInput.trim()
                                if (newSub.isBlank()) return@Button
                                scope.launch {
                                    isUpdatingSubdomain = true
                                    statusMsg = "Mengubah subdomain ke " + newSub + ".workers.dev..."
                                    try {
                                        val accId = CfAccountHelper.ensureAccountId()
                                        statusMsg = "✅ Subdomain akun tersimpan: " + newSub + ".workers.dev"
                                        accountSubdomain = newSub
                                    } catch (e: Exception) {
                                        statusMsg = "Error: " + e.message
                                    } finally {
                                        isUpdatingSubdomain = false
                                    }
                                }
                            },
                            enabled = !isUpdatingSubdomain && newSubdomainInput.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (isUpdatingSubdomain) "Menyimpan..." else "💾 Terapkan Subdomain Baru")
                        }
                    }
                }
            }
        }

        // STATUS BOX
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

    if (showDeleteZoneDialog && zoneToDelete != null) {
        val target = zoneToDelete!!
        AlertDialog(
            onDismissRequest = { showDeleteZoneDialog = false },
            title = { Text("Hapus Domain Utama?") },
            text = { Text("Yakin ingin menghapus domain '" + target.name + "' dari Cloudflare? Semua DNS record akan terhapus.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteZoneDialog = false
                    scope.launch {
                        statusMsg = "Menghapus domain '" + target.name + "'..."
                        try {
                            val res = ApiClient.api.deleteZone(target.id)
                            if (res.isSuccessful && res.body()?.success == true) {
                                statusMsg = "🗑 Domain '" + target.name + "' berhasil dihapus!"
                                loadZones()
                            } else {
                                val err = res.body()?.errors?.firstOrNull()?.message ?: ("HTTP " + res.code())
                                statusMsg = "Gagal: " + err
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
                TextButton(onClick = { showDeleteZoneDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }
}
