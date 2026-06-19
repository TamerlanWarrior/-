package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                NotesApp()
            }
        }
    }
}

sealed interface Screen {
    object ListScreen : Screen
    data class EditScreen(val index: Int, val initialContent: String) : Screen
}

class NotesViewModel : ViewModel() {
    private val _notes = MutableStateFlow<List<String>>(emptyList())
    val notes: StateFlow<List<String>> = _notes.asStateFlow()

    private val _isStoragePermissionGranted = MutableStateFlow(false)
    val isStoragePermissionGranted: StateFlow<Boolean> = _isStoragePermissionGranted.asStateFlow()

    private val _isUsingFallback = MutableStateFlow(false)
    val isUsingFallback: StateFlow<Boolean> = _isUsingFallback.asStateFlow()

    private val _infoMessage = MutableStateFlow<String?>(null)
    val infoMessage: StateFlow<String?> = _infoMessage.asStateFlow()

    private val _currentScreen = MutableStateFlow<Screen>(Screen.ListScreen)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    fun navigateTo(screen: Screen) {
        _currentScreen.value = screen
    }

    fun getTargetFile(): File {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(downloadDir, "Notes.txt")
    }

    fun getFallbackFile(context: Context): File {
        return File(context.filesDir, "Notes_Internal_Backup.txt")
    }

    fun checkPermissions(context: Context) {
        val isGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
        _isStoragePermissionGranted.value = isGranted
        loadNotes(context)
    }

    fun loadNotes(context: Context) {
        val target = getTargetFile()
        val fallback = getFallbackFile(context)

        try {
            if (_isStoragePermissionGranted.value) {
                _isUsingFallback.value = false
                if (target.exists()) {
                    val content = target.readText(Charsets.UTF_8)
                    _notes.value = parseNotes(content)
                } else {
                    if (fallback.exists() && _notes.value.isEmpty()) {
                        val fallbackContent = fallback.readText(Charsets.UTF_8)
                        val migratedNotes = parseNotes(fallbackContent)
                        if (migratedNotes.isNotEmpty()) {
                            _notes.value = migratedNotes
                            saveNotes(context, migratedNotes)
                            _infoMessage.value = "Заметки перенесены в Download/Notes.txt"
                        } else {
                            _notes.value = emptyList()
                        }
                    } else {
                        _notes.value = emptyList()
                    }
                }
            } else {
                _isUsingFallback.value = true
                if (fallback.exists()) {
                    val content = fallback.readText(Charsets.UTF_8)
                    _notes.value = parseNotes(content)
                } else {
                    _notes.value = emptyList()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            _infoMessage.value = "Ошибка при загрузке: ${e.localizedMessage}"
            _isUsingFallback.value = true
            if (fallback.exists()) {
                try {
                    _notes.value = parseNotes(fallback.readText(Charsets.UTF_8))
                } catch (ex: Exception) {
                    _notes.value = emptyList()
                }
            }
        }
    }

    private fun parseNotes(content: String): List<String> {
        if (content.isBlank()) return emptyList()
        val rawBlocks = content.split(Regex("(?m)^\\*{3,}\\s*$"))
        return rawBlocks.map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun saveNotes(context: Context, notesList: List<String>): Boolean {
        _notes.value = notesList
        val target = getTargetFile()
        val fallback = getFallbackFile(context)
        val joinedContent = notesList.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n****************************************\n\n")

        try {
            fallback.writeText(joinedContent, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (_isStoragePermissionGranted.value) {
            try {
                val parent = target.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                }
                target.writeText(joinedContent, Charsets.UTF_8)
                _isUsingFallback.value = false
                return true
            } catch (e: Exception) {
                e.printStackTrace()
                _infoMessage.value = "Ошибка записи в Download/Notes.txt. Сохранено локально."
                _isUsingFallback.value = true
                return false
            }
        } else {
            _isUsingFallback.value = true
            return false
        }
    }

    fun clearInfoMessage() {
        _infoMessage.value = null
    }

    fun updateNote(context: Context, index: Int, text: String) {
        val current = _notes.value.toMutableList()
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return

        if (index >= 0 && index < current.size) {
            current[index] = cleanText
        } else {
            current.add(cleanText)
        }
        saveNotes(context, current)
    }

    fun deleteNoteAt(context: Context, index: Int) {
        val current = _notes.value.toMutableList()
        if (index >= 0 && index < current.size) {
            current.removeAt(index)
            saveNotes(context, current)
        }
    }
}

@Composable
fun NotesApp(viewModel: NotesViewModel = viewModel()) {
    val context = LocalContext.current
    val currentScreen by viewModel.currentScreen.collectAsState()
    val notes by viewModel.notes.collectAsState()
    val isPermissionGranted by viewModel.isStoragePermissionGranted.collectAsState()
    val isUsingFallback by viewModel.isUsingFallback.collectAsState()
    val infoMessage by viewModel.infoMessage.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.checkPermissions(context)
    }

    val legacyPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            viewModel.checkPermissions(context)
            if (isGranted) {
                Toast.makeText(context, "Доступ получен!", Toast.LENGTH_SHORT).show()
            }
        }
    )

    val requestStoragePermission = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                context.startActivity(intent)
            }
        } else {
            legacyPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    LaunchedEffect(infoMessage) {
        infoMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearInfoMessage()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val screen = currentScreen) {
            is Screen.ListScreen -> {
                NotesListScreen(
                    notes = notes,
                    isPermissionGranted = isPermissionGranted,
                    isUsingFallback = isUsingFallback,
                    onRequestPermission = requestStoragePermission,
                    onNavigateToEdit = { index, text ->
                        viewModel.navigateTo(Screen.EditScreen(index, text))
                    },
                    onRefresh = {
                        viewModel.checkPermissions(context)
                        Toast.makeText(context, "Обновлено", Toast.LENGTH_SHORT).show()
                    },
                    onDeleteNote = { index ->
                        viewModel.deleteNoteAt(context, index)
                    }
                )
            }
            is Screen.EditScreen -> {
                NotesEditorScreen(
                    index = screen.index,
                    initialContent = screen.initialContent,
                    onNavigateBack = {
                        viewModel.navigateTo(Screen.ListScreen)
                    },
                    onSaveNote = { text ->
                        viewModel.updateNote(context, screen.index, text)
                        viewModel.navigateTo(Screen.ListScreen)
                    },
                    onDeleteNote = {
                        if (screen.index in notes.indices) {
                            viewModel.deleteNoteAt(context, screen.index)
                        }
                        viewModel.navigateTo(Screen.ListScreen)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesListScreen(
    notes: List<String>,
    isPermissionGranted: Boolean,
    isUsingFallback: Boolean,
    onRequestPermission: () -> Unit,
    onNavigateToEdit: (Int, String) -> Unit,
    onRefresh: () -> Unit,
    onDeleteNote: (Int) -> Unit
) {
    var showPermissionInfoDialog by remember { mutableStateOf(false) }
    var noteToDeleteIndex by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Заметки",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = if (isUsingFallback) "Внутренняя память (нет доступа к загрузкам)" else "Файл: Download/Notes.txt",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isUsingFallback) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier.testTag("refresh_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Обновить"
                        )
                    }
                    IconButton(
                        onClick = { showPermissionInfoDialog = true },
                        modifier = Modifier.testTag("info_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Информация о памяти"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onNavigateToEdit(-1, "") },
                icon = { Icon(Icons.Default.Add, contentDescription = "Создать заметку") },
                text = { Text("Новая заметка") },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .testTag("add_note_fab")
                    .padding(8.dp)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isUsingFallback) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .testTag("permission_banner"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .padding(12.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Предупреждение",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Нет доступа к папке Download",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "Нажмите 'Доступ', чтобы сохранять в Download/Notes.txt",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                            )
                        }
                        Button(
                            onClick = onRequestPermission,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Доступ", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }

            if (notes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Пусто",
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Нет заметок",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Нажмите кнопку снизу, чтобы написать первую заметку.\nИспользуйте линию из 40 звёзд '****************************************' с отступами для разделения.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .testTag("notes_list"),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(notes) { index, noteText ->
                        NoteCard(
                            index = index,
                            text = noteText,
                            onClick = { onNavigateToEdit(index, noteText) },
                            onDelete = { noteToDeleteIndex = index }
                        )
                    }
                }
            }
        }
    }

    if (showPermissionInfoDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionInfoDialog = false },
            title = { Text("О хранении заметок", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        text = "Все ваши заметки сохраняются в одном общем текстовом файле по адресу:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "/storage/emulated/0/Download/Notes.txt",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "В качестве разграничителя используется разделитель на отдельной строке с отступами:\n\n****************************************\n\nВы можете свободно редактировать его в сторонних блокнотах, а приложение покажет каждый блок как независимую заметку.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showPermissionInfoDialog = false }) {
                    Text("Понятно")
                }
            }
        )
    }

    if (noteToDeleteIndex != null) {
        AlertDialog(
            onDismissRequest = { noteToDeleteIndex = null },
            title = { Text("Удалить заметку?") },
            text = { Text("Вы действительно хотите удалить эту заметку из общего списка файла Notes.txt?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        noteToDeleteIndex?.let { onDeleteNote(it) }
                        noteToDeleteIndex = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { noteToDeleteIndex = null }) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
fun NoteCard(
    index: Int,
    text: String,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val title = lines.firstOrNull() ?: "(Пустая заметка)"
    val preview = if (lines.size > 1) {
        lines.drop(1).joinToString("\n")
    } else {
        ""
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("note_card_$index"),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "#${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Row {
                    IconButton(
                        onClick = {
                            val sendIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, text)
                                type = "text/plain"
                            }
                            val shareIntent = Intent.createChooser(sendIntent, "Поделиться заметкой")
                            context.startActivity(shareIntent)
                        },
                        modifier = Modifier.size(32.dp).testTag("share_btn_$index")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Поделиться",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(32.dp).testTag("delete_btn_$index")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Удалить",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            if (preview.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = "${text.length} симв.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesEditorScreen(
    index: Int,
    initialContent: String,
    onNavigateBack: () -> Unit,
    onSaveNote: (String) -> Unit,
    onDeleteNote: () -> Unit
) {
    var textState by remember { mutableStateOf(initialContent) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (index >= 0) "Заметка #${index + 1}" else "Новая заметка",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (textState.trim().isNotEmpty() && textState != initialContent) {
                            onSaveNote(textState)
                        } else {
                            onNavigateBack()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Назад"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (textState.trim().isEmpty()) {
                                Toast.makeText(context, "Заметка пуста", Toast.LENGTH_SHORT).show()
                                return@IconButton
                            }
                            val sendIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, textState)
                                type = "text/plain"
                            }
                            val shareIntent = Intent.createChooser(sendIntent, "Поделиться заметкой")
                            context.startActivity(shareIntent)
                        },
                        modifier = Modifier.testTag("editor_share_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Поделиться"
                        )
                    }

                    if (index >= 0) {
                        IconButton(
                            onClick = { showDeleteConfirm = true },
                            modifier = Modifier.testTag("editor_delete_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Удалить"
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            if (textState.trim().isEmpty()) {
                                Toast.makeText(context, "Нельзя сохранить пустую заметку", Toast.LENGTH_SHORT).show()
                            } else {
                                onSaveNote(textState)
                            }
                        },
                        modifier = Modifier.testTag("editor_save_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Сохранить"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            TextField(
                value = textState,
                onValueChange = { textState = it },
                placeholder = {
                    Text(
                        "Введите текст заметки...\n\nКаждый блок, разделенный строкой '****************************************', станет отдельной заметкой в файле Notes.txt",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("note_text_field"),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 18.sp,
                    lineHeight = 26.sp,
                    fontFamily = FontFamily.SansSerif
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                )
            )
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Символов: ${textState.length}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Строк: ${textState.lines().size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Удалить заметку?") },
            text = { Text("Эта заметка будет удалена из файла Notes.txt") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteNote()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}
