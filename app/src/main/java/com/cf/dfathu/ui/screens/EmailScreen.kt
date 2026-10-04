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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cf.dfathu.data.AppConfig
import com.cf.dfathu.data.api.ApiClient
import com.cf.dfathu.data.api.CfAccountHelper
import com.cf.dfathu.data.local.AccountStorage
import com.cf.dfathu.data.model.ZoneItem
import kotlinx.coroutines.launch

data class EmailDestItem(val id: String, val email: String, val verified: Boolean)
data class CustomEmailRuleItem(val id: String, val name: String, val matchAddress: String, val forwardTo: String, val enabled: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailScreen() {
    val context = LocalContext.current
    val storage = remember { AccountStorage(context) }
    val accounts = remember { storage.getAccounts() }
    val activeIdx = remember { storage.getActiveIndex() }
    val currAcc = accounts.getOrNull(activeIdx)
    val email = AppConfig.activeEmail.ifBlank { currAcc?.email ?: "" }
    val apiKey = AppConfig.activeApiKey.ifBlank { currAcc?.apiKey ?: "" }

    var selectedTab by remember { mutableStateOf(0) }
    val tabTitles = listOf("🔀 Custom Routing", "📥 Catch-All", "🎯 Email Tujuan")

    var zones by remember { mutableStateOf<List<ZoneItem>>(emptyList()) }
    var selectedZone by remember { mutableStateOf<ZoneItem?>(null) }
    var zoneExpanded by remember { mutableStateOf(false) }

    // Worker List untuk opsi "Send to Worker"
    var workerList by remember { mutableStateOf<List<String>>(emptyList()) }

    // Destinations
    var destinations by remember { mutableStateOf<List<EmailDestItem>>(emptyList()) }
    var newDestEmail by remember { mutableStateOf("") }

    // Custom Rules
    var customRules by remember { mutableStateOf<List<CustomEmailRuleItem>>(emptyList()) }
    var newAliasInput by remember { mutableStateOf("") }
    var selectedForwardDest by remember { mutableStateOf("") }
    var destDropdownExpanded by remember { mutableStateOf(false) }

    // Catch-All (Sesuai SC Web: forward ke Email ATAU worker)
    var catchAllEnabled by remember { mutableStateOf(false) }
    var catchAllActionType by remember { mutableStateOf("forward") } // "forward" atau "worker"
    var catchAllActionExpanded by remember { mutableStateOf(false) }
    var catchAllForwardTo by remember { mutableStateOf("") }
    var catchAllForwardDropdownExpanded by remember { mutableStateOf(false) }
    var catchAllWorkerTarget by remember { mutableStateOf("") }
    var catchAllWorkerDropdownExpanded by remember { mutableStateOf(false) }

    // Status MX Routing Zone (Sama persis dengan SC Web)
    var zoneMxStatus by remember { mutableStateOf("") } // "ready", "needs_setup", "unconfigured", dll
    var isCheckingMxStatus by remember { mutableStateOf(false) }
    var isSettingUpMx by remember { mutableStateOf(false) }

    var statusMsg by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    fun loadDestinations() {
        scope.launch {
            try {
                val accId = CfAccountHelper.ensureAccountId()
                val res = ApiClient.api.listEmailDestinations(accId)
                if (res.isSuccessful && res.body()?.success == true) {
                    val raw = res.body()?.result ?: emptyList()
                    val list = raw.mapNotNull {
                        val id = it.get("id")?.asString ?: return@mapNotNull null
                        val emailAddr = it.get("email")?.asString ?: return@mapNotNull null
                        val verified = it.get("verified")?.asString != null || it.get("verified")?.asBoolean == true
                        EmailDestItem(id, emailAddr, verified)
                    }
                    destinations = list
                    if (selectedForwardDest.isBlank() && list.any { it.verified }) {
                        selectedForwardDest = list.first { it.verified }.email
                    }
                    if (catchAllForwardTo.isBlank() && list.any { it.verified }) {
                        catchAllForwardTo = list.first { it.verified }.email
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun loadWorkersList() {
        scope.launch {
            try {
                val accId = CfAccountHelper.ensureAccountId()
                val res = ApiClient.api.listWorkers(accId)
                if (res.isSuccessful && res.body()?.success == true) {
                    val list = res.body()?.result?.mapNotNull { it.get("id")?.asString } ?: emptyList()
                    workerList = list
                    if (catchAllWorkerTarget.isBlank() && list.isNotEmpty()) {
                        catchAllWorkerTarget = list[0]
                    }
                }
            } catch (_: Exception) {}
        }
    }

    // Cek Status MX Records Domain (Logika SC Web get-email-settings)
    fun checkDomainMxStatus(zoneId: String) {
        scope.launch {
            isCheckingMxStatus = true
            try {
                val res = ApiClient.api.getEmailRoutingSettings(zoneId)
                if (res.isSuccessful && res.body()?.success == true) {
                    val resElement = res.body()?.result
                    val resObj = if (resElement?.isJsonObject == true) resElement.asJsonObject else null
                    val status = resObj?.get("status")?.asString ?: "unconfigured"
                    zoneMxStatus = status
                } else {
                    zoneMxStatus = "needs_setup"
                }
            } catch (_: Exception) {
                zoneMxStatus = "unknown"
            } finally {
                isCheckingMxStatus = false
            }
        }
    }

    // Setup Otomatis DNS MX & SPF Records (Logika SC Web enable-email-routing)
    fun setupCloudflareMxAutomatically(zoneId: String) {
        scope.launch {
            isSettingUpMx = true
            statusMsg = "Memasang MX & SPF Cloudflare otomatis..."
            try {
                val res = ApiClient.api.enableEmailRouting(zoneId)
                if (res.isSuccessful && res.body()?.success == true) {
                    statusMsg = "✅ Sukses konfigurasi MX/SPF Email Routing otomatis!"
                    checkDomainMxStatus(zoneId)
                } else {
                    val err = res.body()?.errors?.firstOrNull()?.message ?: ("HTTP " + res.code())
                    statusMsg = "Gagal setup MX: " + err
                }
            } catch (e: Exception) {
                statusMsg = "Error: " + e.message
            } finally {
                isSettingUpMx = false
            }
        }
    }

    fun loadZoneRules(zoneId: String) {
        scope.launch {
            isLoading = true
            try {
                checkDomainMxStatus(zoneId)

                // 1. Load Custom Rules
                val resRules = ApiClient.api.listEmailRules(zoneId)
                if (resRules.isSuccessful && resRules.body()?.success == true) {
                    val raw = resRules.body()?.result ?: emptyList()
                    val parsed = raw.mapNotNull { obj ->
                        val id = obj.get("id")?.asString ?: return@mapNotNull null
                        val name = obj.get("name")?.asString ?: "Rule"
                        val enabled = obj.get("enabled")?.asBoolean ?: true
                        
                        var match = ""
                        val matchers = obj.getAsJsonArray("matchers")
                        matchers?.forEach { m ->
                            val mObj = m.asJsonObject
                            if (mObj.get("type")?.asString == "literal") {
                                match = mObj.get("value")?.asString ?: ""
                            }
                        }

                        var forward = ""
                        val actions = obj.getAsJsonArray("actions")
                        actions?.forEach { a ->
                            val aObj = a.asJsonObject
                            val actType = aObj.get("type")?.asString ?: ""
                            val valArr = aObj.getAsJsonArray("value")
                            val valStr = valArr?.firstOrNull()?.asString ?: ""
                            forward = if (actType == "worker") "Worker: " + valStr else valStr
                        }
                        CustomEmailRuleItem(id, name, match, forward, enabled)
                    }
                    customRules = parsed
                }

                // 2. Load Catch-All persis seperti SC Web
                val resCatch = ApiClient.api.getEmailCatchAll(zoneId)
                if (resCatch.isSuccessful && resCatch.body()?.success == true) {
                    val rElement = resCatch.body()?.result
                    val rObj = if (rElement?.isJsonObject == true) rElement.asJsonObject else null
                    catchAllEnabled = rObj?.get("enabled")?.asBoolean ?: false
                    
                    val actions = rObj?.getAsJsonArray("actions")
                    if (actions != null && actions.size() > 0) {
                        val mainAction = actions[0].asJsonObject
                        val actionType = mainAction.get("type")?.asString ?: "forward"
                        catchAllActionType = actionType
                        val valStr = mainAction.getAsJsonArray("value")?.firstOrNull()?.asString ?: ""
                        if (actionType == "worker") {
                            catchAllWorkerTarget = valStr
                        } else {
                            catchAllForwardTo = valStr
                        }
                    } else {
                        catchAllActionType = "forward"
                    }
                }
            } catch (_: Exception) {} finally {
                isLoading = false
            }
        }
    }

    fun loadInitialData() {
        if (email.isBlank() || apiKey.isBlank()) {
            statusMsg = "⚠️ Isi Email & API Key di tab Akun terlebih dahulu!"
            return
        }
        scope.launch {
            try {
                val res = ApiClient.api.listZones()
                if (res.isSuccessful && res.body()?.success == true) {
                    val list = res.body()?.result ?: emptyList()
                    zones = list
                    if (list.isNotEmpty()) {
                        selectedZone = list[0]
                        loadZoneRules(list[0].id)
                    }
                }
                loadDestinations()
                loadWorkersList()
            } catch (e: Exception) {
                statusMsg = "Error: " + e.message
            }
        }
    }

    LaunchedEffect(email, apiKey) {
        if (email.isNotEmpty() && apiKey.isNotEmpty()) loadInitialData()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // --- 1. HEADER & ZONE SELECTOR ---
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("✉️ Email Routing (Direct)", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    val currentZoneLabel = selectedZone?.name ?: "(Pilih Zone)"
                    Text("Domain: " + currentZoneLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { selectedZone?.let { loadZoneRules(it.id) }; loadDestinations(); loadWorkersList() }, enabled = !isLoading) {
                    Text(if (isLoading) "⏳" else "🔄")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (zones.size > 1) {
                ExposedDropdownMenuBox(
                    expanded = zoneExpanded,
                    onExpandedChange = { zoneExpanded = !zoneExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedZone?.name ?: "Pilih Domain",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Pilih Domain Aktif") },
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
                                    loadZoneRules(z.id)
                                }
                            )
                        }
                    }
                }
            }

            // --- BANNER OTOMATIS STATUS MX / SPF RECORD CLOUDFLARE ---
            if (selectedZone != null) {
                Spacer(modifier = Modifier.height(10.dp))
                val isMxReady = zoneMxStatus == "ready"

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isMxReady) Color(0xFFDCFCE7) else Color(0xFFFEF3C7)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (isMxReady) "✅ STATUS PERUTEAN: MX Record Siap & Aktif!" else "⚠️ STATUS PERUTEAN: MX Records Belum Dikonfigurasi (" + (if (zoneMxStatus.isBlank()) "Mengecek..." else zoneMxStatus) + ")",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isMxReady) Color(0xFF166534) else Color(0xFF92400E)
                            )
                        }

                        if (!isMxReady) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Domain ini belum memiliki record MX & SPF Cloudflare aktif. Klik tombol di bawah untuk memasangnya otomatis tanpa input manual.",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF78350F)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { selectedZone?.let { setupCloudflareMxAutomatically(it.id) } },
                                enabled = !isSettingUpMx,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEA580C)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (isSettingUpMx) "Memasang DNS MX..." else "⚡ Pasang MX & SPF Cloudflare Otomatis")
                            }
                        }
                    }
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

        // --- SUB-TAB 0: CUSTOM ROUTING RULES ---
        if (selectedTab == 0) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("➕ Tambah Custom Email Rule", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("Teruskan alamat email khusus domain ke email tujuan", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = newAliasInput,
                                onValueChange = { newAliasInput = it.lowercase().trim() },
                                label = { Text("Custom Address (cth: admin)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            val domainSuffix = "@" + (selectedZone?.name ?: "domain.com")
                            Text(domainSuffix, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Dropdown Pilih Email Tujuan Terverifikasi
                        ExposedDropdownMenuBox(
                            expanded = destDropdownExpanded,
                            onExpandedChange = { destDropdownExpanded = !destDropdownExpanded }
                        ) {
                            OutlinedTextField(
                                value = selectedForwardDest.ifBlank { "Pilih Email Tujuan" },
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Teruskan ke (Forward To)") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = destDropdownExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = destDropdownExpanded,
                                onDismissRequest = { destDropdownExpanded = false }
                            ) {
                                val verifiedList = destinations.filter { it.verified }
                                if (verifiedList.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("Belum ada email tujuan terverifikasi") },
                                        onClick = { destDropdownExpanded = false }
                                    )
                                } else {
                                    verifiedList.forEach { d ->
                                        DropdownMenuItem(
                                            text = { Text(d.email) },
                                            onClick = {
                                                selectedForwardDest = d.email
                                                destDropdownExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                val zone = selectedZone
                                if (zone == null || newAliasInput.isBlank() || selectedForwardDest.isBlank()) {
                                    statusMsg = "Isi alias alamat email dan pilih tujuan!"
                                    return@Button
                                }
                                scope.launch {
                                    isSaving = true
                                    val fullAddress = newAliasInput + "@" + zone.name
                                    statusMsg = "Menambahkan rule untuk " + fullAddress + "..."
                                    try {
                                        val payload = mapOf(
                                            "name" to ("Forward " + newAliasInput),
                                            "enabled" to true,
                                            "matchers" to listOf(
                                                mapOf("type" to "literal", "field" to "to", "value" to fullAddress)
                                            ),
                                            "actions" to listOf(
                                                mapOf("type" to "forward", "value" to listOf(selectedForwardDest))
                                            )
                                        )
                                        val res = ApiClient.api.createEmailRule(zone.id, payload)
                                        if (res.isSuccessful && res.body()?.success == true) {
                                            statusMsg = "✅ Rule untuk " + fullAddress + " berhasil dibuat!"
                                            newAliasInput = ""
                                            loadZoneRules(zone.id)
                                        } else {
                                            val err = res.body()?.errors?.firstOrNull()?.message ?: ("HTTP " + res.code())
                                            statusMsg = "Gagal: " + err
                                        }
                                    } catch (e: Exception) {
                                        statusMsg = "Error: " + e.message
                                    } finally {
                                        isSaving = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSaving && selectedZone != null && newAliasInput.isNotBlank() && selectedForwardDest.isNotBlank()
                        ) {
                            Text(if (isSaving) "Menyimpan..." else "🚀 Simpan Rule Routing")
                        }
                    }
                }
            }

            item {
                Text("Daftar Rule Aktif (" + customRules.size + "):", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            if (customRules.isEmpty() && !isLoading) {
                item {
                    Text("Belum ada rule routing custom pada domain ini.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }

            items(customRules) { r ->
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
                            Text(r.matchAddress.ifBlank { r.name }, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("➡ " + r.forwardTo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }

                        IconButton(onClick = {
                            val zone = selectedZone ?: return@IconButton
                            scope.launch {
                                statusMsg = "Menghapus rule..."
                                try {
                                    val res = ApiClient.api.deleteEmailRule(zone.id, r.id)
                                    if (res.isSuccessful && res.body()?.success == true) {
                                        statusMsg = "🗑 Rule berhasil dihapus!"
                                        loadZoneRules(zone.id)
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

        // --- SUB-TAB 1: CATCH-ALL ROUTING (BISA KE WORKER ATAU FORWARD EMAIL) ---
        if (selectedTab == 1) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("📥 Catch-All Email Routing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        val catchAllDomain = "@" + (selectedZone?.name ?: "domain.com")
                        Text("Teruskan SEMUA email apa saja yang dikirim ke domain " + catchAllDomain + " ke Email Forwarding atau proses via Worker.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

                        Spacer(modifier = Modifier.height(14.dp))

                        // Switch Status Catch-All
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Status Catch-All:", fontWeight = FontWeight.SemiBold)
                            Switch(
                                checked = catchAllEnabled,
                                onCheckedChange = { catchAllEnabled = it }
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // PILIHAN AKSI KATEGORI: Forward to Email ATAU Send to Worker
                        ExposedDropdownMenuBox(
                            expanded = catchAllActionExpanded,
                            onExpandedChange = { catchAllActionExpanded = !catchAllActionExpanded }
                        ) {
                            val actionLabel = if (catchAllActionType == "worker") "Send to Worker (⚡ Eksekusi Script)" else "Forward to destination address (✉️️ Email)"
                            OutlinedTextField(
                                value = actionLabel,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Aksi / Action Kategori") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = catchAllActionExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = catchAllActionExpanded,
                                onDismissRequest = { catchAllActionExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Forward to destination address (✉️ Email)") },
                                    onClick = {
                                        catchAllActionType = "forward"
                                        catchAllActionExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Send to Worker (⚡ Eksekusi Script)") },
                                    onClick = {
                                        catchAllActionType = "worker"
                                        catchAllActionExpanded = false
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // KONDISI 1: JIKA FORWARD TO EMAIL
                        if (catchAllActionType == "forward") {
                            ExposedDropdownMenuBox(
                                expanded = catchAllForwardDropdownExpanded,
                                onExpandedChange = { catchAllForwardDropdownExpanded = !catchAllForwardDropdownExpanded }
                            ) {
                                OutlinedTextField(
                                    value = catchAllForwardTo.ifBlank { "Pilih Email Forwarding Terverifikasi" },
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Pilih Email Forwarding") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = catchAllForwardDropdownExpanded) },
                                    modifier = Modifier.menuAnchor().fillMaxWidth()
                                )
                                ExposedDropdownMenu(
                                    expanded = catchAllForwardDropdownExpanded,
                                    onDismissRequest = { catchAllForwardDropdownExpanded = false }
                                ) {
                                    val verifiedList = destinations.filter { it.verified }
                                    if (verifiedList.isEmpty()) {
                                        DropdownMenuItem(
                                            text = { Text("Belum ada email terverifikasi") },
                                            onClick = { catchAllForwardDropdownExpanded = false }
                                        )
                                    } else {
                                        verifiedList.forEach { d ->
                                            DropdownMenuItem(
                                                text = { Text(d.email) },
                                                onClick = {
                                                    catchAllForwardTo = d.email
                                                    catchAllForwardDropdownExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // KONDISI 2: JIKA SEND TO WORKER
                        if (catchAllActionType == "worker") {
                            ExposedDropdownMenuBox(
                                expanded = catchAllWorkerDropdownExpanded,
                                onExpandedChange = { catchAllWorkerDropdownExpanded = !catchAllWorkerDropdownExpanded }
                            ) {
                                OutlinedTextField(
                                    value = catchAllWorkerTarget.ifBlank { "Pilih Worker Tujuan" },
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Pilih Target Worker") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = catchAllWorkerDropdownExpanded) },
                                    modifier = Modifier.menuAnchor().fillMaxWidth()
                                )
                                ExposedDropdownMenu(
                                    expanded = catchAllWorkerDropdownExpanded,
                                    onDismissRequest = { catchAllWorkerDropdownExpanded = false }
                                ) {
                                    if (workerList.isEmpty()) {
                                        DropdownMenuItem(
                                            text = { Text("Tidak ada worker di akun ini") },
                                            onClick = { catchAllWorkerDropdownExpanded = false }
                                        )
                                    } else {
                                        workerList.forEach { w ->
                                            DropdownMenuItem(
                                                text = { Text("⚡ " + w) },
                                                onClick = {
                                                    catchAllWorkerTarget = w
                                                    catchAllWorkerDropdownExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Button(
                            onClick = {
                                val zone = selectedZone ?: return@Button
                                val targetVal = if (catchAllActionType == "worker") catchAllWorkerTarget else catchAllForwardTo

                                if (catchAllEnabled && targetVal.isBlank()) {
                                    statusMsg = if (catchAllActionType == "worker") "Pilih worker tujuan!" else "Pilih email tujuan!"
                                    return@Button
                                }

                                scope.launch {
                                    isSaving = true
                                    statusMsg = "Menyimpan pengaturan Catch-All..."
                                    try {
                                        val actionsList = if (catchAllEnabled) {
                                            listOf(
                                                mapOf(
                                                    "type" to catchAllActionType,
                                                    "value" to listOf(targetVal)
                                                )
                                            )
                                        } else emptyList()

                                        val payload = mapOf(
                                            "name" to "Catch-All Rule",
                                            "enabled" to catchAllEnabled,
                                            "matchers" to listOf(mapOf("type" to "all")),
                                            "actions" to actionsList
                                        )

                                        val res = ApiClient.api.updateEmailCatchAll(zone.id, payload)
                                        if (res.isSuccessful && res.body()?.success == true) {
                                            val destLabel = if (catchAllActionType == "worker") "Worker: " + targetVal else targetVal
                                            statusMsg = "✅ Catch-All diperbarui -> " + (if (catchAllEnabled) destLabel else "Nonaktif")
                                        } else {
                                            val err = res.body()?.errors?.firstOrNull()?.message ?: ("HTTP " + res.code())
                                            statusMsg = "Gagal: " + err
                                        }
                                    } catch (e: Exception) {
                                        statusMsg = "Error: " + e.message
                                    } finally {
                                        isSaving = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSaving && selectedZone != null
                        ) {
                            Text(if (isSaving) "Menyimpan..." else "💾 Simpan Pengaturan Catch-All")
                        }
                    }
                }
            }
        }

        // --- SUB-TAB 2: DAFTAR EMAIL TUJUAN (FORWARDING ADDRESSES) ---
        if (selectedTab == 2) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("➕ Tambah Email Tujuan Forwarding", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("Cloudflare akan mengirimkan email konfirmasi verifikasi", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = newDestEmail,
                                onValueChange = { newDestEmail = it.trim() },
                                label = { Text("Email Tujuan (cth: inbox@gmail.com)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    if (newDestEmail.isBlank()) return@Button
                                    scope.launch {
                                        isSaving = true
                                        statusMsg = "Mendaftarkan email tujuan..."
                                        try {
                                            val accId = CfAccountHelper.ensureAccountId()
                                            val payload = mapOf("email" to newDestEmail)
                                            val res = ApiClient.api.createEmailDestination(accId, payload)
                                            if (res.isSuccessful && res.body()?.success == true) {
                                                statusMsg = "✅ Verifikasi dikirim ke " + newDestEmail + "! Cek inbox."
                                                newDestEmail = ""
                                                loadDestinations()
                                            } else {
                                                val err = res.body()?.errors?.firstOrNull()?.message ?: ("HTTP " + res.code())
                                                statusMsg = "Gagal: " + err
                                            }
                                        } catch (e: Exception) {
                                            statusMsg = "Error: " + e.message
                                        } finally {
                                            isSaving = false
                                        }
                                    }
                                },
                                enabled = !isSaving && newDestEmail.isNotBlank()
                            ) {
                                Text(if (isSaving) "..." else "Tambah")
                            }
                        }
                    }
                }
            }

            item {
                Text("Daftar Email Tujuan (" + destinations.size + "):", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            items(destinations) { d ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(d.email, fontWeight = FontWeight.Bold)
                            Text(
                                text = if (d.verified) "✅ Terverifikasi" else "⏳ Menunggu Verifikasi",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (d.verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }

                        IconButton(onClick = {
                            scope.launch {
                                statusMsg = "Menghapus tujuan " + d.email + "..."
                                try {
                                    val accId = CfAccountHelper.ensureAccountId()
                                    val res = ApiClient.api.deleteEmailDestination(accId, d.id)
                                    if (res.isSuccessful && res.body()?.success == true) {
                                        statusMsg = "🗑 Email " + d.email + " dicopot!"
                                        loadDestinations()
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

        // --- STATUS BOX ---
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
}
