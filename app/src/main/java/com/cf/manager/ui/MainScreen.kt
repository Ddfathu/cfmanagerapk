package com.cf.manager.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.cf.manager.data.model.CfAccount
import com.cf.manager.ui.screens.*
import kotlinx.coroutines.launch

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

    var selectedTab by remember { mutableStateOf(0) }
    val tabTitles = listOf("⚡ Worker", "🔑 Vars", "📄 Pages", "🌐 DNS", "🔒 SSL", "🚇 Tunnel", "✉️ Email", "⚙ Akun")

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
                    "✅ Terhubung ke CF Account ID: $accId"
                } else {
                    "⚠️ Kredensial belum valid / gagal terhubung ke Cloudflare."
                }
            }
        }
    }

    LaunchedEffect(activeAccount) {
        refreshActiveSession(activeAccount)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("CF Manager (Direct Native)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            text = "Akun Aktif: ${activeAccount.alias} (${activeAccount.email.ifBlank { "Belum diisi" }})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                ScrollableTabRow(selectedTabIndex = selectedTab) {
                    tabTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) }
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
            when (selectedTab) {
                0 -> WorkerEditorScreen()
                1 -> VariablesScreen()
                2 -> PagesScreen()
                3 -> DnsScreen()
                4 -> SslScreen()
                5 -> TunnelScreen()
                6 -> EmailScreen()
                7 -> {
                    // TAB PENGATURAN AKUN RESMI DIRECT
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item {
                            Text("⚙️️ Pengaturan Akun Cloudflare", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("Aplikasi terhubung langsung ke https://api.cloudflare.com/client/v4/", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

                            if (accountStatusNotice.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Text(
                                        text = accountStatusNotice,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(8.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Daftar Akun (${accounts.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Button(onClick = {
                                    newAlias = "Akun ${accounts.size + 1}"
                                    newEmail = ""
                                    newApiKey = ""
                                    showAddDialog = true
                                }) {
                                    Text("➕ Tambah Akun")
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Ketuk salah satu kartu akun untuk mengaktifkannya:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }

                        itemsIndexed(accounts) { index, acc ->
                            val isSelected = (index == activeIdx)
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        activeIdx = index
                                        storage.setActiveIndex(index)
                                        refreshActiveSession(acc)
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (isSelected) "✔ ${acc.alias} [AKTIF]" else acc.alias,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(text = "Email: ${acc.email.ifBlank { "(Kosong)" }}", style = MaterialTheme.typography.bodySmall)
                                        Text(
                                            text = "Key: ${if (acc.apiKey.length > 8) acc.apiKey.take(8) + "••••••••" else "(Kosong)"}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
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

                    if (showAddDialog) {
                        AlertDialog(
                            onDismissRequest = { showAddDialog = false },
                            title = { Text("Tambah Akun Cloudflare", fontWeight = FontWeight.Bold) },
                            text = {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    OutlinedTextField(
                                        value = newAlias,
                                        onValueChange = { newAlias = it },
                                        label = { Text("Nama Alias (cth: Akun 2)") },
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
                                Button(onClick = {
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
                                    }
                                }) {
                                    Text("Simpan")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showAddDialog = false }) {
                                    Text("Batal")
                                }
                            }
                        )
                    }

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
            }
        }
    }
}
