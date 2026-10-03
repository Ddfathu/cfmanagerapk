package com.cf.manager.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
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
import com.cf.manager.data.AppConfig
import com.cf.manager.data.api.ApiClient
import com.cf.manager.data.api.CfAccountHelper
import com.cf.manager.data.local.AccountStorage
import com.cf.manager.data.model.TunnelItem
import com.cf.manager.data.model.ZoneItem
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.launch

data class IngressRuleItem(
    val hostname: String,
    val service: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunnelScreen() {
    val context = LocalContext.current
    val storage = remember { AccountStorage(context) }
    val accounts = remember { storage.getAccounts() }
    val activeIdx = remember { storage.getActiveIndex() }
    val currAcc = accounts.getOrNull(activeIdx)
    val email = AppConfig.activeEmail.ifBlank { currAcc?.email ?: "" }
    val apiKey = AppConfig.activeApiKey.ifBlank { currAcc?.apiKey ?: "" }

    var tunnels by remember { mutableStateOf<List<TunnelItem>>(emptyList()) }
    var zones by remember { mutableStateOf<List<ZoneItem>>(emptyList()) }
    var tunnelNameInput by remember { mutableStateOf("") }
    var activeToken by remember { mutableStateOf("") }

    var selectedTunnel by remember { mutableStateOf<TunnelItem?>(null) }
    var selectedZone by remember { mutableStateOf<ZoneItem?>(null) }
    var zoneExpanded by remember { mutableStateOf(false) }

    var subDomainInput by remember { mutableStateOf("") }
    var serviceType by remember { mutableStateOf("http://") }
    var serviceUrlInput by remember { mutableStateOf("localhost:8080") }

    var statusMsg by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var isCreating by remember { mutableStateOf(false) }
    var isRouting by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var tunnelToDelete by remember { mutableStateOf<TunnelItem?>(null) }

    // State Ingress Routing List & Edit
    var ingressRules by remember { mutableStateOf<List<IngressRuleItem>>(emptyList()) }
    var isLoadingIngress by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<IngressRuleItem?>(null) }
    var editServiceInput by remember { mutableStateOf("") }
    var isSavingRule by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    fun loadIngressRules(tunnelId: String) {
        scope.launch {
            isLoadingIngress = true
            try {
                val accId = CfAccountHelper.ensureAccountId()
                val res = ApiClient.api.getTunnelConfigurations(accId, tunnelId)
                if (res.isSuccessful && res.body()?.success == true) {
                    val configObj = res.body()?.result?.getAsJsonObject("config")
                    val ingressArr = configObj?.getAsJsonArray("ingress")
                    val list = mutableListOf<IngressRuleItem>()
                    ingressArr?.forEach { element ->
                        val obj = element.asJsonObject
                        val host = obj.get("hostname")?.asString
                        val srv = obj.get("service")?.asString ?: ""
                        if (!host.isNullOrBlank()) {
                            list.add(IngressRuleItem(host, srv))
                        }
                    }
                    ingressRules = list
                } else {
                    ingressRules = emptyList()
                }
            } catch (_: Exception) {
                ingressRules = emptyList()
            } finally {
                isLoadingIngress = false
            }
        }
    }

    fun loadData() {
        if (email.isBlank() || apiKey.isBlank()) {
            statusMsg = "⚠️ Isi Email & API Key di tab Akun terlebih dahulu!"
            return
        }
        scope.launch {
            isLoading = true
            try {
                val accId = CfAccountHelper.ensureAccountId()
                if (accId.isBlank()) {
                    statusMsg = "Gagal mendeteksi Account ID Cloudflare. Periksa API Key!"
                    return@launch
                }

                val resT = ApiClient.api.listTunnels(accId)
                if (resT.isSuccessful && resT.body()?.success == true) {
                    val list = resT.body()?.result ?: emptyList()
                    tunnels = list
                    if (selectedTunnel == null && list.isNotEmpty()) {
                        selectedTunnel = list[0]
                        loadIngressRules(list[0].id)
                    }
                }

                val resZ = ApiClient.api.listZones()
                if (resZ.isSuccessful && resZ.body()?.success == true) {
                    val zList = resZ.body()?.result ?: emptyList()
                    zones = zList
                    if (selectedZone == null && zList.isNotEmpty()) {
                        selectedZone = zList[0]
                    }
                }
            } catch (e: Exception) {
                statusMsg = "Error: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(email, apiKey) {
        if (email.isNotEmpty() && apiKey.isNotEmpty()) loadData()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // --- 1. HEADER ---
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("🚇 Cloudflare Tunnel", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Kelola Ingress Routing & Public Hostname native", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                IconButton(onClick = { loadData() }, enabled = !isLoading) {
                    Text(if (isLoading) "⏳" else "🔄")
                }
            }
        }

        // --- 2. CARD BUAT TUNNEL BARU ---
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("➕ Buat Tunnel Baru", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = tunnelNameInput,
                            onValueChange = { tunnelNameInput = it.lowercase().trim() },
                            label = { Text("Nama Tunnel") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                if (tunnelNameInput.isBlank()) return@Button
                                scope.launch {
                                    isCreating = true
                                    statusMsg = "Membuat tunnel '${tunnelNameInput}'..."
                                    try {
                                        val accId = CfAccountHelper.ensureAccountId()
                                        val payload = mapOf(
                                            "name" to tunnelNameInput,
                                            "config_src" to "cloudflare"
                                        )
                                        val res = ApiClient.api.createTunnel(accId, payload)
                                        if (res.isSuccessful && res.body()?.success == true) {
                                            statusMsg = "✅ Tunnel '${tunnelNameInput}' berhasil dibuat!"
                                            tunnelNameInput = ""
                                            loadData()
                                        } else {
                                            val err = res.body()?.errors?.firstOrNull()?.message ?: "HTTP ${res.code()}"
                                            statusMsg = "Gagal: $err"
                                        }
                                    } catch (e: Exception) {
                                        statusMsg = "Error: ${e.message}"
                                    } finally {
                                        isCreating = false
                                    }
                                }
                            },
                            enabled = !isCreating && tunnelNameInput.isNotBlank()
                        ) {
                            Text(if (isCreating) "..." else "Buat")
                        }
                    }
                }
            }
        }

        // --- 3. RUN TOKEN VIEWER ---
        if (activeToken.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🔑 Cloudflared Run Token", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            TextButton(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("tunnel_token", activeToken)
                                clipboard.setPrimaryClip(clip)
                                statusMsg = "📋 Token disalin ke clipboard!"
                            }) {
                                Text("Salin Token")
                            }
                        }
                        Text(
                            text = activeToken,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                .padding(8.dp)
                        )
                    }
                }
            }
        }

        // --- 4. FORM TAMBAH INGRESS ROUTE (PUBLIC HOSTNAME) ---
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("🔗 Tambah Public Hostname (Ingress Route)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("Target Tunnel: ${selectedTunnel?.name ?: "(Pilih Tunnel di bawah)"}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(10.dp))

                    ExposedDropdownMenuBox(
                        expanded = zoneExpanded,
                        onExpandedChange = { zoneExpanded = !zoneExpanded }
                    ) {
                        OutlinedTextField(
                            value = selectedZone?.name ?: "Pilih Domain Cloudflare",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Domain Utama (Zone)") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = zoneExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = zoneExpanded,
                            onDismissRequest = { zoneExpanded = false }
                        ) {
                            zones.forEach { z ->
                                DropdownMenuItem(
                                    text = { Text(z.name) },
                                    onClick = {
                                        selectedZone = z
                                        zoneExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = subDomainInput,
                        onValueChange = { subDomainInput = it.lowercase().trim() },
                        label = { Text("Subdomain (cth: vpn, ssh, @)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = serviceType,
                            onValueChange = { serviceType = it },
                            label = { Text("Proto") },
                            modifier = Modifier.width(95.dp),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = serviceUrlInput,
                            onValueChange = { serviceUrlInput = it.trim() },
                            label = { Text("Service Lokal (cth: localhost:8080)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            val tun = selectedTunnel
                            val zon = selectedZone
                            if (tun == null || zon == null) {
                                statusMsg = "⚠️️ Pilih tunnel dan domain utama terlebih dahulu!"
                                return@Button
                            }
                            scope.launch {
                                isRouting = true
                                val accId = CfAccountHelper.ensureAccountId()
                                val fullHost = if (subDomainInput.isBlank() || subDomainInput == "@") zon.name else "${subDomainInput}.${zon.name}"
                                val fullService = if (serviceUrlInput.startsWith("http://") || serviceUrlInput.startsWith("https://") || serviceUrlInput.startsWith("tcp://")) {
                                    serviceUrlInput
                                } else {
                                    "$serviceType$serviceUrlInput"
                                }

                                statusMsg = "Menyimpan ingress config ke Cloudflare..."
                                try {
                                    val currentList = ingressRules.filter { it.hostname != fullHost }.toMutableList()
                                    currentList.add(IngressRuleItem(fullHost, fullService))

                                    val ingressArray = currentList.map { mapOf("hostname" to it.hostname, "service" to it.service) }.toMutableList()
                                    ingressArray.add(mapOf("service" to "http_status:404"))

                                    val ingressConfig = mapOf("config" to mapOf("ingress" to ingressArray))
                                    ApiClient.api.updateTunnelConfigurations(accId, tun.id, ingressConfig)

                                    // DNS CNAME
                                    val dnsPayload = mapOf(
                                        "type" to "CNAME",
                                        "name" to fullHost,
                                        "content" to "${tun.id}.cfargotunnel.com",
                                        "ttl" to 1,
                                        "proxied" to true
                                    )
                                    ApiClient.api.createDns(zon.id, dnsPayload)

                                    statusMsg = "✅ Hostname '$fullHost' berhasil ditambahkan!"
                                    subDomainInput = ""
                                    loadIngressRules(tun.id)
                                } catch (e: Exception) {
                                    statusMsg = "Error: ${e.message}"
                                } finally {
                                    isRouting = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isRouting && selectedTunnel != null && selectedZone != null
                    ) {
                        Text(if (isRouting) "Menyimpan..." else "🚀 Simpan Hostname & DNS CNAME")
                    }
                }
            }
        }

        // --- 5. DAFTAR TUNNEL DENGAN SUB-LIST INGRESS RULES ---
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Daftar Tunnel (${tunnels.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Ketuk kartu untuk buka Ingress", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }

        items(tunnels) { t ->
            val isSelected = (selectedTunnel?.id == t.id)
            val rawStatus = (t.status ?: "inactive").lowercase()
            val isHealthy = rawStatus == "healthy" || rawStatus == "active"
            val statusColor = if (isHealthy) Color(0xFF16A34A) else Color(0xFFDC2626)

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        selectedTunnel = t
                        loadIngressRules(t.id)
                    },
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Text(if (isSelected) "✔ " else "🚇 ", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = t.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = statusColor.copy(alpha = 0.15f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(statusColor)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = if (isHealthy) "Aktif" else "Mati",
                                    color = statusColor,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "ID: ${t.id}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.outline
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                selectedTunnel = t
                                scope.launch {
                                    statusMsg = "Mengambil token '${t.name}'..."
                                    try {
                                        val accId = CfAccountHelper.ensureAccountId()
                                        val res = ApiClient.api.getTunnelToken(accId, t.id)
                                        if (res.isSuccessful && res.body()?.success == true) {
                                            activeToken = res.body()?.result ?: ""
                                            statusMsg = "✅ Token tunnel '${t.name}' dimuat!"
                                        }
                                    } catch (e: Exception) {
                                        statusMsg = "Error: ${e.message}"
                                    }
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text("🔑 Token")
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = {
                                tunnelToDelete = t
                                showDeleteDialog = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text("🗑 Hapus")
                        }
                    }

                    // --- SECTION INGRESS RULES PADA TUNNEL YANG DIPILIH ---
                    if (isSelected) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "🌐 Ingress Routes Aktif (${ingressRules.size}):",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        if (isLoadingIngress) {
                            Text("Memuat Ingress Cloudflare...", style = MaterialTheme.typography.bodySmall)
                        } else if (ingressRules.isEmpty()) {
                            Text("Belum ada hostname routing di tunnel ini.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                ingressRules.forEach { rule ->
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
                                                Text(rule.hostname, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                                Text("➡ ${rule.service}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                                            }

                                            Row {
                                                IconButton(
                                                    onClick = {
                                                        editingRule = rule
                                                        editServiceInput = rule.service
                                                    },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Text("✏️")
                                                }

                                                IconButton(
                                                    onClick = {
                                                        scope.launch {
                                                            statusMsg = "Menghapus rule ${rule.hostname}..."
                                                            try {
                                                                val accId = CfAccountHelper.ensureAccountId()
                                                                val updatedList = ingressRules.filter { it.hostname != rule.hostname }
                                                                val ingressArr = updatedList.map { mapOf("hostname" to it.hostname, "service" to it.service) }.toMutableList()
                                                                ingressArr.add(mapOf("service" to "http_status:404"))

                                                                val payload = mapOf("config" to mapOf("ingress" to ingressArr))
                                                                val res = ApiClient.api.updateTunnelConfigurations(accId, t.id, payload)
                                                                if (res.isSuccessful && res.body()?.success == true) {
                                                                    statusMsg = "🗑 Rule ${rule.hostname} dihapus!"
                                                                    loadIngressRules(t.id)
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

    // --- DIALOG EDIT INGRESS SERVICE ---
    if (editingRule != null) {
        val targetRule = editingRule!!
        AlertDialog(
            onDismissRequest = { editingRule = null },
            title = { Text("Edit Ingress Route", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Hostname: ${targetRule.hostname}", fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editServiceInput,
                        onValueChange = { editServiceInput = it.trim() },
                        label = { Text("Target Service Lokal") },
                        placeholder = { Text("http://localhost:8080") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val tun = selectedTunnel ?: return@Button
                        scope.launch {
                            isSavingRule = true
                            statusMsg = "Memperbarui service untuk ${targetRule.hostname}..."
                            try {
                                val accId = CfAccountHelper.ensureAccountId()
                                val updatedList = ingressRules.map {
                                    if (it.hostname == targetRule.hostname) it.copy(service = editServiceInput) else it
                                }
                                val ingressArr = updatedList.map { mapOf("hostname" to it.hostname, "service" to it.service) }.toMutableList()
                                ingressArr.add(mapOf("service" to "http_status:404"))

                                val payload = mapOf("config" to mapOf("ingress" to ingressArr))
                                val res = ApiClient.api.updateTunnelConfigurations(accId, tun.id, payload)
                                if (res.isSuccessful && res.body()?.success == true) {
                                    statusMsg = "✅ Ingress ${targetRule.hostname} berhasil diupdate ke $editServiceInput!"
                                    editingRule = null
                                    loadIngressRules(tun.id)
                                } else {
                                    statusMsg = "Gagal update: HTTP ${res.code()}"
                                }
                            } catch (e: Exception) {
                                statusMsg = "Error: ${e.message}"
                            } finally {
                                isSavingRule = false
                            }
                        }
                    },
                    enabled = !isSavingRule && editServiceInput.isNotBlank()
                ) {
                    Text(if (isSavingRule) "Menyimpan..." else "💾 Simpan")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingRule = null }) {
                    Text("Batal")
                }
            }
        )
    }

    // --- DIALOG HAPUS TUNNEL ---
    if (showDeleteDialog && tunnelToDelete != null) {
        val target = tunnelToDelete!!
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Hapus Tunnel?") },
            text = { Text("Yakin ingin menghapus tunnel '${target.name}' (${target.id}) dari Cloudflare?") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    scope.launch {
                        statusMsg = "Menghapus tunnel '${target.name}'..."
                        try {
                            val accId = CfAccountHelper.ensureAccountId()
                            val res = ApiClient.api.deleteTunnel(accId, target.id)
                            if (res.isSuccessful && res.body()?.success == true) {
                                statusMsg = "🗑 Tunnel '${target.name}' berhasil dihapus!"
                                if (selectedTunnel?.id == target.id) selectedTunnel = null
                                loadData()
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
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }
}
