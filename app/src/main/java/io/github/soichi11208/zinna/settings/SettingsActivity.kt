package io.github.soichi11208.zinna.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.soichi11208.zinna.keyboard.FlickGuideStyle
import io.github.soichi11208.zinna.keyboard.KeyboardStyle
import io.github.soichi11208.zinna.keyboard.LayoutRepository
import io.github.soichi11208.zinna.mozc.MozcEngine
import io.github.soichi11208.zinna.mozc.ProfileBackup
import io.github.soichi11208.zinna.mozc.UserDictionary
import io.github.soichi11208.zinna.theme.ZinnaTheme
import kotlin.system.exitProcess

/**
 * Setup and status screen.
 *
 * Deliberately thin for now: enabling the IME and confirming the native engine actually loaded are
 * the two things a user needs on first run, and the second one is the difference between "the
 * keyboard is broken" and "libmozc.so is missing for this ABI".
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ZinnaTheme {
                Scaffold { padding ->
                    SettingsScreen(Modifier.padding(padding))
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember { LayoutRepository(context) }
    var engineStatus by remember { mutableStateOf(describeEngine(context)) }
    DisposableEffect(context) {
        val prefs = context.getSharedPreferences(ImeSettings.NAME, Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "zenzai_plus_enabled" || key == "zenzai_mozc_enabled") engineStatus = describeEngine(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val layouts = remember { repository.availableLayoutIds() }
    val themes = remember { repository.availableThemeIds() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("KKKeyboard", style = MaterialTheme.typography.headlineMedium)

        // 上のタブ：「基本」＝今までの設定、「カスタマイズ」＝文字色・フォントなど
        var tab by remember { mutableStateOf(0) }
        androidx.compose.material3.TabRow(selectedTabIndex = tab) {
            for ((i, name) in listOf("基本", "カスタマイズ").withIndex()) {
                androidx.compose.material3.Tab(selected = tab == i, onClick = { tab = i }, text = { Text(name) })
            }
        }
        if (tab == 1) {
            CustomizeCard()
            TestInputField()
            return@Column
        }

        MyKeyboardCard()

        InfoCard(title = "変換エンジン", body = engineStatus)
        ZenzaiCard()
        InfoCard(title = "追加辞書", body = describeDictionaries(context))
        InfoCard(title = "配列", body = layouts.joinToString(", ").ifEmpty { "(なし)" })
        InfoCard(title = "テーマ", body = themes.joinToString(", ").ifEmpty { "(なし)" })

        UserDictionaryCard()

        BackupCard()

        KeyboardCard()

        AppearanceCard()

        TestInputField()

        Button(
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("キーボードを有効化") }

        Button(
            onClick = {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("キーボードを選択") }
    }
}

/** KKKeyboard で追加した設定：プライベートモード・Fold の左右分割・定型文。 */
@Composable
private fun MyKeyboardCard() {
    val context = LocalContext.current
    val settings = remember { ImeSettings(context) }
    var privateMode by remember { mutableStateOf(settings.privateMode) }
    var split by remember { mutableStateOf(settings.splitWhenWide) }
    var phrases by remember { mutableStateOf(settings.phrasesText) }
    var threshold by remember { mutableStateOf(settings.flickThresholdDp) }
    var toggleInput by remember { mutableStateOf(settings.toggleInput) }
    var holdToClear by remember { mutableStateOf(settings.holdToClear) }
    var charge by remember { mutableStateOf(settings.clearChargeMs.toFloat()) }
    var twoLines by remember { mutableStateOf(settings.candidateTwoLines) }
    var pressShape by remember { mutableStateOf(settings.pressShape) }
    var pressColor by remember { mutableStateOf(settings.pressColor) }
    var look by remember { mutableStateOf(settings.keyLook) }
    val glass = look == ImeSettings.LOOK_GLASS
    var refract by remember { mutableStateOf(settings.glassRefract) }
    var frost by remember { mutableStateOf(settings.glassFrost) }
    var glassLight by remember { mutableStateOf(settings.glassLight) }
    var glassShadow by remember { mutableStateOf(settings.glassShadow) }
    var popup by remember { mutableStateOf(settings.flickGuideStyle != FlickGuideStyle.NONE) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("KKKeyboard の設定", style = MaterialTheme.typography.titleMedium)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("プライベートモード", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "ONの間は入力を学習せず、記号や絵文字の履歴も残しません。キーボード上部の「🔓 プライベート」でも切り替えられます",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = privateMode, onCheckedChange = {
                    privateMode = it
                    settings.privateMode = it
                })
            }

            HorizontalDivider()

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Foldを開いたら左右分割", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "画面が広いときは、左右に「あかさ…」を分けて両手の親指で打てる配置にします",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = split, onCheckedChange = {
                    split = it
                    settings.splitWhenWide = it
                })
            }

            HorizontalDivider()

            Text("キーの見た目", style = MaterialTheme.typography.bodyLarge)
            for ((option, label) in listOf(
                ImeSettings.LOOK_GLASS to "リキッドグラス（厚いガラス。背景画像がゆがんで見える）",
                ImeSettings.LOOK_MEMENTO to "MEMENTO MORI A：海の青（青い札）",
                ImeSettings.LOOK_MEMENTO_MOON to "MEMENTO MORI B：満月（夜の黒と月の金）",
                ImeSettings.LOOK_MEMENTO_BOLD to "MEMENTO MORI C：大胆（白い札・大きく傾く）",
                ImeSettings.LOOK_MEMENTO_LINE to "MEMENTO MORI D：線だけ（札なし・下線と印）",
                ImeSettings.LOOK_MINIMAL to "ミニマリスト（キーの間に細い線だけ）",
                ImeSettings.LOOK_FLAT to "ふつう（平らなキー）",
            )) {
                val onSelect = { look = option; settings.keyLook = option }
                if (look == option) {
                    Button(onClick = onSelect, modifier = Modifier.fillMaxWidth()) { Text(label) }
                } else {
                    OutlinedButton(onClick = onSelect, modifier = Modifier.fillMaxWidth()) { Text(label) }
                }
            }

            if (look == ImeSettings.LOOK_FLAT || look == ImeSettings.LOOK_MINIMAL) {
                Text("押したときの形", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((value, label) in listOf(0 to "角丸", 1 to "四角", 2 to "丸")) {
                        val pick = { pressShape = value; settings.pressShape = value }
                        if (pressShape == value) Button(onClick = pick) { Text(label) }
                        else OutlinedButton(onClick = pick) { Text(label) }
                    }
                }
                Text("押したときの色", style = MaterialTheme.typography.bodyMedium)
                PressColorPicker(pressColor) { pressColor = it; settings.pressColor = it }
            }

            if (glass) {
                Text(
                    "ガラスの調整（ゆがみは、背景画像を設定したときに見えます）",
                    style = MaterialTheme.typography.bodySmall,
                )
                GlassSlider("ゆがみ", "なし", "強い", refract, 0f..2f, { refract = it }) { settings.glassRefract = refract }
                GlassSlider("くもり", "透明", "すりガラス", frost, 0f..1f, { frost = it }) { settings.glassFrost = frost }
                GlassSlider("縁の光", "弱い", "強い", glassLight, 0f..2f, { glassLight = it }) { settings.glassLight = glassLight }
                GlassSlider("影", "なし", "濃い", glassShadow, 0f..2f, { glassShadow = it }) { settings.glassShadow = glassShadow }
                TextButton(onClick = {
                    refract = 1f; frost = 0.2f; glassLight = 1f; glassShadow = 1f
                    settings.glassRefract = 1f; settings.glassFrost = 0.2f; settings.glassLight = 1f; settings.glassShadow = 1f
                }) { Text("ガラスの調整を最初に戻す") }
            }

            HorizontalDivider()

            SettingSwitch(
                title = "キーを押したときのポップアップ",
                body = "キーを押している間、入る文字を吹き出しで出します。☺記キー長押しのメニューでも切り替えられます",
                checked = popup,
            ) {
                popup = it
                settings.flickGuideStyle = if (it) FlickGuideStyle.PREVIEW else FlickGuideStyle.NONE
            }

            HorizontalDivider()

            Text("フリック感度", style = MaterialTheme.typography.bodyLarge)
            Text(
                "左ほど少し指をずらしただけでフリックになります（敏感）。右ほど大きく動かさないとフリックになりません（鈍感）",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("敏感", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = threshold,
                    onValueChange = { threshold = it },
                    onValueChangeFinished = { settings.flickThresholdDp = threshold },
                    valueRange = ImeSettings.MIN_FLICK_THRESHOLD_DP..ImeSettings.MAX_FLICK_THRESHOLD_DP,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text("鈍感", style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()

            SettingSwitch(
                title = "連打入力（ケータイ入力）",
                body = "ONにすると「あ」を続けて押すと あ→い→う… と変わります。同じ文字を続けて打つときは少し待つか ▶ を押します。フリックもそのまま使えます",
                checked = toggleInput,
            ) { toggleInput = it; settings.toggleInput = it }

            HorizontalDivider()

            SettingSwitch(
                title = "⌫を左にずらして全削除",
                body = "⌫を押したまま指を左にずらして止めると、赤いゲージがたまり、満タンでカーソルより左の文字を全部消します。普通の長押しは1文字ずつ連続で消します",
                checked = holdToClear,
            ) { holdToClear = it; settings.holdToClear = it }

            if (holdToClear) {
                Text(
                    "全削除のチャージ時間：%.1f 秒".format(charge / 1000f),
                    style = MaterialTheme.typography.bodySmall,
                )
                // 0.1秒きざみ（0.1〜3.0秒）。−／＋で0.1秒ずつ動かせる
                val setCharge = { v: Float ->
                    charge = (Math.round(v / 100f) * 100f).coerceIn(100f, 3000f)
                    settings.clearChargeMs = charge.toLong()
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { setCharge(charge - 100f) }) { Text("−") }
                    Slider(
                        value = charge,
                        onValueChange = { charge = it },
                        onValueChangeFinished = { setCharge(charge) },
                        valueRange = 100f..3000f,
                        steps = 28,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                    )
                    TextButton(onClick = { setCharge(charge + 100f) }) { Text("＋") }
                }
            }

            HorizontalDivider()

            SettingSwitch(
                title = "予測変換を2行で出す",
                body = "OFFは1行（横にスクロール）。ONにすると候補バーが2行ぶんの高さになり、キーボード全体が少し高くなります",
                checked = twoLines,
            ) { twoLines = it; settings.candidateTwoLines = it }

            HorizontalDivider()

            Text("定型文", style = MaterialTheme.typography.bodyLarge)
            Text(
                "1行に1つ書きます。キーボードの「☺記」→「定型文」から入力できます",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = phrases,
                onValueChange = {
                    phrases = it
                    settings.phrasesText = it
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
            )
        }
    }
}

/** タイトル・説明・スイッチの1行。 */
/** ガラスの調整用のスライダー1本（名前・左右の言葉つき）。 */
@Composable
private fun GlassSlider(
    title: String,
    low: String,
    high: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
) {
    Text(title, style = MaterialTheme.typography.bodyLarge)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(low, style = MaterialTheme.typography.bodySmall)
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            valueRange = range,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text(high, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    body: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ZenzaiCard() {
    val context = LocalContext.current
    val settings = remember { ImeSettings(context) }
    val bundled = remember { io.github.soichi11208.zinna.ime.ZenzaiEngine.isBundled(context) }
    var enabled by remember { mutableStateOf(settings.zenzaiPlusEnabled) }
    var mozcEnabled by remember { mutableStateOf(settings.zenzaiMozcEnabled) }
    val prefs = remember { context.getSharedPreferences(ImeSettings.NAME, Context.MODE_PRIVATE) }
    var updates by remember { mutableStateOf(prefs.getLong("zenzai_ttt_updates", 0)) }
    var words by remember { mutableStateOf(prefs.getInt("zenzai_dictionary_trained", 0)) }
    var requested by remember { mutableStateOf(false) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            updates = prefs.getLong("zenzai_ttt_updates", 0)
            words = prefs.getInt("zenzai_dictionary_trained", 0)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("AI変換（Zenzai）", modifier = Modifier.weight(1f))
                Switch(
                    checked = enabled,
                    enabled = bundled || enabled,
                    onCheckedChange = { enabled = it; settings.zenzaiPlusEnabled = it },
                )
            }
            Text(
                if (bundled) "スマホの中のAIが文脈を読んで変換し、その候補を先頭に出します。通信はしません。初回だけ読み込みに数秒かかります。"
                else "このビルドにはzenzai-plusモデルが含まれていません。",
                style = MaterialTheme.typography.bodySmall,
            )
            if (bundled) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("ふつうの変換候補も出す", modifier = Modifier.weight(1f))
                    Switch(
                        checked = mozcEnabled,
                        enabled = enabled,
                        onCheckedChange = { mozcEnabled = it; settings.zenzaiMozcEnabled = it },
                    )
                }
                Text("TTT学習: $updates 件 / 辞書学習: $words 語", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled = enabled, onClick = {
                    prefs.edit().putInt(UserDictionary.REVISION_KEY, prefs.getInt(UserDictionary.REVISION_KEY, 0) + 1).apply()
                    requested = true
                }) { Text(if (requested) "辞書学習を予約しました" else "ユーザー辞書を学習") }
            }
        }
    }
}

@Composable
private fun UserDictionaryCard() {
    val context = LocalContext.current
    // Recomputed on every composition rather than remembered: returning from the editor
    // recomposes this screen, and a cached count would show the pre-edit number.
    var count by remember { mutableStateOf(UserDictionary(context).entries().size) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { DictionaryFile.read(it.readBytes()) }
        }.getOrNull()
        importMessage = if (text == null) {
            "ファイルを読めませんでした"
        } else {
            val added = UserDictionary(context).importFrom(text)
            count = UserDictionary(context).entries().size
            if (added == 0) "新しく足せる単語はありませんでした（形式が違うか、全部登録済みです）"
            else "$added 語を読み込みました"
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ユーザー辞書", style = MaterialTheme.typography.titleMedium)
            Text(
                if (count == 0) "登録された単語はありません" else "$count 語を登録済み",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = {
                    context.startActivity(Intent(context, UserDictionaryActivity::class.java))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("単語を編集") }
            OutlinedButton(
                onClick = { pickFile.launch(arrayOf("text/*", "application/zip", "application/octet-stream")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("ファイルから読み込む") }
            Text(
                "ほかのキーボード（Gboard・Google日本語入力・ATOK など）で書き出した辞書ファイルを読み込めます",
                style = MaterialTheme.typography.bodySmall,
            )
            importMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/**
 * Export and import of everything the user cannot recreate. See [ProfileBackup].
 *
 * The passphrase is asked for before the file picker opens, so a mistyped one is caught while the
 * user still remembers what they meant to type rather than after a file has been written.
 */
@Composable
private fun BackupCard() {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Pending?>(null) }
    var passphrase by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var restorePending by remember { mutableStateOf(ProfileBackup.hasStagedRestore(context)) }

    val save = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BACKUP_MIME)
    ) { uri ->
        val phrase = passphrase.toCharArray()
        passphrase = ""
        if (uri == null) return@rememberLauncherForActivityResult
        val archive = ProfileBackup.export(context, phrase, BACKUP_EXTRAS)
        message = if (archive == null) {
            "書き出せませんでした。キーボードを一度使ってから試してください"
        } else {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(archive) }
                    ?: error("no stream")
                "書き出しました"
            }.getOrElse { "保存できませんでした: ${it.message}" }
        }
    }

    val load = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val phrase = passphrase.toCharArray()
        passphrase = ""
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        message = if (bytes == null) {
            "ファイルを読めませんでした"
        } else {
            when (val result = ProfileBackup.stageRestore(context, bytes, phrase)) {
                ProfileBackup.Result.Ok -> {
                    restorePending = true
                    "読み込みました。反映するにはアプリを終了して開き直してください"
                }
                ProfileBackup.Result.NotABackup -> "このファイルはバックアップではありません"
                ProfileBackup.Result.WrongPassphrase ->
                    "パスフレーズが違うか、ファイルが壊れています"
                is ProfileBackup.Result.TooNew ->
                    "新しいバージョンのアプリで作られたバックアップです (形式 ${result.format})"
                is ProfileBackup.Result.Failed -> result.message
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("バックアップ", style = MaterialTheme.typography.titleMedium)
            Text(
                "ユーザー辞書・変換の学習内容・キーボードの設定を1つのファイルにまとめます。" +
                    "打った内容そのものを含むので、パスフレーズで暗号化します。忘れると復元できません",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { pending = Pending.EXPORT },
                    modifier = Modifier.weight(1f),
                ) { Text("書き出す") }
                OutlinedButton(
                    onClick = { pending = Pending.IMPORT },
                    modifier = Modifier.weight(1f),
                ) { Text("読み込む") }
            }
            if (restorePending) {
                Text(
                    "復元待ちです。学習内容はキーボードが動いている間ずっと書き戻されるので、" +
                        "一度終了させないと上書きされてしまいます",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        // Ends this process, keyboard included. Android starts the input method
                        // again the next time a text field is focused, and that start is what
                        // unpacks the restore.
                        onClick = { exitProcess(0) },
                        modifier = Modifier.weight(1f),
                    ) { Text("終了して反映") }
                    OutlinedButton(
                        onClick = {
                            ProfileBackup.discardStagedRestore(context)
                            restorePending = false
                            message = "復元を取り消しました"
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("取り消す") }
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }

    pending?.let { what ->
        // Twice for an export, because a typo in a passphrase nobody has typed before is only
        // discovered when the backup is needed — by which time it is too late to matter.
        var confirmation by remember(what) { mutableStateOf("") }
        val exporting = what == Pending.EXPORT
        val ready = passphrase.length >= MIN_PASSPHRASE &&
            (!exporting || confirmation == passphrase)

        AlertDialog(
            onDismissRequest = { pending = null; passphrase = "" },
            title = { Text(if (exporting) "バックアップを書き出す" else "バックアップを読み込む") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("パスフレーズ") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (exporting) {
                        OutlinedTextField(
                            value = confirmation,
                            onValueChange = { confirmation = it },
                            label = { Text("もう一度") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "$MIN_PASSPHRASE 文字以上。このパスフレーズなしでは誰も — 私たちも — " +
                                "中身を読めません",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = ready,
                    onClick = {
                        pending = null
                        message = null
                        if (exporting) save.launch(ProfileBackup.suggestedFileName())
                        else load.launch(arrayOf("*/*"))
                    },
                ) { Text("続ける") }
            },
            dismissButton = {
                OutlinedButton(onClick = { pending = null; passphrase = "" }) { Text("やめる") }
            },
        )
    }
}

private enum class Pending { EXPORT, IMPORT }

private const val BACKUP_MIME = "application/octet-stream"

/**
 * Everything outside mozc's own profile that the user chose rather than typed: the settings, the
 * background image they picked, and any layout or theme they dropped in by hand.
 */
private val BACKUP_EXTRAS = ProfileBackup.Extras(
    preferences = listOf(ImeSettings.NAME),
    directories = listOf(
        ImeSettings.BACKGROUND_DIR,
        LayoutRepository.LAYOUTS_DIR,
        LayoutRepository.THEMES_DIR,
    ),
)

/** Short enough not to be a chore, long enough that PBKDF2 has something to work with. */
private const val MIN_PASSPHRASE = 8

/**
 * How the keyboard is laid out and how big it is — choices about the input surface rather than
 * about how it looks, which is why they are not in [AppearanceCard].
 */
@Composable
private fun KeyboardCard() {
    val context = LocalContext.current
    val settings = remember { ImeSettings(context) }

    var style by remember { mutableStateOf(settings.keyboardStyle) }
    var guide by remember { mutableStateOf(settings.flickGuideStyle) }
    var symbolAllDirections by remember { mutableStateOf(settings.showAllDirectionsOnSymbolPlane) }
    var heightScale by remember { mutableStateOf(settings.keyHeightScale) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("キーボード", style = MaterialTheme.typography.titleMedium)

            Text("入力方式", style = MaterialTheme.typography.bodyLarge)
            Text(
                "かなと英字それぞれの配列。キーボード上のキーで面を切り替えたときも、この選択に従います",
                style = MaterialTheme.typography.bodySmall,
            )
            for ((option, label) in listOf(
                KeyboardStyle.FLICK to "フリック",
                KeyboardStyle.QWERTY to "QWERTY",
                KeyboardStyle.MIXED to "かなフリック + 英字QWERTY",
            )) {
                val onSelect = {
                    style = option
                    settings.keyboardStyle = option
                }
                if (style == option) {
                    Button(onClick = onSelect, modifier = Modifier.fillMaxWidth()) { Text(label) }
                } else {
                    OutlinedButton(
                        onClick = onSelect,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(label) }
                }
            }

            HorizontalDivider()

            Text("フリック中の表示", style = MaterialTheme.typography.bodyLarge)
            Text(
                "キーを押している間に何を出すか。フリック配列のときだけ効きます",
                style = MaterialTheme.typography.bodySmall,
            )
            for ((option, label) in listOf(
                FlickGuideStyle.PREVIEW to "離したら入る文字だけ",
                FlickGuideStyle.DIRECTIONS to "4方向すべて",
                FlickGuideStyle.NONE to "表示しない",
            )) {
                val onSelect = {
                    guide = option
                    settings.flickGuideStyle = option
                }
                if (guide == option) {
                    Button(onClick = onSelect, modifier = Modifier.fillMaxWidth()) { Text(label) }
                } else {
                    OutlinedButton(
                        onClick = onSelect,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(label) }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("記号面は常に4方向", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "記号はどの方向に何があるか覚えにくいので、上の選択に関わらず記号面だけ" +
                            "4方向すべてを出します",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = symbolAllDirections,
                    onCheckedChange = {
                        symbolAllDirections = it
                        settings.showAllDirectionsOnSymbolPlane = it
                    },
                )
            }

            HorizontalDivider()

            Text(
                "キーボードの高さ  ${(heightScale * 100).toInt()}%",
                style = MaterialTheme.typography.bodyLarge,
            )
            Slider(
                value = heightScale,
                onValueChange = { heightScale = it },
                onValueChangeFinished = { settings.keyHeightScale = heightScale },
                valueRange = ImeSettings.MIN_KEY_HEIGHT_SCALE..ImeSettings.MAX_KEY_HEIGHT_SCALE,
            )
        }
    }
}

/**
 * Appearance controls that belong to this device rather than to a shareable theme file.
 *
 * The keyboard picks changes up the next time it opens — it lives in another process and only
 * re-reads settings when its input view starts.
 */
@Composable
private fun AppearanceCard() {
    val context = LocalContext.current
    val settings = remember { ImeSettings(context) }

    var pureBlack by remember { mutableStateOf(settings.pureBlack) }
    var opacity by remember { mutableStateOf(settings.backgroundOpacity) }
    var hasImage by remember { mutableStateOf(settings.backgroundImage != null) }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Read it out now rather than holding the URI: the IME runs in a process that never got
        // this grant, and the picked file can be deleted or its volume unmounted later.
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes != null) {
            settings.setBackgroundImage(bytes)
            hasImage = true
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("外観", style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("ピュアブラック", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "有機EL向け。背景を完全な黒にして画素を消灯させます",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = pureBlack,
                    onCheckedChange = {
                        pureBlack = it
                        settings.pureBlack = it
                    },
                )
            }

            HorizontalDivider()

            Text("キーボードの背景画像", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { pickImage.launch(arrayOf("image/*")) },
                    modifier = Modifier.weight(1f),
                ) { Text(if (hasImage) "変更" else "選択") }
                OutlinedButton(
                    onClick = {
                        settings.clearBackgroundImage()
                        hasImage = false
                    },
                    enabled = hasImage,
                    modifier = Modifier.weight(1f),
                ) { Text("解除") }
            }

            if (hasImage) {
                Text(
                    "画像の濃さ  ${(opacity * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = opacity,
                    onValueChange = { opacity = it },
                    onValueChangeFinished = { settings.backgroundOpacity = opacity },
                    valueRange = 0f..1f,
                )
            }
        }
    }
}

/**
 * Somewhere to actually try the keyboard without leaving the app.
 *
 * A multi-line field on purpose: it is the only way to see what the Enter key does when the editor
 * has no action attached, which is exactly the case that is easy to get wrong.
 */
@Composable
private fun TestInputField() {
    var text by remember { mutableStateOf("") }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("試し入力", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                placeholder = { Text("ここで入力を試せます") },
                singleLine = false,
            )
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * The bundled dictionaries import in the background on first run, so this doubles as the way to
 * tell "not imported yet" from "imported and empty" without digging through logcat.
 */
private fun describeDictionaries(context: Context): String =
    // Baked into mozc.data at build time rather than imported at runtime, so there is no state to
    // report — either the engine loaded or it did not.
    if (ImeSettings(context).zenzaiPlusEnabled) "zenzai-plus / ユーザー辞書"
    else if (MozcEngine.get(context) == null) "—"
    else "ニコニコ大百科×ピクシブ百科事典 / カタカナ語→英語 / Wikipedia 見出し"

private fun describeEngine(context: Context): String {
    if (ImeSettings(context).zenzaiPlusEnabled) return if (ImeSettings(context).zenzaiMozcEnabled)
        "zenzai-plus（実験的） / Mozc候補を併用" else "zenzai-plus（実験的） / Mozc無効"
    val engine = MozcEngine.get(context)
        ?: return "読み込み失敗 — この ABI 用の libmozc.so がありません (scripts/build_mozc.sh を実行)"
    val version = engine.dataVersion.ifEmpty { "unknown" }
    return "オフライン動作中 / 辞書バージョン $version"
}


/** 押したときの色の見本。左端の「標準」はテーマの色。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PressColorPicker(selected: Int, onPick: (Int) -> Unit) {
    val colors = listOf(
        0,
        0x66FFFFFF, 0x99FFFFFF.toInt(), 0x66000000, 0x9916CFFB.toInt(), 0x993B82F6.toInt(),
        0x9922C55E.toInt(), 0x99FACC15.toInt(), 0x99F97316.toInt(), 0x99EF4444.toInt(),
        0x99EC4899.toInt(), 0x99A855F7.toInt(), 0x999CA3AF.toInt(),
    )
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (c in colors) {
            val shape = androidx.compose.foundation.shape.CircleShape
            androidx.compose.foundation.layout.Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .then(
                        if (c == selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape)
                        else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape)
                    )
                    .padding(4.dp)
                    .background(
                        if (c == 0) MaterialTheme.colorScheme.surfaceVariant else androidx.compose.ui.graphics.Color(c),
                        shape,
                    )
                    .clickable { onPick(c) },
            ) {
                if (c == 0) Text("標準", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}


/** 「カスタマイズ」タブ：キーボードと予測変換の欄の、文字色・フォント・太さ・大きさ・背景色。 */
@Composable
private fun CustomizeCard() {
    val context = LocalContext.current
    val settings = remember { ImeSettings(context) }
    var keyText by remember { mutableStateOf(settings.customKeyText) }
    var keyFont by remember { mutableStateOf(settings.customKeyFont) }
    var keyWeight by remember { mutableStateOf(settings.customKeyWeight) }
    var keyScale by remember { mutableStateOf(settings.customKeyScale) }
    var kbBg by remember { mutableStateOf(settings.customKeyboardBg) }
    var candText by remember { mutableStateOf(settings.customCandText) }
    var candFont by remember { mutableStateOf(settings.customCandFont) }
    var candWeight by remember { mutableStateOf(settings.customCandWeight) }
    var candScale by remember { mutableStateOf(settings.customCandScale) }
    var candBg by remember { mutableStateOf(settings.customCandBg) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("キーボード", style = MaterialTheme.typography.titleMedium)
            Text("文字の色", style = MaterialTheme.typography.bodyMedium)
            SolidColorPicker(keyText) { keyText = it; settings.customKeyText = it }
            Text("フォント", style = MaterialTheme.typography.bodyMedium)
            ChoiceRow(io.github.soichi11208.zinna.theme.Fonts.CHOICES, keyFont) { keyFont = it; settings.customKeyFont = it }
            Text("太さ", style = MaterialTheme.typography.bodyMedium)
            ChoiceRow(io.github.soichi11208.zinna.theme.Fonts.WEIGHTS, keyWeight) { keyWeight = it; settings.customKeyWeight = it }
            GlassSlider("文字の大きさ", "小さい", "大きい", keyScale, 0.6f..1.6f, { keyScale = it }) { settings.customKeyScale = keyScale }
            Text("背景の色（背景画像を設定しているときは画像が優先）", style = MaterialTheme.typography.bodyMedium)
            SolidColorPicker(kbBg) { kbBg = it; settings.customKeyboardBg = it }

            HorizontalDivider()

            Text("予測変換の欄", style = MaterialTheme.typography.titleMedium)
            Text("文字の色", style = MaterialTheme.typography.bodyMedium)
            SolidColorPicker(candText) { candText = it; settings.customCandText = it }
            Text("フォント", style = MaterialTheme.typography.bodyMedium)
            ChoiceRow(io.github.soichi11208.zinna.theme.Fonts.CHOICES, candFont) { candFont = it; settings.customCandFont = it }
            Text("太さ", style = MaterialTheme.typography.bodyMedium)
            ChoiceRow(io.github.soichi11208.zinna.theme.Fonts.WEIGHTS, candWeight) { candWeight = it; settings.customCandWeight = it }
            GlassSlider("文字の大きさ", "小さい", "大きい", candScale, 0.6f..1.6f, { candScale = it }) { settings.customCandScale = candScale }
            Text("背景の色（標準＝塗らない）", style = MaterialTheme.typography.bodyMedium)
            SolidColorPicker(candBg) { candBg = it; settings.customCandBg = it }

            TextButton(onClick = {
                settings.resetCustom()
                keyText = 0; keyFont = ""; keyWeight = 0; keyScale = 1f; kbBg = 0
                candText = 0; candFont = ""; candWeight = 0; candScale = 1f; candBg = 0
            }) { Text("カスタマイズを全部もとに戻す") }
        }
    }
}

/** 選択肢をボタンで並べる（選んでいるものは塗りつぶし）。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(choices: List<Pair<String, T>>, selected: T, onPick: (T) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((label, value) in choices) {
            if (value == selected) Button(onClick = { onPick(value) }) { Text(label) }
            else OutlinedButton(onClick = { onPick(value) }) { Text(label) }
        }
    }
}

/** 塗りつぶしの色の見本。左端の「標準」はテーマの色のまま。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SolidColorPicker(selected: Int, onPick: (Int) -> Unit) {
    val colors = listOf(
        0,
        0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFD1D5DB.toInt(), 0xFF4B5563.toInt(), 0xFF1E1E24.toInt(),
        0xFF0B1F4D.toInt(), 0xFF3B82F6.toInt(), 0xFF16CFFB.toInt(), 0xFF22C55E.toInt(), 0xFFFACC15.toInt(),
        0xFFF97316.toInt(), 0xFFEF4444.toInt(), 0xFFEC4899.toInt(), 0xFFA855F7.toInt(), 0xFF8B5E3C.toInt(),
    )
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (c in colors) {
            val shape = androidx.compose.foundation.shape.CircleShape
            androidx.compose.foundation.layout.Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .then(
                        if (c == selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape)
                        else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape)
                    )
                    .padding(4.dp)
                    .background(
                        if (c == 0) MaterialTheme.colorScheme.surfaceVariant else androidx.compose.ui.graphics.Color(c),
                        shape,
                    )
                    .clickable { onPick(c) },
            ) {
                if (c == 0) Text("標準", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
