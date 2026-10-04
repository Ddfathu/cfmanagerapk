package com.cf.manager.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cf.manager.data.AppConfig
import com.cf.manager.data.api.ApiClient
import com.cf.manager.data.api.CfAccountHelper
import com.cf.manager.data.local.AccountStorage
import com.cf.manager.data.model.ZoneItem
import com.google.gson.JsonObject
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

    // Destinations
    var destinations by remember { mutableStateOf<List<EmailDestItem>>(emptyList()) }
    var newDestEmail by remember { mutableStateOf("") }

    // Custom Rules
    var customRules by remember { mutableStateOf<List<CustomEmailRuleItem>>(emptyList()) }
    var newAliasInput by remember { mutableStateOf("") }
    var selectedForwardDest by remember { mutableStateOf("") }
    var destDropdownExpanded by remember { mutableStateOf(false) }

    // Catch-All
    var catchAllEnabled by remember { mutableStateOf(false) }
    var catchAllForwardTo by remember { mutableStateOf("") }

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
                    if (selectedForwardDest.isBlank() && list.isNotEmpty()) {
                        selectedForwardDest = list[0].email
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun loadZoneRules(zoneId: String) {
        scope.launch {
            isLoading = true
            try {
                // Load Custom Rules
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
                            if (aObj.get("type")?.asString == "forward") {
                                val valArr = aObj.getAsJsonArray("value")
                                forward = valArr?.firstOrNull()?.asString ?: ""
                            }
                        }
                        CustomEmailRuleItem(id, name, match, forward, enabled)
                    }
                    customRules = parsed
                }

                // Load Catch-All
                val resCatch = ApiClient.api.getEmailCatchAll(zoneId)
                if (resCatch.isSuccessful && resCatch.body()?.success == true) {
                    val rElement = resCatch.body()?.result
                    val rObj = if (rElement?.isJsonObject == true) rElement.asJsonObject else null
                    catchAllEnabled = rObj?.get("enabled")?.asBoolean ?: false
                    val actions = rObj?.getAsJsonArray("actions")
                    actions?.forEach { a ->
                        val aObj = a.asJsonObject
                        if (aObj.get("type")?.asString == "forward") {
                            catchAllForwardTo = aObj.getAsJsonArray("value")?.firstOrNull()?.asString ?: ""
                        }
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
            } catch (e: Exception) {
                statusMsg = "Error: ${e.message}"
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
                IconButton(onClick = { selectedZone?.let { loadZoneRules(it.id) }; loadDestinations() }, enabled = !isLoading) {
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
                                    val fullAddress = "${newAliasInput}@${zone.name}"
                                    statusMsg = "Menambahkan rule untuk $fullAddress..."
                                    try {
                                        val payload = mapOf(
                                            "name" to "Forward $newAliasInput",
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
                                            statusMsg = "✅ Rule untuk $fullAddress berhasil dibuat!"
                                            newAliasInput = ""
                                            loadZoneRules(zone.id)
                                        } else {
                                            val err = res.body()?.errors?.firstOrNull()?.message ?: "HTTP ${res.code()}"
                                            statusMsg = "Gagal: $err"
                                        }
                                    } catch (e: Exception) {
                                        statusMsg = "Error: ${e.message}"
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
                Text("Daftar Rule Aktif (${customRules.size}):", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                            Text("➡ ${r.forwardTo}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
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

        // --- SUB-TAB 1: CATCH-ALL ROUTING ---
        if (selectedTab == 1) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("📥 Catch-All Email", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        val catchAllDomain = "@" + (selectedZone?.name ?: "domain.com")
                        Text("Teruskan SEMUA email apa saja yang dikirim ke domain " + catchAllDomain + " tanpa perlu membuat alias satu-satu.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

                        Spacer(modifier = Modifier.height(14.dp))

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

                        Spacer(modifier = Modifier.height(10.dp))

                        ExposedDropdownMenuBox(
                            expanded = destDropdownExpanded,
                            onExpandedChange = { destDropdownExpanded = !destDropdownExpanded }
                        ) {
                            OutlinedTextField(
                                value = catchAllForwardTo.ifBlank { "Pilih Email Penerima Utama" },
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Email Forwarding Penerima") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = destDropdownExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = destDropdownExpanded,
                                onDismissRequest = { destDropdownExpanded = false }
                            ) {
                                destinations.filter { it.verified }.forEach { d ->
                                    DropdownMenuItem(
                                        text = { Text(d.email) },
                                        onClick = {
                                            catchAllForwardTo = d.email
                                            destDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Button(
                            onClick = {
                                val zone = selectedZone ?: return@Button
                                if (catchAllEnabled && catchAllForwardTo.isBlank()) {
                                    statusMsg = "Pilih email penerima untuk catch-all!"
                                    return@Button
                                }
                                scope.launch {
                                    isSaving = true
                                    statusMsg = "Menyimpan pengaturan Catch-All..."
                                    try {
                                        val payload = mapOf(
                                            "name" to "Catch-All Rule",
                                            "enabled" to catchAllEnabled,
                                            "matchers" to listOf(mapOf("type" to "all")),
                                            "actions" to if (catchAllEnabled) listOf(mapOf("type" to "forward", "value" to listOf(catchAllForwardTo))) else emptyList()
                                        )
                                        val res = ApiClient.api.updateEmailCatchAll(zone.id, payload)
                                        if (res.isSuccessful && res.body()?.success == true) {
                                            statusMsg = "✅ Pengaturan Catch-All berhasil diperbarui!"
                                        } else {
                                            val err = res.body()?.errors?.firstOrNull()?.message ?: "HTTP ${res.code()}"
                                            statusMsg = "Gagal: $err"
                                        }
                                    } catch (e: Exception) {
                                        statusMsg = "Error: ${e.message}"
                                    } finally {
                                        isSaving = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSaving && selectedZone != null
                        ) {
                            Text(if (isSaving) "Menyimpan..." else "💾 Terapkan Catch-All")
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
                                                statusMsg = "✅ Verifikasi dikirim ke $newDestEmail! Cek inbox."
                                                newDestEmail = ""
                                                loadDestinations()
                                            } else {
                                                statusMsg = "Gagal: HTTP ${res.code()}"
                                            }
                                        } catch (e: Exception) {
                                            statusMsg = "Error: ${e.message}"
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
                Text("Daftar Email Tujuan (${destinations.size}):", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                                statusMsg = "Menghapus tujuan ${d.email}..."
                                try {
                                    val accId = CfAccountHelper.ensureAccountId()
                                    val res = ApiClient.api.deleteEmailDestination(accId, d.id)
                                    if (res.isSuccessful && res.body()?.success == true) {
                                        statusMsg = "🗑 Email ${d.email} dicopot!"
                                        loadDestinations()
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
