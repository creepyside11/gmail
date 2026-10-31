package com.gmailfarmer.app

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GmailFarmerApp() }
    }
}

private data class ReviewPreset(val title: String)

private class PresetStore(context: Context) {
    private val preferences = context.getSharedPreferences("review_presets", Context.MODE_PRIVATE)

    fun read(): List<ReviewPreset> = preferences.getStringSet(KEY, emptySet())
        .orEmpty().toList().sorted().map(::ReviewPreset)

    fun add(title: String) {
        val next = preferences.getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
        next.add(title.trim())
        preferences.edit().putStringSet(KEY, next).apply()
    }

    fun remove(title: String) {
        val next = preferences.getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
        next.remove(title)
        preferences.edit().putStringSet(KEY, next).apply()
    }

    companion object { private const val KEY = "titles" }
}

private sealed interface AccountsState {
    data object Loading : AccountsState
    data class Ready(val accounts: List<Account>) : AccountsState
    data object PermissionRequired : AccountsState
    data object Error : AccountsState
}

private suspend fun loadGoogleAccounts(context: Context): AccountsState = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.GET_ACCOUNTS) != PackageManager.PERMISSION_GRANTED
    ) return@withContext AccountsState.PermissionRequired

    try {
        AccountsState.Ready(AccountManager.get(context).getAccountsByType("com.google").toList())
    } catch (_: SecurityException) {
        AccountsState.PermissionRequired
    } catch (_: RuntimeException) {
        AccountsState.Error
    }
}

@Composable
private fun GmailFarmerApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val presetStore = remember { PresetStore(context) }
    val chooserLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    var tab by remember { mutableIntStateOf(0) }
    var refreshToken by remember { mutableIntStateOf(0) }
    var accountsState by remember { mutableStateOf<AccountsState>(AccountsState.Loading) }
    var query by remember { mutableStateOf("") }
    var pendingAccount by remember { mutableStateOf<Account?>(null) }
    var settingsUnavailable by remember { mutableStateOf(false) }
    var presets by remember { mutableStateOf(presetStore.read()) }
    var showPresetEditor by remember { mutableStateOf(false) }
    var newPresetTitle by remember { mutableStateOf("") }

    fun refresh() { refreshToken++ }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(refreshToken) {
        accountsState = AccountsState.Loading
        accountsState = loadGoogleAccounts(context)
    }

    pendingAccount?.let { account ->
        AlertDialog(
            onDismissRequest = { pendingAccount = null },
            title = { Text("Manage account in Android Settings?") },
            text = { Text("Android Settings will ask you to confirm. Removing an account from this device can stop sync and sign you out of apps; it does not delete the Google account.\n\n${maskEmail(account.name)}") },
            confirmButton = {
                TextButton(onClick = {
                    pendingAccount = null
                    try {
                        context.startActivity(Intent(Settings.ACTION_SYNC_SETTINGS))
                    } catch (_: ActivityNotFoundException) {
                        try { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                        catch (_: ActivityNotFoundException) { settingsUnavailable = true }
                    }
                }) { Text("Open Settings") }
            },
            dismissButton = { TextButton(onClick = { pendingAccount = null }) { Text("Cancel") } }
        )
    }

    if (settingsUnavailable) {
        AlertDialog(
            onDismissRequest = { settingsUnavailable = false },
            title = { Text("Settings unavailable") },
            text = { Text("Open Android Settings manually, then choose Passwords & accounts > Google > the account > Remove account.") },
            confirmButton = { TextButton(onClick = { settingsUnavailable = false }) { Text("Done") } }
        )
    }

    if (showPresetEditor) {
        AlertDialog(
            onDismissRequest = { showPresetEditor = false },
            title = { Text("New review checklist") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Manual only. This checklist never creates, removes, signs into, or changes accounts.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = newPresetTitle,
                        onValueChange = { newPresetTitle = it },
                        label = { Text("Checklist name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = newPresetTitle.isNotBlank(), onClick = {
                    presetStore.add(newPresetTitle)
                    presets = presetStore.read()
                    newPresetTitle = ""
                    showPresetEditor = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showPresetEditor = false }) { Text("Cancel") } }
        )
    }

    val background = Brush.linearGradient(listOf(Color(0xFF111416), Color(0xFF292D31), Color(0xFF171A1D)))
    MaterialTheme(colorScheme = glassColors()) {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                NavigationBar(containerColor = Color(0xDD17191B)) {
                    NavigationBarItem(tab == 0, { tab = 0 }, icon = { Icon(Icons.Outlined.AccountCircle, null) }, label = { Text("Accounts") })
                    NavigationBarItem(tab == 1, { tab = 1 }, icon = { Icon(Icons.Outlined.FactCheck, null) }, label = { Text("Checklists") })
                    NavigationBarItem(tab == 2, { tab = 2 }, icon = { Icon(Icons.Outlined.Lock, null) }, label = { Text("Privacy") })
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().background(background).padding(padding)) {
                when (tab) {
                    0 -> AccountsScreen(
                        state = accountsState,
                        query = query,
                        onQueryChange = { query = it },
                        onRefresh = { refresh() },
                        onPermission = { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) permissionLauncher.launch(Manifest.permission.GET_ACCOUNTS) },
                        onChoose = {
                            chooserLauncher.launch(AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), "Choose a Google account to review", null, null, null))
                        },
                        onManage = { pendingAccount = it }
                    )
                    1 -> ChecklistsScreen(presets, { showPresetEditor = true }) {
                        presetStore.remove(it.title)
                        presets = presetStore.read()
                    }
                    else -> PrivacyScreen()
                }
            }
        }
    }
}

@Composable
private fun AccountsScreen(state: AccountsState, query: String, onQueryChange: (String) -> Unit, onRefresh: () -> Unit, onPermission: () -> Unit, onChoose: () -> Unit, onManage: (Account) -> Unit) {
    val accounts = (state as? AccountsState.Ready)?.accounts.orEmpty().filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 18.dp)) {
        Header("GMAIL FARMER", "Google accounts", "Visible to this app on the current Android profile") { IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, "Refresh") } }
        Spacer(Modifier.height(14.dp))
        GlassPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Info, null, tint = Color(0xFFB8C0C7))
                Spacer(Modifier.width(10.dp))
                Text("Android may hide accounts from apps. This screen never reads mail or credentials.", style = MaterialTheme.typography.bodySmall, color = Color(0xFFD0D5D9))
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(query, onQueryChange, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Filter visible accounts") })
        Spacer(Modifier.height(12.dp))
        when (state) {
            AccountsState.Loading -> CenterMessage("Loading visible accounts…")
            AccountsState.PermissionRequired -> EmptyAccounts("Permission needed", "Allow account visibility so the app can show Google account names exposed by Android.", "Allow access", onPermission)
            AccountsState.Error -> EmptyAccounts("Accounts unavailable", "Android did not expose the account list. Try again or review the current profile settings.", "Try again", onRefresh)
            is AccountsState.Ready -> if (accounts.isEmpty()) {
                EmptyAccounts(if (query.isBlank()) "No visible Google accounts" else "No matches", "Android controls which accounts apps can see. You can choose one account to review without creating or changing it.", if (query.isBlank()) "Choose account to review" else "Clear filter") { if (query.isBlank()) onChoose() else onQueryChange("") }
            } else {
                Text("${accounts.size} visible", style = MaterialTheme.typography.labelLarge, color = Color(0xFFB8C0C7))
                Spacer(Modifier.height(6.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) { items(accounts, key = { it.name }) { account -> AccountRow(account, onManage) } }
            }
        }
    }
}

@Composable
private fun AccountRow(account: Account, onManage: (Account) -> Unit) {
    GlassPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF3A4147)) { Icon(Icons.Outlined.AccountCircle, null, Modifier.padding(10.dp), tint = Color(0xFFE0E4E7)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(maskEmail(account.name), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Google account • visible now", style = MaterialTheme.typography.bodySmall, color = Color(0xFFAEB6BD))
            }
            IconButton(onClick = { onManage(account) }) { Icon(Icons.Outlined.Settings, "Manage in Android settings") }
        }
    }
}

@Composable
private fun ChecklistsScreen(presets: List<ReviewPreset>, onAdd: () -> Unit, onDelete: (ReviewPreset) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 18.dp)) {
        Header("MANUAL REVIEW", "Checklists", "Saved local notes for repeatable account reviews") { IconButton(onClick = onAdd) { Icon(Icons.Outlined.Add, "New checklist") } }
        Spacer(Modifier.height(14.dp))
        GlassPanel {
            Text("Manual only", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("These checklists never create, remove, sign into, or change accounts. They have no run button and no automation payload.", style = MaterialTheme.typography.bodySmall, color = Color(0xFFD0D5D9))
        }
        Spacer(Modifier.height(12.dp))
        if (presets.isEmpty()) EmptyAccounts("No checklists yet", "Create a short manual review note for your own device workflow.", "New checklist", onAdd)
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { items(presets, key = { it.title }) { preset ->
            GlassPanel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, null, tint = Color(0xFFBFC7CD))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text(preset.title, fontWeight = FontWeight.SemiBold); Text("Local note • manual steps only", style = MaterialTheme.typography.bodySmall, color = Color(0xFFAEB6BD)) }
                    IconButton(onClick = { onDelete(preset) }) { Icon(Icons.Outlined.DeleteOutline, "Delete checklist") }
                }
            }
        } }
    }
}

@Composable
private fun PrivacyScreen() {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 18.dp)) {
        Header("DEVICE FIRST", "Privacy", "What Gmail Farmer can and cannot see")
        Spacer(Modifier.height(14.dp))
        GlassPanel {
            PrivacyRow("Account scope", "Only Google accounts that Android exposes to this app for the current user/profile.")
            Divider(Modifier.padding(vertical = 12.dp), color = Color(0x337A838A))
            PrivacyRow("Data access", "Account names are held in memory for the current screen. The app does not read mail, passwords, tokens or contacts.")
            Divider(Modifier.padding(vertical = 12.dp), color = Color(0x337A838A))
            PrivacyRow("Removal", "The app never removes accounts directly. Android Settings owns the confirmation and device policy.")
            Divider(Modifier.padding(vertical = 12.dp), color = Color(0x337A838A))
            PrivacyRow("Storage", "Only checklist titles are stored locally. No account identifiers are written to presets or sent over a network.")
        }
    }
}

@Composable private fun PrivacyRow(label: String, value: String) { Column(verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(label, style = MaterialTheme.typography.labelLarge, color = Color(0xFFB8C0C7)); Text(value, style = MaterialTheme.typography.bodyMedium) } }

@Composable private fun Header(eyebrow: String, title: String, subtitle: String, action: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) { Column(Modifier.weight(1f)) { Text(eyebrow, style = MaterialTheme.typography.labelSmall, color = Color(0xFF98A2AA)); Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color(0xFFAEB6BD)) }; action?.invoke() }
}

@Composable private fun GlassPanel(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = Color(0xB31F2428)), border = BorderStroke(1.dp, Color(0x447E8991))) {
        Column(Modifier.padding(14.dp)) { content() }
    }
}

@Composable private fun EmptyAccounts(title: String, body: String, actionLabel: String, onAction: () -> Unit) {
    GlassPanel { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text(body, style = MaterialTheme.typography.bodySmall, color = Color(0xFFBFC7CD)); Button(onClick = onAction, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD8DEE3), contentColor = Color(0xFF1B1E20))) { Text(actionLabel); Spacer(Modifier.width(6.dp)); Icon(Icons.Outlined.ArrowForward, null, Modifier.size(18.dp)) } } }
}

@Composable private fun CenterMessage(message: String) { Box(Modifier.fillMaxWidth().padding(top = 28.dp), contentAlignment = Alignment.Center) { Text(message, color = Color(0xFFB8C0C7)) } }

private fun maskEmail(email: String): String { val separator = email.indexOf('@'); if (separator <= 0) return "***"; return "${email.substring(0, separator).take(2)}***@${email.substring(separator + 1)}" }

private fun glassColors() = androidx.compose.material3.darkColorScheme(primary = Color(0xFFD8DEE3), onPrimary = Color(0xFF1A1D20), secondary = Color(0xFFB7C0C7), onSecondary = Color(0xFF1A1D20), background = Color(0xFF151719), onBackground = Color(0xFFE8EBED), surface = Color(0xFF202427), onSurface = Color(0xFFE8EBED), surfaceVariant = Color(0xFF353B40), onSurfaceVariant = Color(0xFFC6CDD2))
