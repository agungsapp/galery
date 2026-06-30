package com.example

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.BackgroundClean
import com.example.ui.theme.BorderColor
import com.example.ui.theme.LightRed
import com.example.ui.theme.PrimaryClean
import com.example.ui.theme.RedDelete
import com.example.ui.theme.SecondaryClean
import com.example.ui.theme.SurfaceDarkPurple
import com.example.ui.theme.TextDark
import com.example.ui.theme.TextDarkPurple
import com.example.ui.theme.TextMedium
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// Screen enumeration
sealed class AppScreen {
    object Splash : AppScreen()
    object Home : AppScreen()
    object Swiping : AppScreen()
    object Finished : AppScreen()
}

// Data model representing a gallery image (or a web fallback image)
data class GalleryImage(
    val id: Long,
    val uri: Uri,
    val name: String,
    val size: Long,
    val dateAdded: Long,
    val isDemo: Boolean = false
)

// Viewmodel managing application state, MediaStore queries, and Scoped Storage deletion
class GalleryViewModel : ViewModel() {
    var currentScreen by mutableStateOf<AppScreen>(AppScreen.Splash)
        private set

    val images = mutableStateListOf<GalleryImage>()

    var currentIndex by mutableStateOf(0)
        private set

    var keptCount by mutableStateOf(0)
        private set

    var deletedCount by mutableStateOf(0)
        private set

    // Stream of pending delete intent requests (for Android 10+ RecoverableSecurityException)
    private val _pendingDeleteSender = MutableStateFlow<IntentSenderRequest?>(null)
    val pendingDeleteSender: StateFlow<IntentSenderRequest?> = _pendingDeleteSender.asStateFlow()

    private var pendingImageToDelete: GalleryImage? = null

    fun finishSplash() {
        currentScreen = AppScreen.Home
    }

    fun loadGallery(context: Context) {
        val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            requiredPermission
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            Toast.makeText(context, "Izin akses penyimpanan dibutuhkan.", Toast.LENGTH_SHORT).show()
            return
        }

        val loadedList = queryGalleryImages(context)
        images.clear()
        if (loadedList.isEmpty()) {
            images.addAll(getDemoImages())
            Toast.makeText(context, "Galeri kosong. Memuat contoh gambar demo!", Toast.LENGTH_LONG).show()
        } else {
            images.addAll(loadedList)
        }

        currentIndex = 0
        keptCount = 0
        deletedCount = 0
        currentScreen = AppScreen.Swiping
    }

    fun loadDemoMode() {
        images.clear()
        images.addAll(getDemoImages())
        currentIndex = 0
        keptCount = 0
        deletedCount = 0
        currentScreen = AppScreen.Swiping
    }

    private fun queryGalleryImages(context: Context): List<GalleryImage> {
        val list = mutableListOf<GalleryImage>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "Gambar_${id}.jpg"
                    val size = cursor.getLong(sizeCol)
                    val dateAdded = cursor.getLong(dateCol)
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    list.add(GalleryImage(id, uri, name, size, dateAdded, isDemo = false))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun getDemoImages(): List<GalleryImage> {
        return listOf(
            GalleryImage(
                1,
                Uri.parse("https://images.unsplash.com/photo-1551024601-bec78aea704b?auto=format&fit=crop&q=80&w=800"),
                "Donut_Pink_Minimalist.jpg",
                2457600L,
                System.currentTimeMillis() / 1000 - 3600 * 24,
                isDemo = true
            ),
            GalleryImage(
                2,
                Uri.parse("https://images.unsplash.com/photo-1507525428034-b723cf961d3e?auto=format&fit=crop&q=80&w=800"),
                "Pantai_Sutra_Minimal.jpg",
                3145728L,
                System.currentTimeMillis() / 1000 - 3600 * 48,
                isDemo = true
            ),
            GalleryImage(
                3,
                Uri.parse("https://images.unsplash.com/photo-1447752875215-b2761acb3c5d?auto=format&fit=crop&q=80&w=800"),
                "Hutan_Sunyi_Aesthetic.jpg",
                4194304L,
                System.currentTimeMillis() / 1000 - 3600 * 72,
                isDemo = true
            ),
            GalleryImage(
                4,
                Uri.parse("https://images.unsplash.com/photo-1513542789411-b6a5d4f31634?auto=format&fit=crop&q=80&w=800"),
                "Workspace_Zen_Clean.jpg",
                1887436L,
                System.currentTimeMillis() / 1000 - 3600 * 96,
                isDemo = true
            )
        )
    }

    fun keepCurrentImage() {
        if (currentIndex < images.size) {
            keptCount++
            moveToNext()
        }
    }

    fun deleteCurrentImage(context: Context) {
        if (currentIndex >= images.size) return

        val currentImage = images[currentIndex]
        if (currentImage.isDemo) {
            deletedCount++
            Toast.makeText(context, "Gambar demo dihapus!", Toast.LENGTH_SHORT).show()
            moveToNext()
            return
        }

        try {
            val rowsDeleted = context.contentResolver.delete(currentImage.uri, null, null)
            if (rowsDeleted > 0) {
                deletedCount++
                Toast.makeText(context, "Foto berhasil dihapus!", Toast.LENGTH_SHORT).show()
                moveToNext()
            } else {
                deletedCount++
                Toast.makeText(context, "Foto dilewati/ditandai dihapus.", Toast.LENGTH_SHORT).show()
                moveToNext()
            }
        } catch (securityException: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val recoverableSecurityException = securityException as? RecoverableSecurityException
                if (recoverableSecurityException != null) {
                    pendingImageToDelete = currentImage
                    val intentSenderRequest = IntentSenderRequest.Builder(
                        recoverableSecurityException.userAction.actionIntent.intentSender
                    ).build()
                    _pendingDeleteSender.value = intentSenderRequest
                } else {
                    deletedCount++
                    moveToNext()
                }
            } else {
                Toast.makeText(context, "Gagal menghapus file (Akses menulis ditolak).", Toast.LENGTH_SHORT).show()
                deletedCount++
                moveToNext()
            }
        }
    }

    fun onPendingDeleteResult(success: Boolean) {
        _pendingDeleteSender.value = null
        if (success) {
            deletedCount++
            moveToNext()
        } else {
            moveToNext()
        }
        pendingImageToDelete = null
    }

    private fun moveToNext() {
        currentIndex++
        if (currentIndex >= images.size) {
            currentScreen = AppScreen.Finished
        }
    }

    fun restartApp() {
        currentIndex = 0
        keptCount = 0
        deletedCount = 0
        images.clear()
        currentScreen = AppScreen.Home
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainAppContent()
            }
        }
    }
}

@Composable
fun MainAppContent(viewModel: GalleryViewModel = viewModel()) {
    val context = LocalContext.current

    // Observe permission events to launch RecoverableSecurityException on Android 10+
    val pendingDeleteSender by viewModel.pendingDeleteSender.collectAsState()
    val deleteIntentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onPendingDeleteResult(result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(pendingDeleteSender) {
        pendingDeleteSender?.let { senderRequest ->
            deleteIntentLauncher.launch(senderRequest)
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundClean)
            .statusBarsPadding()
            .navigationBarsPadding(),
        containerColor = BackgroundClean
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (viewModel.currentScreen) {
                is AppScreen.Splash -> SplashScreen(
                    onFinished = { viewModel.finishSplash() }
                )
                is AppScreen.Home -> HomeScreen(
                    onOpenGallery = { viewModel.loadGallery(context) },
                    onDemoMode = { viewModel.loadDemoMode() }
                )
                is AppScreen.Swiping -> SwipingScreen(
                    viewModel = viewModel,
                    onExit = { viewModel.restartApp() }
                )
                is AppScreen.Finished -> FinishedScreen(
                    viewModel = viewModel,
                    onRestart = { viewModel.restartApp() }
                )
            }
        }
    }
}

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    var startAnimation by remember { mutableStateOf(false) }
    val alphaAnim by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
        label = "splash_fade"
    )
    val scaleAnim by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0.85f,
        animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
        label = "splash_scale"
    )

    LaunchedEffect(Unit) {
        startAnimation = true
        kotlinx.coroutines.delay(2200)
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundClean),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .graphicsLayer(
                    alpha = alphaAnim,
                    scaleX = scaleAnim,
                    scaleY = scaleAnim
                )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "sanca",
                    color = PrimaryClean,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 3.sp,
                    fontFamily = FontFamily.SansSerif
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Developer",
                    color = PrimaryClean,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    fontFamily = FontFamily.SansSerif
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "CLEAN GALLERY SWIPE",
                color = TextMedium,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 4.sp
            )
        }
    }
}

@Composable
fun HomeScreen(onOpenGallery: () -> Unit, onDemoMode: () -> Unit) {
    val context = LocalContext.current
    val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            onOpenGallery()
        } else {
            Toast.makeText(
                context,
                "Mohon izinkan akses galeri untuk mengelola foto asli Anda.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Header
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 24.dp)
        ) {
            Text(
                text = "SANCA DEVELOPER",
                color = PrimaryClean,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
                modifier = Modifier.opacity(0.6f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Gallery Organizer",
                color = TextDark,
                fontSize = 28.sp,
                fontWeight = FontWeight.Light,
                textAlign = TextAlign.Center
            )
        }

        // Center Graphic Card (matches mockup)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 5f)
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            // Rotating offset decorative backdrop shapes
            Box(
                modifier = Modifier
                    .fillMaxSize(0.9f)
                    .rotate(3f)
                    .background(Color(0xFFEADDFF), RoundedCornerShape(32.dp))
            )
            Box(
                modifier = Modifier
                    .fillMaxSize(0.95f)
                    .rotate(-2f)
                    .background(Color(0xFFD0BCFF), RoundedCornerShape(32.dp))
            )

            // Primary presentation card
            Card(
                shape = RoundedCornerShape(32.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, BorderColor.copy(alpha = 0.5f), RoundedCornerShape(32.dp))
                    .shadow(4.dp, RoundedCornerShape(32.dp)),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = "https://images.unsplash.com/photo-1551024601-bec78aea704b?auto=format&fit=crop&q=80&w=800",
                        contentDescription = "Minimalist graphic",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    // Beautiful gradient overlay for legibility
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)),
                                    startY = 250f
                                )
                            )
                    )
                    // Description
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(24.dp)
                    ) {
                        Text(
                            text = "Swipe to Organize",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Swipe left to delete, right to keep.",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // Action Buttons
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = {
                    val hasPermission = ContextCompat.checkSelfPermission(
                        context,
                        requiredPermission
                    ) == PackageManager.PERMISSION_GRANTED

                    if (hasPermission) {
                        onOpenGallery()
                    } else {
                        permissionLauncher.launch(requiredPermission)
                    }
                },
                shape = RoundedCornerShape(100.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SecondaryClean,
                    contentColor = TextDarkPurple
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("open_gallery_button"),
                contentPadding = PaddingValues(horizontal = 24.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = TextDarkPurple
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Open Gallery",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Demo Mode fallback button
            TextButton(
                onClick = { onDemoMode() },
                modifier = Modifier.testTag("demo_mode_button")
            ) {
                Text(
                    text = "Coba Mode Demo (Tanpa Izin Galeri)",
                    color = PrimaryClean,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.2.sp
                )
            }
        }
    }
}

@Composable
fun SwipingScreen(
    viewModel: GalleryViewModel,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val images = viewModel.images
    val currentIndex = viewModel.currentIndex

    val currentImage = images.getOrNull(currentIndex)
    val nextImage = images.getOrNull(currentIndex + 1)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // App header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "SANCA DEVELOPER",
                    color = PrimaryClean,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Text(
                    text = "Swipe Organizer",
                    color = TextDark,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Simple Exit/Reset Action Icon Button
            IconButton(
                onClick = onExit,
                modifier = Modifier
                    .background(Color.White, CircleShape)
                    .border(1.dp, BorderColor, CircleShape)
                    .size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Restart",
                    tint = TextMedium,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Active Deck Container
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            if (currentImage != null) {
                // Background card if there's a next item
                if (nextImage != null) {
                    Card(
                        shape = RoundedCornerShape(32.dp),
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .aspectRatio(4f / 5f)
                            .graphicsLayer {
                                scaleX = 0.95f
                                scaleY = 0.95f
                                rotationZ = 3f
                            }
                            .shadow(2.dp, RoundedCornerShape(32.dp)),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            AsyncImage(
                                model = nextImage.uri,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                // Active Top Card
                key(currentImage.id) {
                    SwipeCardItem(
                        image = currentImage,
                        onSwipeLeft = { viewModel.deleteCurrentImage(context) },
                        onSwipeRight = { viewModel.keepCurrentImage() }
                    )
                }
            } else {
                // Deck empty state
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White, RoundedCornerShape(32.dp))
                        .border(1.dp, BorderColor, RoundedCornerShape(32.dp))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "🎉",
                            fontSize = 48.sp,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        Text(
                            text = "Semua foto selesai diperiksa!",
                            color = TextDark,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Anda bisa melihat statistik pengaturan file sekarang.",
                            color = TextMedium,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // Bottom controller buttons
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // DELETE Button (Swipe Left trigger)
                FloatingActionButton(
                    onClick = { viewModel.deleteCurrentImage(context) },
                    containerColor = Color.White,
                    contentColor = RedDelete,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(68.dp)
                        .border(1.dp, BorderColor, CircleShape)
                        .testTag("delete_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Hapus (Swipe Kiri)",
                        modifier = Modifier.size(28.dp),
                        tint = RedDelete
                    )
                }

                Spacer(modifier = Modifier.width(48.dp))

                // KEEP Button (Swipe Right trigger)
                FloatingActionButton(
                    onClick = { viewModel.keepCurrentImage() },
                    containerColor = PrimaryClean,
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(68.dp)
                        .shadow(4.dp, CircleShape)
                        .testTag("keep_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Simpan (Swipe Kanan)",
                        modifier = Modifier.size(28.dp),
                        tint = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Geser ke kiri untuk hapus, ke kanan untuk simpan",
                color = TextMedium,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )

            // Indicator Dots
            if (images.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val total = images.size
                    val active = currentIndex.coerceAtMost(total - 1)
                    repeat(total.coerceAtMost(6)) { index ->
                        val isSelected = index == active % 6
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .height(6.dp)
                                .width(if (isSelected) 24.dp else 6.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) PrimaryClean else BorderColor)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SwipeCardItem(
    image: GalleryImage,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit
) {
    var offsetX by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()

    val animOffsetX = remember { Animatable(0f) }

    LaunchedEffect(offsetX) {
        animOffsetX.snapTo(offsetX)
    }

    // Lower threshold for a super smooth and fast, sensitive swipe trigger
    val swipeThreshold = 140f

    Card(
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 5f)
            .pointerInput(image.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (offsetX < -swipeThreshold) {
                            scope.launch {
                                animOffsetX.animateTo(-1200f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                                onSwipeLeft()
                            }
                        } else if (offsetX > swipeThreshold) {
                            scope.launch {
                                animOffsetX.animateTo(1200f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                                onSwipeRight()
                            }
                        } else {
                            scope.launch {
                                animOffsetX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                                offsetX = 0f
                            }
                        }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount
                    }
                )
            }
            .graphicsLayer {
                translationX = animOffsetX.value
                translationY = 0f
                rotationZ = animOffsetX.value * 0.035f
            }
            .border(1.dp, BorderColor.copy(alpha = 0.5f), RoundedCornerShape(32.dp))
            .shadow(6.dp, RoundedCornerShape(32.dp))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = image.uri,
                contentDescription = "Swipe Photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // Drag indicator badges (appear immediately on slight swipe)
            if (offsetX < -40f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(24.dp)
                        .rotate(-12f)
                        .border(4.dp, RedDelete, RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "DELETE",
                        color = RedDelete,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                }
            } else if (offsetX > 40f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(24.dp)
                        .rotate(12f)
                        .border(4.dp, PrimaryClean, RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "KEEP",
                        color = PrimaryClean,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                }
            }

            // Metadata info panel overlay
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(20.dp)
            ) {
                Column {
                    Text(
                        text = image.name,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${formatSize(image.size)} • ${formatDate(image.dateAdded)}",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
fun FinishedScreen(
    viewModel: GalleryViewModel,
    onRestart: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Simple elegant top identity
        Text(
            text = "SANCA DEVELOPER",
            color = PrimaryClean,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 3.sp,
            modifier = Modifier.padding(top = 24.dp)
        )

        // Celebration details
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .background(SecondaryClean, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = PrimaryClean,
                    modifier = Modifier.size(48.dp)
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = "Organisasi Selesai!",
                color = TextDark,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Semua foto di galeri Anda telah dikelompokkan dengan rapi.",
                color = TextMedium,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(36.dp))

            // Beautiful minimal score / stats board
            Row(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .background(Color.White, RoundedCornerShape(24.dp))
                    .border(1.dp, BorderColor.copy(alpha = 0.4f), RoundedCornerShape(24.dp))
                    .padding(vertical = 24.dp, horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = viewModel.keptCount.toString(),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Black,
                        color = PrimaryClean
                    )
                    Text(
                        text = "Disimpan (Keep)",
                        fontSize = 12.sp,
                        color = TextMedium,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Vertical divider line
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(48.dp)
                        .background(BorderColor.copy(alpha = 0.6f))
                )

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = viewModel.deletedCount.toString(),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Black,
                        color = RedDelete
                    )
                    Text(
                        text = "Dihapus (Delete)",
                        fontSize = 12.sp,
                        color = TextMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Action controls
        Button(
            onClick = onRestart,
            shape = RoundedCornerShape(100.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = PrimaryClean,
                contentColor = Color.White
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag("restart_button")
        ) {
            Text(
                text = "Organisasi Baru / Mulai Ulang",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// Helpers
fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, 3)
    return String.format(Locale.getDefault(), "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

fun formatDate(timestampSec: Long): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    return sdf.format(Date(timestampSec * 1000))
}

// Custom extension to easily control opacity
@Composable
fun Modifier.opacity(value: Float): Modifier = this.graphicsLayer(alpha = value)
