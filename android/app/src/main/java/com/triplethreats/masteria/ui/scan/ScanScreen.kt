package com.triplethreats.masteria.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TextSnippet
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.triplethreats.masteria.AppContainer
import com.triplethreats.masteria.data.ApiException
import com.triplethreats.masteria.data.ScanRequest
import com.triplethreats.masteria.ui.appViewModel
import com.triplethreats.masteria.ui.components.ActivitySpinner
import com.triplethreats.masteria.ui.components.ButtonStyle
import com.triplethreats.masteria.ui.components.GlassSurface
import com.triplethreats.masteria.ui.components.MButton
import com.triplethreats.masteria.ui.components.TabBarHeight
import com.triplethreats.masteria.ui.components.pressable
import com.triplethreats.masteria.ui.theme.Masteria
import com.triplethreats.masteria.ui.theme.rememberHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/** A physics page for demos: works on an emulator or when there's no book at hand. */
private const val SAMPLE_PAGE = """Newton's First Law of Motion
An object at rest stays at rest, and an object in motion stays in motion with the same speed and in the same direction, unless it is acted upon by an unbalanced force. This tendency of objects to resist changes in their state of motion is called inertia. The mass of an object is a measure of its inertia: the greater the mass, the greater the inertia.
Newton's Second Law of Motion
The rate of change of momentum of an object is proportional to the applied unbalanced force and takes place in the direction of the force. This gives F = m × a, where F is force in newtons, m is mass in kilograms and a is acceleration in m/s². For example, a force of 10 N acting on a 2 kg ball produces an acceleration of 5 m/s².
Newton's Third Law of Motion
For every action there is an equal and opposite reaction. When a swimmer pushes water backwards, the water pushes the swimmer forwards."""

sealed interface ScanStage {
    data object Camera : ScanStage
    data class Review(val text: String, val image: Bitmap?) : ScanStage
    data class Building(val image: Bitmap?) : ScanStage
    data class Failed(val message: String, val text: String, val image: Bitmap?) : ScanStage
}

class ScanViewModel(private val container: AppContainer) : ViewModel() {
    var stage by mutableStateOf<ScanStage>(ScanStage.Camera)
        private set
    var reading by mutableStateOf(false)
        private set

    fun reset() {
        stage = ScanStage.Camera
    }

    fun useSample() {
        stage = ScanStage.Review(SAMPLE_PAGE, null)
    }

    /** On-device OCR first (fast, private); the page image only goes to NIM if OCR finds little. */
    fun recognise(image: InputImage, bitmap: Bitmap?) {
        reading = true
        viewModelScope.launch {
            val text = runCatching { ocr(image) }.getOrDefault("")
            reading = false
            stage = ScanStage.Review(text.trim(), bitmap)
        }
    }

    fun build(onReady: () -> Unit) {
        val review = stage as? ScanStage.Review ?: (stage as? ScanStage.Failed)?.let { ScanStage.Review(it.text, it.image) } ?: return
        stage = ScanStage.Building(review.image)
        viewModelScope.launch {
            try {
                val useImage = review.text.length < 40 && review.image != null
                val quest = container.api.scan(
                    ScanRequest(
                        text = review.text.takeIf { it.isNotBlank() },
                        imageBase64 = if (useImage) review.image?.let(::toJpegBase64) else null,
                    )
                )
                container.pendingQuest = quest
                stage = ScanStage.Camera
                onReady()
            } catch (e: ApiException) {
                stage = ScanStage.Failed(e.message.orEmpty(), review.text, review.image)
            }
        }
    }

    private suspend fun ocr(image: InputImage): String = suspendCancellableCoroutine { cont ->
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { cont.resume(it.text) }
            .addOnFailureListener { cont.resume("") }
            .addOnCompleteListener { recognizer.close() }
    }
}

private fun toJpegBase64(bitmap: Bitmap): String {
    val scale = (1024f / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1f)
    val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}

@Composable
fun ScanScreen(active: Boolean, onQuestReady: () -> Unit) {
    val vm = appViewModel { ScanViewModel(it) }
    val context = LocalContext.current
    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                vm.recognise(InputImage.fromFilePath(context, uri), bitmap)
            }
        }
    }
    val pickImage = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AnimatedContent(
            targetState = vm.stage,
            contentKey = { it::class },
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
            label = "scanStage",
        ) { stage ->
            when (stage) {
                ScanStage.Camera -> CameraStage(
                    active = active,
                    hasCamera = hasCamera,
                    reading = vm.reading,
                    onRequestPermission = { permission.launch(Manifest.permission.CAMERA) },
                    onCaptured = { image, bitmap -> vm.recognise(image, bitmap) },
                    onPick = pickImage,
                    onSample = { vm.useSample() },
                )
                is ScanStage.Review -> ReviewStage(stage, onRetake = { vm.reset() }, onBuild = { vm.build(onQuestReady) })
                is ScanStage.Building -> BuildingStage(stage.image)
                is ScanStage.Failed -> ReviewStage(
                    ScanStage.Review(stage.text, stage.image),
                    error = stage.message,
                    onRetake = { vm.reset() },
                    onBuild = { vm.build(onQuestReady) },
                )
            }
        }
    }
}

@Composable
private fun CameraStage(
    active: Boolean,
    hasCamera: Boolean,
    reading: Boolean,
    onRequestPermission: () -> Unit,
    onCaptured: (InputImage, Bitmap?) -> Unit,
    onPick: () -> Unit,
    onSample: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val haptics = rememberHaptics()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + TabBarHeight
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    var flash by remember { mutableIntStateOf(0) }
    val flashAlpha = remember { Animatable(0f) }
    LaunchedEffect(flash) {
        if (flash > 0) {
            flashAlpha.snapTo(0.85f); flashAlpha.animateTo(0f, tween(260))
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (hasCamera && active) {
            val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
            AndroidView({ previewView }, Modifier.fillMaxSize())
            DisposableEffect(lifecycle) {
                val future = ProcessCameraProvider.getInstance(context)
                var provider: ProcessCameraProvider? = null
                future.addListener({
                    provider = future.get()
                    val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                    runCatching {
                        provider?.unbindAll()
                        provider?.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                    }
                }, ContextCompat.getMainExecutor(context))
                onDispose { provider?.unbindAll() }
            }
            PageGuides()
        } else if (!hasCamera) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Rounded.CameraAlt, null, tint = Color.White, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(14.dp))
                Text("Scan a page into a quest", style = Masteria.type.title2, color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Masteria reads the text on your phone first, then NVIDIA NIM writes and checks 5 questions about it.",
                    style = Masteria.type.subhead, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                MButton("Allow camera", onRequestPermission, icon = Icons.Rounded.CameraAlt)
            }
        }

        // Instructions, on glass over the viewfinder
        GlassSurface(
            Modifier
                .statusBarsPadding()
                .padding(16.dp)
                .align(Alignment.TopCenter),
            shape = RoundedCornerShape(18.dp),
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = Masteria.colors.brand, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Fit one textbook page or your notes in the frame", style = Masteria.type.subhead, color = Masteria.colors.label)
            }
        }

        // Controls
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = bottom + 20.dp, start = 28.dp, end = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            RoundControl(Icons.Rounded.PhotoLibrary, "Choose a photo", "Photos", onPick)
            Box(
                Modifier
                    .size(78.dp)
                    .pressable(enabled = hasCamera && active && !reading, scale = 0.9f, haptic = false) {
                        haptics.tap(); flash++
                        capture.takePicture(
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    val rotation = image.imageInfo.rotationDegrees
                                    val bitmap = runCatching { image.toBitmap().rotate(rotation) }.getOrNull()
                                    image.close()
                                    if (bitmap != null) onCaptured(InputImage.fromBitmap(bitmap, 0), bitmap)
                                }

                                override fun onError(exception: ImageCaptureException) = Unit
                            },
                        )
                    }
                    .semantics { contentDescription = "Take photo" }
                    .border(4.dp, Color.White, CircleShape)
                    .padding(7.dp)
                    .clip(CircleShape)
                    .background(if (hasCamera) Color.White else Color.White.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center,
            ) {
                if (reading) ActivitySpinner(color = Color.Black, size = 26.dp)
            }
            RoundControl(Icons.Rounded.Description, "Use a sample page", "Sample", onSample)
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = flashAlpha.value }
                .background(Color.White)
        )
    }
}

private fun Bitmap.rotate(degrees: Int): Bitmap =
    if (degrees == 0) this else Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { postRotate(degrees.toFloat()) }, true)

@Composable
private fun RoundControl(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, short: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(52.dp)
                .pressable(scale = 0.9f, onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            GlassSurface(Modifier.size(52.dp), shape = CircleShape) {
                Icon(icon, null, tint = Masteria.colors.label, modifier = Modifier
                    .size(24.dp)
                    .align(Alignment.Center))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(short, style = Masteria.type.caption, color = Color.White)
    }
}

/** Corner brackets framing the page, like the system document scanner. */
@Composable
private fun PageGuides() {
    Canvas(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 36.dp, vertical = 150.dp)
    ) {
        val l = 36.dp.toPx()
        val w = 4.dp.toPx()
        val col = Color.White.copy(alpha = 0.9f)
        val (x0, y0, x1, y1) = listOf(0f, 0f, size.width, size.height)
        listOf(
            Offset(x0, y0) to listOf(Offset(x0 + l, y0), Offset(x0, y0 + l)),
            Offset(x1, y0) to listOf(Offset(x1 - l, y0), Offset(x1, y0 + l)),
            Offset(x0, y1) to listOf(Offset(x0 + l, y1), Offset(x0, y1 - l)),
            Offset(x1, y1) to listOf(Offset(x1 - l, y1), Offset(x1, y1 - l)),
        ).forEach { (corner, ends) -> ends.forEach { drawLine(col, corner, it, w, StrokeCap.Round) } }
    }
}

@Composable
private fun ReviewStage(stage: ScanStage.Review, onRetake: () -> Unit, onBuild: () -> Unit, error: String? = null) {
    val c = Masteria.colors
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + TabBarHeight
    val enough = stage.text.length >= 40 || stage.image != null
    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding()
            .padding(bottom = bottom)
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Text("Page captured", style = Masteria.type.largeTitle, color = c.label)
            Spacer(Modifier.height(6.dp))
            Text(
                if (stage.text.isNotBlank()) "Read on your phone. NVIDIA NIM will write 5 questions from it and verify every answer."
                else "Couldn't read much text on the phone, so the photo itself will be sent to NIM's vision model.",
                style = Masteria.type.subhead, color = c.secondaryLabel,
            )
            Spacer(Modifier.height(16.dp))
            stage.image?.let {
                Image(
                    it.asImageBitmap(), contentDescription = "Captured page",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(18.dp)),
                )
                Spacer(Modifier.height(14.dp))
            }
            if (stage.text.isNotBlank()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(c.card)
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.TextSnippet, null, tint = c.brand, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Text found · ${stage.text.split(Regex("\\s+")).size} words", style = Masteria.type.headline, color = c.label)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(stage.text.take(700) + if (stage.text.length > 700) "…" else "", style = Masteria.type.footnote, color = c.secondaryLabel)
                }
            }
            if (error != null) {
                Spacer(Modifier.height(14.dp))
                Text(error, style = Masteria.type.subhead, color = c.danger)
            }
        }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MButton("Retake", onRetake, Modifier.weight(1f), style = ButtonStyle.Tinted, icon = Icons.Rounded.Refresh)
            MButton(
                if (error != null) "Try again" else "Build quest", onBuild, Modifier.weight(1.4f),
                enabled = enough, icon = Icons.Rounded.AutoAwesome,
            )
        }
    }
}

@Composable
private fun BuildingStage(image: Bitmap?) {
    val c = Masteria.colors
    val steps = listOf("Reading the page", "Writing questions with NVIDIA NIM", "Checking every answer independently", "Assembling your quest")
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        // Honest pacing: the server does these in order; we advance as time passes, never past the last.
        val waits = listOf(900L, 7000L, 9000L)
        waits.forEach { delay(it); if (step < steps.lastIndex) step++ }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        image?.let {
            Image(
                it.asImageBitmap(), null, contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp)
                    .clip(RoundedCornerShape(18.dp)),
            )
            Spacer(Modifier.height(24.dp))
        }
        Text("Building your quest", style = Masteria.type.title1, color = c.label)
        Spacer(Modifier.height(6.dp))
        Text("This takes up to a minute on the hosted models.", style = Masteria.type.subhead, color = c.secondaryLabel)
        Spacer(Modifier.height(24.dp))
        steps.forEachIndexed { i, s ->
            val state = when {
                i < step -> 2; i == step -> 1; else -> 0
            }
            Row(Modifier.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    when (state) {
                        2 -> Icon(Icons.Rounded.CheckCircle, null, tint = c.success, modifier = Modifier.size(22.dp))
                        1 -> ActivitySpinner(size = 20.dp)
                        else -> Box(
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(c.fillStrong)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    s, style = if (state == 1) Masteria.type.headline else Masteria.type.body,
                    color = if (state == 0) c.tertiaryLabel else c.label,
                )
            }
        }
    }
}
