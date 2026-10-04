package com.cf.dfathu.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.launch

data class PlacementRegionOption(val valKey: String, val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuntimeScreen() {
    val context = LocalContext.current
    val storage = remember { AccountStorage(context) }
    val accounts = remember { storage.getAccounts() }
    val activeIdx = remember { storage.getActiveIndex() }
    val currAcc = accounts.getOrNull(activeIdx)
    val email = AppConfig.activeEmail.ifBlank { currAcc?.email ?: "" }
    val apiKey = AppConfig.activeApiKey.ifBlank { currAcc?.apiKey ?: "" }

    var workers by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedWorker by remember { mutableStateOf("") }
    var workerDropdownExpanded by remember { mutableStateOf(false) }

    // State Placement (Sama persis dengan sc web: default, smart, region, service)
    var placementType by remember { mutableStateOf("default") } // default, smart, region, service
    var placementTypeExpanded by remember { mutableStateOf(false) }
    
    var placementProvider by remember { mutableStateOf("aws") } // aws, gcp
    var providerExpanded by remember { mutableStateOf(false) }

    var placementRegion by remember { mutableStateOf("ap-southeast-3") }
    var regionExpanded by remember { mutableStateOf(false) }

    var placementHost by remember { mutableStateOf("") }
    var isSavingPlacement by remember { mutableStateOf(false) }

    // Region dataset persis sesuai SC Web
    val awsRegions = remember {
        listOf(
            PlacementRegionOption("ap-southeast-3", "ap-southeast-3 (Jakarta)"),
            PlacementRegionOption("ap-southeast-1", "ap-southeast-1 (Singapore)"),
            PlacementRegionOption("ap-southeast-2", "ap-southeast-2 (Sydney)"),
            PlacementRegionOption("ap-east-1", "ap-east-1 (Hong Kong)"),
            PlacementRegionOption("ap-northeast-1", "ap-northeast-1 (Tokyo)"),
            PlacementRegionOption("us-east-1", "us-east-1 (N. Virginia)"),
            PlacementRegionOption("us-west-1", "us-west-1 (N. California)"),
            PlacementRegionOption("eu-central-1", "eu-central-1 (Frankfurt)")
        )
    }

    val gcpRegions = remember {
        listOf(
            PlacementRegionOption("asia-southeast2", "asia-southeast2 (Jakarta)"),
            PlacementRegionOption("asia-southeast1", "asia-southeast1 (Singapore)"),
            PlacementRegionOption("asia-east1", "asia-east1 (Taiwan)"),
            PlacementRegionOption("asia-northeast1", "asia-northeast1 (Tokyo)"),
            PlacementRegionOption("us-central1", "us-central1 (Iowa)"),
            PlacementRegionOption("europe-west1", "europe-west1 (Belgium)")
        )
    }

    // State Compatibility
    var compatDate by remember { mutableStateOf("2024-01-01") }
    var compatFlags by remember { mutableStateOf("nodejs_compat, streams_enable_constructors") }
    var isSavingCompat by remember { mutableStateOf(false) }

    var statusMsg by remember { mutableStateOf("") }
    var isLoadingSettings by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val gson = remember { Gson() }

    fun loadWorkerRuntime(workerName: String) {
        if (workerName.isBlank() || email.isBlank() || apiKey.isBlank()) return
        scope.launch {
            isLoadingSettings = true
            try {
                val accId = CfAccountHelper.ensureAccountId()
                val res = ApiClient.api.getWorkerSettings(accId, workerName)
                if (res.isSuccessful && res.body()?.success == true) {
                    val resultElement = res.body()?.result
                    val resultObj = if (resultElement?.isJsonObject == true) resultElement.asJsonObject else null

                    // 1. Baca compatibility date & flags
                    val cDate = resultObj?.get("compatibility_date")?.asString
                    if (!cDate.isNullOrBlank()) compatDate = cDate

                    val flagsArr = resultObj?.getAsJsonArray("compatibility_flags")
                    if (flagsArr != null) {
                        val flags = flagsArr.map { it.asString }
                        compatFlags = flags.joinToString(", ")
                    }

                    // 2. Baca placement lengkap sesuai logika SC Web
                    val placeObj = resultObj?.getAsJsonObject("placement")
                    val mode = placeObj?.get("mode")?.asString ?: "off"

                    when (mode) {
                        "smart" -> {
                            placementType = "smart"
                        }
                        "targeted" -> {
                            val reg = placeObj?.get("region")?.asString
                            val host = placeObj?.get("host")?.asString
                            if (!reg.isNullOrBlank()) {
                                placementType = "region"
                                when {
                                    reg.startsWith("aws:") -> {
                                        placementProvider = "aws"
                                        placementRegion = reg.removePrefix("aws:")
                                    }
                                    reg.startsWith("gcp:") -> {
                                        placementProvider = "gcp"
                                        placementRegion = reg.removePrefix("gcp:")
                                    }
                                    else -> {
                                        placementRegion = reg
                                    }
                                }
                            } else if (!host.isNullOrBlank()) {
                                placementType = "service"
                                placementHost = host
                            }
                        }
                        else -> {
                            placementType = "default"
                        }
                    }
                }
            } catch (e: Exception) {
                statusMsg = "Gagal memuat runtime: " + e.message
            } finally {
                isLoadingSettings = false
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
                        loadWorkerRuntime(list[0])
                    }
                }
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(email, apiKey) {
        if (email.isNotEmpty() && apiKey.isNotEmpty()) {
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
                    Text("⚙️ Worker Runtime & Placement", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Smart, Target Region (AWS/GCP), Compatibility Flags", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                IconButton(onClick = { if (selectedWorker.isNotBlank()) loadWorkerRuntime(selectedWorker) }, enabled = !isLoadingSettings) {
                    Text(if (isLoadingSettings) "⏳" else "🔄")
                }
            }
        }

        // PILIH WORKER TARGET
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Pilih Worker Target Setelan:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(6.dp))

                    ExposedDropdownMenuBox(
                        expanded = workerDropdownExpanded,
                        onExpandedChange = { workerDropdownExpanded = !workerDropdownExpanded }
                    ) {
                        OutlinedTextField(
                            value = selectedWorker.ifBlank { "Pilih Worker..." },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Worker") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = workerDropdownExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = workerDropdownExpanded,
                            onDismissRequest = { workerDropdownExpanded = false }
                        ) {
                            workers.forEach { w ->
                                DropdownMenuItem(
                                    text = { Text(w) },
                                    onClick = {
                                        selectedWorker = w
                                        workerDropdownExpanded = false
                                        loadWorkerRuntime(w)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // 1. PLACEMENT CARD (LENGKAP DENGAN PILIHAN REGION AWS/GCP PERSIS SC WEB)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Placement", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(10.dp))

                    // Dropdown Mode Placement
                    ExposedDropdownMenuBox(
                        expanded = placementTypeExpanded,
                        onExpandedChange = { placementTypeExpanded = !placementTypeExpanded }
                    ) {
                        val displayType = when (placementType) {
                            "smart" -> "Smart (Automatic low-latency optimization)"
                            "region" -> "Region (Target spesifik cloud vendor)"
                            "service" -> "Service (Target spesifik hostname)"
                            else -> "Default (Close to end-users)"
                        }
                        OutlinedTextField(
                            value = displayType,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Placement Type") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = placementTypeExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = placementTypeExpanded,
                            onDismissRequest = { placementTypeExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Default (Close to end-users)") },
                                onClick = {
                                    placementType = "default"
                                    placementTypeExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Smart (Automatic low-latency optimization)") },
                                onClick = {
                                    placementType = "smart"
                                    placementTypeExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Region (Target spesifik cloud vendor)") },
                                onClick = {
                                    placementType = "region"
                                    placementTypeExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Service (Target spesifik hostname)") },
                                onClick = {
                                    placementType = "service"
                                    placementTypeExpanded = false
                                }
                            )
                        }
                    }

                    // TAMPILKAN PILIHAN REGION JIKA TYPE == REGION
                    if (placementType == "region") {
                        Spacer(modifier = Modifier.height(10.dp))

                        // Provider Dropdown (AWS / GCP)
                        ExposedDropdownMenuBox(
                            expanded = providerExpanded,
                            onExpandedChange = { providerExpanded = !providerExpanded }
                        ) {
                            val providerLabel = if (placementProvider == "gcp") "Google Cloud Platform (GCP)" else "Amazon Web Services (AWS)"
                            OutlinedTextField(
                                value = providerLabel,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Cloud Provider") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = providerExpanded,
                                onDismissRequest = { providerExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Amazon Web Services (AWS)") },
                                    onClick = {
                                        placementProvider = "aws"
                                        placementRegion = "ap-southeast-3" // default Jakarta AWS
                                        providerExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Google Cloud Platform (GCP)") },
                                    onClick = {
                                        placementProvider = "gcp"
                                        placementRegion = "asia-southeast2" // default Jakarta GCP
                                        providerExpanded = false
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Region Dropdown
                        val activeRegionList = if (placementProvider == "gcp") gcpRegions else awsRegions
                        val currentRegionLabel = activeRegionList.find { it.valKey == placementRegion }?.label ?: placementRegion

                        ExposedDropdownMenuBox(
                            expanded = regionExpanded,
                            onExpandedChange = { regionExpanded = !regionExpanded }
                        ) {
                            OutlinedTextField(
                                value = currentRegionLabel,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Region Target") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = regionExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = regionExpanded,
                                onDismissRequest = { regionExpanded = false }
                            ) {
                                activeRegionList.forEach { opt ->
                                    DropdownMenuItem(
                                        text = { Text(opt.label) },
                                        onClick = {
                                            placementRegion = opt.valKey
                                            regionExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // TAMPILKAN INPUT HOST JIKA TYPE == SERVICE
                    if (placementType == "service") {
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = placementHost,
                            onValueChange = { placementHost = it.trim() },
                            label = { Text("External Service Hostname / Host:Port") },
                            placeholder = { Text("db.domain.com:5432") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = {
                            val w = selectedWorker
                            if (w.isBlank()) return@Button
                            scope.launch {
                                isSavingPlacement = true
                                statusMsg = "Menyimpan pengaturan Placement Region..."
                                try {
                                    val accId = CfAccountHelper.ensureAccountId()

                                    // SUSUN PAYLOAD SAMA PERSIS DENGAN SC WEB
                                    val placementObj: Map<String, Any> = when (placementType) {
                                        "smart" -> mapOf("mode" to "smart")
                                        "region" -> mapOf(
                                            "mode" to "targeted",
                                            "region" to (placementProvider + ":" + placementRegion)
                                        )
                                        "service" -> mapOf(
                                            "mode" to "targeted",
                                            "host" to placementHost
                                        )
                                        else -> mapOf("mode" to "off")
                                    }

                                    val payload = mapOf("placement" to placementObj)
                                    val res = ApiClient.api.updateWorkerSettings(accId, w, payload)
                                    if (res.isSuccessful && res.body()?.success == true) {
                                        val displayTypeStr = placementType.uppercase()
                                        statusMsg = "💾 Berhasil menyimpan Placement (" + displayTypeStr + ")!"
                                        loadWorkerRuntime(w)
                                    } else {
                                        val err = res.body()?.errors?.firstOrNull()?.message ?: ("HTTP " + res.code())
                                        statusMsg = "Gagal: " + err
                                    }
                                } catch (e: Exception) {
                                    statusMsg = "Error: " + e.message
                                } finally {
                                    isSavingPlacement = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                        enabled = !isSavingPlacement && selectedWorker.isNotBlank()
                    ) {
                        Text(if (isSavingPlacement) "Menyimpan..." else "💾 Simpan Placement Region")
                    }
                }
            }
        }

        // 2. COMPATIBILITY CARD
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Compatibility", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = compatDate,
                        onValueChange = { compatDate = it.trim() },
                        label = { Text("Compatibility date (YYYY-MM-DD)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = compatFlags,
                        onValueChange = { compatFlags = it },
                        label = { Text("Compatibility flags") },
                        placeholder = { Text("nodejs_compat, streams_enable_constructors") },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Pisahkan dengan koma (contoh: nodejs_compat, streams_enable_constructors).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = {
                            val w = selectedWorker
                            if (w.isBlank()) return@Button
                            scope.launch {
                                isSavingCompat = true
                                statusMsg = "Menyimpan pengaturan Compatibility..."
                                try {
                                    val accId = CfAccountHelper.ensureAccountId()
                                    val parsedFlags = compatFlags.split(",")
                                        .map { it.trim() }
                                        .filter { it.isNotBlank() }

                                    val payload = mapOf(
                                        "compatibility_date" to compatDate,
                                        "compatibility_flags" to parsedFlags
                                    )
                                    val res = ApiClient.api.updateWorkerSettings(accId, w, payload)
                                    if (res.isSuccessful && res.body()?.success == true) {
                                        statusMsg = "💾 Berhasil menyimpan Compatibility!"
                                    } else {
                                        val err = res.body()?.errors?.firstOrNull()?.message ?: ("HTTP " + res.code())
                                        statusMsg = "Gagal: " + err
                                    }
                                } catch (e: Exception) {
                                    statusMsg = "Error: " + e.message
                                } finally {
                                    isSavingCompat = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSavingCompat && selectedWorker.isNotBlank()
                    ) {
                        Text(if (isSavingCompat) "Menyimpan..." else "💾 Simpan Compatibility")
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
}
