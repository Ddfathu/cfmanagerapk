package com.cf.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cf.manager.data.AppConfig
import com.cf.manager.data.api.ApiClient
import com.cf.manager.data.api.CfAccountHelper
import com.cf.manager.data.local.AccountStorage
import com.cf.manager.data.model.CfAccount
import com.cf.manager.ui.screens.*
import kotlinx.coroutines.launch

enum class MainCategory(val label: String, val icon: String) {
    COMPUTE("Compute", "⚡"),
    NETWORK("Network", "🌐"),
    STORAGE("Storage", "💿"),
    CONFIG("Config", "🛠️")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val storage = remember { AccountStorage(context) }
    val scope = rememberCoroutineScope()

    var accounts by remember {
        val list = storage.getAccounts()
        if (list.isEmpty()) {
            mutableStateOf(mutableListOf(CfAccount(alias = "Utama", email = "", apiKey = "")))
        } else {
            mutableStateOf(list)
        }
    }
    var activeIdx by remember {
        val saved = storage.getActiveIndex()
        mutableStateOf(if (saved in accounts.indices) saved else 0)
    }

    // 4 Kategori Utama di Bottom Bar (Konsep 1)
    var activeCategory by remember { mutableStateOf(MainCategory.COMPUTE) }

    // Sub-tab di dalam setiap kategori
    var computeSubTab by remember { mutableStateOf(0) }   // 0: Worker, 1: Pages, 2: Runtime
    var networkSubTab by remember { mutableStateOf(0) }   // 0: Domain, 1: DNS, 2: Tunnel, 3: SSL
    var storageSubTab by remember { mutableStateOf(0) }   // 0: R2 Storage
    var configSubTab by remember { mutableStateOf(0) }    // 0: Variables, 1: Email Routing

    // Modal Bottom Sheet Akun Cepat di Atas
    var showAccountSheet by remember { mutableStateOf(false) }

    // Dialog Tambah Akun
    var showAddDialog by remember { mutableStateOf(false) }
    var newAlias by remember { mutableStateOf("") }
    var newEmail by remember { mutableStateOf("") }
    var newApiKey by remember { mutableStateOf("") }

    // Dialog Edit Akun
    var showEditDialog by remember { mutableStateOf(false) }
    var editTargetIdx by remember { mutableStateOf(-1) }
    var editAlias by remember { mutableStateOf("") }
    var editEmail by remember { mutableStateOf("") }
    var editApiKey by remember { mutableStateOf("") }

    var accountStatusNotice by remember { mutableStateOf("") }

    val activeAccount = accounts.getOrElse(activeIdx) { CfAccount(alias = "Default", email = "", apiKey = "") }

    fun refreshActiveSession(acc: CfAccount) {
        AppConfig.activeEmail = acc.email
        AppConfig.activeApiKey = acc.apiKey
        ApiClient.activeAccountId = ""
        scope.launch {
            if (acc.email.isNotBlank() && acc.apiKey.isNotBlank()) {
                val accId = CfAccountHelper.ensureAccountId()
                accountStatusNotice = if (accId.isNotBlank()) {
                    "✅ ID: " + accId.take(12) + "..."
                } else {
                    "⚠️ Kredensial tidak valid"
                }
            } else {
                accountStatusNotice = "⚠️ Belum ada API Key"
            }
        }
    }

    LaunchedEffect(activeAccount) {
        refreshActiveSession(activeAccount)
    }

    Scaffold(
        topBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 3.dp
            ) {
                Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
                    // BARIS 1: LOGO BRAND & AVATAR AKUN MODERN DI KANAN ATAS
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "CF Manager",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFFF6821F)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFFEEF2FF)
                                ) {
                                    Text(
                                        text = "PRO",
                                        color = Color(0xFF4F46E5),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = if (accountStatusNotice.isNotBlank()) accountStatusNotice else "Cloudflare Native API",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }

                        // TOMBOL AKUN ELEGAN DI POJOK KANAN ATAS
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(24.dp))
                                .clickable { showAccountSheet = true },
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            tonalElevation = 2.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val initial = (activeAccount.alias.firstOrNull() ?: activeAccount.email.firstOrNull() ?: 'U').uppercaseChar().toString()
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(
                                            Brush.linearGradient(
                                                listOf(Color(0xFFF97316), Color(0xFFEA580C))
                                            )
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = initial,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                Column {
                                    Text(
                                        text = activeAccount.alias,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = if (activeAccount.email.isNotBlank()) activeAccount.email.take(12) + "..." else "Ganti Akun",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        maxLines = 1
                                    )
                                }

                                Spacer(modifier = Modifier.width(4.dp))
                                Text("▾", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }

                    // BARIS 2: SUB-NAVIGATION SESUAI KATEGORI AKTIF
                    when (activeCategory) {
                        MainCategory.COMPUTE -> {
                            val computeTabs = listOf("⚡ Workers", "📄 Pages", "⚙️ Runtime")
                            TabRow(
                                selectedTabIndex = computeSubTab,
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.primary
                            ) {
                                computeTabs.forEachIndexed { i, title ->
                                    Tab(
                                        selected = computeSubTab == i,
                                        onClick = { computeSubTab = i },
                                        text = { Text(title, fontWeight = if (computeSubTab == i) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.bodySmall) }
                                    )
                                }
                            }
                        }
                        MainCategory.NETWORK -> {
                            val networkTabs = listOf("🌐 Domain", "🛰 DNS", "🚇 Tunnel", "🔒 SSL/TLS")
                            TabRow(
                                selectedTabIndex = networkSubTab,
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.primary
                            ) {
                                networkTabs.forEachIndexed { i, title ->
                                    Tab(
                                        selected = networkSubTab == i,
                                        onClick = { networkSubTab = i },
                                        text = { Text(title, fontWeight = if (networkSubTab == i) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.bodySmall) }
                                    )
                                }
                            }
                        }
                        MainCategory.STORAGE -> {
                            val storageTabs = listOf("💿 R2 Buckets & Bindings")
                            TabRow(
                                selectedTabIndex = storageSubTab,
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.primary
                            ) {
                                storageTabs.forEachIndexed { i, title ->
                                    Tab(
                                        selected = storageSubTab == i,
                                        onClick = { storageSubTab = i },
                                        text = { Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall) }
                                    )
                                }
                            }
                        }
                        MainCategory.CONFIG -> {
                            val configTabs = listOf("🔑 Variables & Secrets", "✉️ Email Routing")
                            TabRow(
                                selectedTabIndex = configSubTab,
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.primary
                            ) {
                                configTabs.forEachIndexed { i, title ->
                                    Tab(
                                        selected = configSubTab == i,
                                        onClick = { configSubTab = i },
                                        text = { Text(title, fontWeight = if (configSubTab == i) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.bodySmall) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 4 NAVIGATION BAR UTAMA (MATERIAL 3 NAVIGATION BAR)
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp
                ) {
                    MainCategory.values().forEach { cat ->
                        val isSelected = (activeCategory == cat)
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = { activeCategory = cat },
                            icon = {
                                Text(
                                    text = cat.icon,
                                    style = MaterialTheme.typography.titleMedium
                                )
                            },
                            label = {
                                Text(
                                    text = cat.label,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = Color(0xFFFFF7ED)
                            )
                        )
                    }
                }

                // FOOTER WATERMARK PERMANEN DEDE FATHU
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = "🚀 Aplikasi ini di-build oleh Dede Fathu",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (activeCategory) {
                MainCategory.COMPUTE -> {
                    when (computeSubTab) {
                        0 -> WorkerEditorScreen()
                        1 -> PagesScreen()
                        2 -> RuntimeScreen()
                    }
                }
                MainCategory.NETWORK -> {
                    when (networkSubTab) {
                        0 -> DomainScreen()
                        1 -> DnsScreen()
                        2 -> TunnelScreen()
                        3 -> SslScreen()
                    }
                }
                MainCategory.STORAGE -> {
                    when (storageSubTab) {
                        0 -> R2Screen()
                    }
                }
                MainCategory.CONFIG -> {
                    when (configSubTab) {
                        0 -> VariablesScreen()
                        1 -> EmailScreen()
                    }
                }
            }
        }
    }

    // MODAL BOTTOM SHEET SWITCH & KELOLA AKUN
    if (showAccountSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAccountSheet = false },
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("👥 Pilih Akun Cloudflare", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Ganti profil akun dengan 1-klik instan", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }

                    Button(
                        onClick = {
                            newAlias = "Akun " + (accounts.size + 1)
                            newEmail = ""
                            newApiKey = ""
                            showAddDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("+ Tambah Akun")
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(accounts) { index, acc ->
                        val isSelected = (index == activeIdx)
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    activeIdx = index
                                    storage.setActiveIndex(index)
                                    refreshActiveSession(acc)
                                    showAccountSheet = false
                                },
                            color = if (isSelected) Color(0xFFFFF7ED) else MaterialTheme.colorScheme.surfaceVariant,
                            border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFF97316)) else null,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val accInitial = (acc.alias.firstOrNull() ?: 'U').uppercaseChar().toString()
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isSelected) Color(0xFFEA580C) else Color(0xFF64748B)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = accInitial,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = acc.alias,
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.titleSmall
                                            )
                                            if (isSelected) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = Color(0xFFEA580C)
                                                ) {
                                                    Text(
                                                        text = "AKTIF",
                                                        color = Color.White,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = if (acc.email.isNotBlank()) acc.email else "Email belum diisi",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                        Text(
                                            text = "Key: " + (if (acc.apiKey.length > 8) acc.apiKey.take(6) + "••••••••" else "(Kosong)"),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = {
                                        editTargetIdx = index
                                        editAlias = acc.alias
                                        editEmail = acc.email
                                        editApiKey = acc.apiKey
                                        showEditDialog = true
                                    }) {
                                        Text("✏️")
                                    }

                                    if (accounts.size > 1) {
                                        IconButton(onClick = {
                                            val updated = accounts.toMutableList().apply { removeAt(index) }
                                            accounts = updated
                                            storage.saveAccounts(updated)
                                            if (activeIdx >= updated.size) {
                                                activeIdx = 0
                                                storage.setActiveIndex(0)
                                            }
                                            val curr = updated[activeIdx]
                                            refreshActiveSession(curr)
                                        }) {
                                            Text("🗑")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // DIALOG TAMBAH AKUN
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Tambah Akun Cloudflare", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = newAlias,
                        onValueChange = { newAlias = it },
                        label = { Text("Nama Panggilan / Alias (cth: Akun Utama)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = newEmail,
                        onValueChange = { newEmail = it.trim() },
                        label = { Text("Email Cloudflare") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = newApiKey,
                        onValueChange = { newApiKey = it.trim() },
                        label = { Text("Global API Key / API Token") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newAlias.isNotBlank()) {
                            val list = accounts.toMutableList()
                            val newAcc = CfAccount(alias = newAlias, email = newEmail, apiKey = newApiKey)
                            list.add(newAcc)
                            accounts = list
                            storage.saveAccounts(list)
                            activeIdx = list.size - 1
                            storage.setActiveIndex(activeIdx)
                            refreshActiveSession(newAcc)
                            showAddDialog = false
                            showAccountSheet = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A))
                ) {
                    Text("Simpan & Aktifkan")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }

    // DIALOG EDIT AKUN
    if (showEditDialog && editTargetIdx in accounts.indices) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Edit Akun: $editAlias", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = editAlias,
                        onValueChange = { editAlias = it },
                        label = { Text("Nama Alias") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = editEmail,
                        onValueChange = { editEmail = it.trim() },
                        label = { Text("Email Cloudflare") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = editApiKey,
                        onValueChange = { editApiKey = it.trim() },
                        label = { Text("Global API Key / API Token") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val list = accounts.toMutableList()
                    list[editTargetIdx] = list[editTargetIdx].copy(
                        alias = editAlias,
                        email = editEmail,
                        apiKey = editApiKey
                    )
                    accounts = list
                    storage.saveAccounts(list)
                    if (editTargetIdx == activeIdx) {
                        refreshActiveSession(list[editTargetIdx])
                    }
                    showEditDialog = false
                }) {
                    Text("Simpan")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }
}
