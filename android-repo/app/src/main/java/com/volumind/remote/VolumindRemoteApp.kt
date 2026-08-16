package com.volumind.remote

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import java.io.ByteArrayOutputStream

private val Ink = Color(0xFF24333B)
private val Accent = Color(0xFF167D9A)
private val Canvas = Color(0xFFF4F6F7)

@Composable fun VolumindRemoteApp() {
    val context = LocalContext.current
    val client = remember { RelayClient(context.applicationContext) }
    val state by client.state.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme = lightColorScheme(primary = Accent, background = Canvas, surface = Color.White, onSurface = Ink)) {
        Scaffold(
            topBar = { Header(state.connection, state.fusionOnline) }, containerColor = Canvas
        ) { padding ->
            if (!state.paired) PairScreen(Modifier.padding(padding), state.connection, client::connect)
            else ChatScreen(Modifier.padding(padding), state, client::sendChat, client::startBuild, client::stopBuild, client::submitAnswers)
        }
    }
}

@Composable private fun Header(status: String, connected: Boolean) {
    Surface(shadowElevation = 2.dp) { Column {
      Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = Color(0xFFF0F3F5), shape = RoundedCornerShape(12.dp), modifier = Modifier.size(42.dp)) {
            Image(painterResource(R.drawable.volumind_mark_2026), "לוגו Volumind החדש", modifier = Modifier.padding(3.dp))
        }
        Spacer(Modifier.width(11.dp)); Column { Text("Volumind", fontWeight = FontWeight.Bold); Text("CAD assistant", style = MaterialTheme.typography.labelSmall, color = Color.Gray) }
      }
      Surface(color=Color(0xFFF9FAFB)){Row(Modifier.fillMaxWidth().padding(vertical=7.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(7.dp).background(if(connected)Color(0xFF2A9B67) else Color(0xFFD18A30),RoundedCornerShape(99.dp)));Spacer(Modifier.width(7.dp));Text(status,style=MaterialTheme.typography.labelSmall,color=Color(0xFF68757E))}}
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

private fun readMobileAttachment(context:Context,uri:Uri):MobileAttachment{
    val resolver=context.contentResolver
    var name="attachment"
    resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{cursor->if(cursor.moveToFirst())name=cursor.getString(0)?:name}
    val type=resolver.getType(uri)?:when(name.substringAfterLast('.',"").lowercase()){"py","js","kt","java","json","txt","md","csv","html","css"->"text/plain";else->"application/octet-stream"}
    val output=ByteArrayOutputStream();val limit=4*1024*1024
    resolver.openInputStream(uri)?.use{input->val buffer=ByteArray(16*1024);while(true){val count=input.read(buffer);if(count<0)break;if(output.size()+count>limit)throw IllegalArgumentException("$name גדול מ־4MB");output.write(buffer,0,count)}}?:throw IllegalArgumentException("לא ניתן לפתוח את $name")
    val bytes=output.toByteArray()
    if(type.startsWith("image/"))return MobileAttachment(name,type,data="data:$type;base64,${Base64.encodeToString(bytes,Base64.NO_WRAP)}")
    val extension=name.substringAfterLast('.', "").lowercase()
    val textExtensions=setOf("py","js","ts","kt","java","json","txt","md","csv","html","css","xml","yaml","yml")
    if(!type.startsWith("text/")&&extension !in textExtensions)throw IllegalArgumentException("כרגע אפשר לצרף תמונות או קובצי טקסט/קוד")
    return MobileAttachment(name,type.ifBlank{"text/plain"},text=bytes.toString(Charsets.UTF_8).take(120000))
}

@Composable private fun ChatScreen(modifier: Modifier, state: RemoteState, send: (String,List<MobileAttachment>) -> Unit, start: () -> Unit, stop: () -> Unit, submitAnswers: (Map<String,String>) -> Unit) {
    var text by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<MobileAttachment>>(emptyList()) }
    var plusOpen by remember { mutableStateOf(false) }
    var codeDialog by remember { mutableStateOf(false) }
    var pastedCode by remember { mutableStateOf("") }
    var attachmentError by remember { mutableStateOf<String?>(null) }
    val context=LocalContext.current
    val filePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->
        val added=mutableListOf<MobileAttachment>();attachmentError=null
        var payloadSize=attachments.sumOf{it.text.length+it.data.length}
        uris.take(6-attachments.size).forEach{uri->runCatching{readMobileAttachment(context,uri)}.onSuccess{file->val size=file.text.length+file.data.length;if(payloadSize+size>6*1024*1024)attachmentError="סך הקבצים גדול מדי לשליחה (מקסימום 6MB)" else{added.add(file);payloadSize+=size}}.onFailure{attachmentError=it.message}}
        attachments=attachments+added
    }
    Column(modifier.fillMaxSize()) {
        if (state.steps.isNotEmpty()) BuildProgress(state, start, stop)
        if (state.questions.isNotEmpty()) QuestionCard(state.questions, submitAnswers)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.messages.isEmpty()) item { Text("שלח פקודה ל־Volumind. תוכל לראות כאן שאלות, תשובות וכל שלב בבנייה.", color = Color.Gray, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 60.dp)) }
            items(state.messages, key = { it.id }) { message -> MessageBubble(message) }
        }
        Surface(shadowElevation = 5.dp) { Column(Modifier.fillMaxWidth().padding(10.dp)) {
            if(attachments.isNotEmpty())Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){attachments.forEachIndexed{i,file->InputChip(selected=true,onClick={attachments=attachments.filterIndexed{index,_->index!=i}},label={Text(file.name,maxLines=1)},trailingIcon={Icon(Icons.Default.Close,"הסר",Modifier.size(16.dp))})}}
            attachmentError?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(bottom=5.dp))}
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Box{FilledTonalIconButton({plusOpen=true}){Icon(Icons.Default.Add,"הוסף קובץ או קוד")};DropdownMenu(plusOpen,{plusOpen=false}){DropdownMenuItem({Text("העלה קובץ או תמונה")},{plusOpen=false;filePicker.launch(arrayOf("*/*"))});DropdownMenuItem({Text("הדבק קוד")},{plusOpen=false;codeDialog=true})}}
                Spacer(Modifier.width(8.dp));OutlinedTextField(text, { text = it }, placeholder = { Text("מה תרצה לבנות?") }, modifier = Modifier.weight(1f), maxLines = 5, shape = RoundedCornerShape(18.dp))
                Spacer(Modifier.width(8.dp)); FilledIconButton({ send(text,attachments); text = "";attachments=emptyList() }, enabled = state.fusionOnline&&(text.isNotBlank()||attachments.isNotEmpty())) { Icon(Icons.AutoMirrored.Filled.Send, "שלח") }
            }
        } }
    }
    if(codeDialog)AlertDialog(onDismissRequest={codeDialog=false},title={Text("הדבק קוד")},text={OutlinedTextField(pastedCode,{pastedCode=it},placeholder={Text("הדבק כאן קוד שיצר AI")},modifier=Modifier.fillMaxWidth().heightIn(min=180.dp),minLines=8)},confirmButton={Button({attachments=attachments+MobileAttachment("pasted_fusion_code.py","text/x-python",text=pastedCode.take(120000));pastedCode="";codeDialog=false},enabled=pastedCode.isNotBlank()){Text("צרף קוד")}},dismissButton={TextButton({codeDialog=false}){Text("ביטול")}})
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

@Composable private fun BuildProgress(state: RemoteState, start: () -> Unit, stop: () -> Unit) {
    val steps=state.steps;val done=steps.count{it.state=="done"}
    Surface(color=Color.White,shadowElevation=2.dp,modifier=Modifier.fillMaxWidth().padding(12.dp),shape=RoundedCornerShape(14.dp)){Column(Modifier.padding(14.dp)){
        Text(if(state.awaitingApproval)"תוכנית הבנייה" else "המודל מתקדם · $done/${steps.size}",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium)
        steps.forEachIndexed{i,step->Text("${i+1}. ${step.title}",color=if(step.state=="done")Accent else Color(0xFF66757E),modifier=Modifier.padding(top=6.dp))}
        if(state.awaitingApproval){Button(start,enabled=state.fusionOnline,modifier=Modifier.align(Alignment.End).padding(top=12.dp)){Text("התחל לבנות ב־Fusion")}}
        else {LinearProgressIndicator(progress={if(steps.isEmpty())0f else done.toFloat()/steps.size},modifier=Modifier.fillMaxWidth().padding(top=12.dp));Row(Modifier.fillMaxWidth().padding(top=8.dp),verticalAlignment=Alignment.CenterVertically){Text(steps.firstOrNull{it.state=="running"}?.title?:"ממתין לשלב הבא",modifier=Modifier.weight(1f),style=MaterialTheme.typography.labelMedium);IconButton(stop){Icon(Icons.Default.StopCircle,"עצור",tint=Color(0xFFC44F4F))}}
          if(state.screenshotUrl!=null)AsyncImage(state.screenshotUrl,state.screenshotCaption,modifier=Modifier.fillMaxWidth().height(170.dp).padding(top=8.dp),contentScale=ContentScale.Fit)
        }
    }}
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
