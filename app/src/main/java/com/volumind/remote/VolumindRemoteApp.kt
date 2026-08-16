package com.volumind.remote

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

private val Ink = Color(0xFF24333B)
private val Accent = Color(0xFF167D9A)
private val Canvas = Color(0xFFF4F6F7)

@Composable fun VolumindRemoteApp() {
    val client = remember { RelayClient() }
    val state by client.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    MaterialTheme(colorScheme = lightColorScheme(primary = Accent, background = Canvas, surface = Color.White, onSurface = Ink)) {
        Scaffold(
            topBar = { Header(state.connection, state.paired) },
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.ChatBubbleOutline, null) }, { Text("צ׳אט") })
                    NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.ViewInAr, null) }, { Text("מודל חי") })
                }
            }, containerColor = Canvas
        ) { padding ->
            if (!state.paired) PairScreen(Modifier.padding(padding), state.connection, client::connect)
            else if (tab == 0) ChatScreen(Modifier.padding(padding), state, client::sendChat, client::stopBuild, client::submitAnswers)
            else LiveModelScreen(Modifier.padding(padding), state)
        }
    }
}

@Composable private fun Header(status: String, connected: Boolean) {
    Surface(shadowElevation = 2.dp) { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = Color(0xFFF0F3F5), shape = RoundedCornerShape(12.dp), modifier = Modifier.size(42.dp)) {
            Image(painterResource(R.drawable.volumind_logo), "לוגו Volumind", modifier = Modifier.padding(3.dp))
        }
        Spacer(Modifier.width(11.dp)); Column { Text("Volumind Remote", fontWeight = FontWeight.Bold); Text(status, style = MaterialTheme.typography.labelSmall, color = if (connected) Color(0xFF25845A) else Color.Gray) }
    } }
}

@Composable private fun PairScreen(modifier: Modifier, status: String, connect: (String, String) -> Unit) {
    var url by remember { mutableStateOf(BuildConfig.RELAY_URL) }; var code by remember { mutableStateOf("") }
    Column(modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.ViewInAr, null, tint = Accent, modifier = Modifier.size(58.dp)); Spacer(Modifier.height(18.dp))
        Text("חיבור למחשב", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("הזן את כתובת השרת ואת קוד הצימוד שמוצג במחשב.", textAlign = TextAlign.Center, color = Color.Gray, modifier = Modifier.padding(8.dp))
        OutlinedTextField(url, { url = it }, label = { Text("כתובת שרת WSS") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp)); OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("קוד צימוד בן 6 ספרות") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp)); Button({ connect(url.trim(), code) }, enabled = code.length == 6, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("התחבר") }
        Text(status, style = MaterialTheme.typography.labelMedium, color = Color.Gray, modifier = Modifier.padding(12.dp))
    }
}

@Composable private fun ChatScreen(modifier: Modifier, state: RemoteState, send: (String) -> Unit, stop: () -> Unit, submitAnswers: (Map<String,String>) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(modifier.fillMaxSize()) {
        if (state.steps.isNotEmpty()) BuildProgress(state.steps, stop)
        if (state.questions.isNotEmpty()) QuestionCard(state.questions, submitAnswers)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.messages.isEmpty()) item { Text("שלח פקודה ל־Volumind. תוכל לראות כאן שאלות, תשובות וכל שלב בבנייה.", color = Color.Gray, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 60.dp)) }
            items(state.messages, key = { it.id }) { message -> MessageBubble(message) }
        }
        Surface(shadowElevation = 5.dp) { Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(text, { text = it }, placeholder = { Text("מה תרצה לבנות?") }, modifier = Modifier.weight(1f), maxLines = 5, shape = RoundedCornerShape(18.dp))
            Spacer(Modifier.width(8.dp)); FilledIconButton({ send(text); text = "" }, enabled = text.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, "שלח") }
        } }
    }
}

@Composable private fun QuestionCard(questions: List<RemoteQuestion>, submit: (Map<String,String>) -> Unit) {
    var answers by remember(questions) { mutableStateOf<Map<String,String>>(emptyMap()) }
    Surface(color = Color.White, shadowElevation = 2.dp) { Column(Modifier.fillMaxWidth().padding(14.dp)) {
        Text("כמה שאלות קצרות", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        questions.forEach { question ->
            Text(question.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 5.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                question.options.forEach { option -> FilterChip(selected = answers[question.id] == option, onClick = { answers = answers + (question.id to option) }, label = { Text(option) }) }
            }
        }
        Button({ submit(answers) }, enabled = answers.size == questions.size, modifier = Modifier.align(Alignment.End).padding(top = 10.dp)) { Text("המשך") }
    } }
}

@Composable private fun BuildProgress(steps: List<BuildStep>, stop: () -> Unit) {
    val done = steps.count { it.state == "done" }; Surface(color = Color(0xFFE7F2F5)) { Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("בונה את המודל · $done/${steps.size}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); IconButton(stop) { Icon(Icons.Default.StopCircle, "עצור", tint = Color(0xFFC44F4F)) } }
        LinearProgressIndicator(progress = { if (steps.isEmpty()) 0f else done.toFloat()/steps.size }, modifier = Modifier.fillMaxWidth())
        Text(steps.firstOrNull { it.state == "running" }?.title ?: "ממתין לשלב הבא", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 7.dp))
    } }
}

@Composable private fun MessageBubble(message: ChatMessage) { Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.sender == "user") Arrangement.End else Arrangement.Start) {
    Surface(color = if (message.sender == "user") Accent else Color.White, shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp, modifier = Modifier.widthIn(max = 320.dp)) { Text(message.text, color = if (message.sender == "user") Color.White else Ink, modifier = Modifier.padding(12.dp)) }
} }

@Composable private fun LiveModelScreen(modifier: Modifier, state: RemoteState) { Column(modifier.fillMaxSize().padding(14.dp)) {
    Text("המודל מתקדם", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("צילום חדש יופיע אוטומטית אחרי כל שלב מאומת.", color = Color.Gray)
    Spacer(Modifier.height(14.dp)); Surface(shape = RoundedCornerShape(18.dp), color = Color.White, modifier = Modifier.fillMaxWidth().weight(1f)) {
        if (state.screenshotUrl == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(state.screenshotCaption, color = Color.Gray) }
        else AsyncImage(state.screenshotUrl, state.screenshotCaption, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
    Text(state.screenshotCaption, modifier = Modifier.fillMaxWidth().padding(10.dp), textAlign = TextAlign.Center)
} }
