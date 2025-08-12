package com.ss.honeyrider

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// ================================================================================
// 1. DATA LAYER (MODELS, REPOSITORY, SESSION MANAGER)
// ================================================================================

data class RiderProfile(
    val id: Long,
    val username: String,
    val name: String,
    val vehicleModel: String,
    val vehicleNumber: String,
    val imageUrl: String?,
    val isAvailable: Boolean
)

data class Order(
    val id: String,
    val restaurantName: String,
    val pickupAddress: String,
    val deliveryAddress: String,
    val earnings: Double,
    val status: OrderStatus
)

enum class OrderStatus {
    PENDING, ACCEPTED, REJECTED
}

object SessionManager {
    private const val PREFS_NAME = "RiderPrefs"
    private const val KEY_RIDER_ID = "rider_id"
    private const val DEFAULT_RIDER_ID = -1L

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveRiderId(context: Context, riderId: Long) {
        getPrefs(context).edit().putLong(KEY_RIDER_ID, riderId).apply()
    }

    fun getRiderId(context: Context): Long {
        return getPrefs(context).getLong(KEY_RIDER_ID, DEFAULT_RIDER_ID)
    }

    fun clearSession(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}


object FakeRepository {
    private val sampleOrders = MutableStateFlow(listOf(
        Order("ORD-101", "Pizza Junction", "123 MG Road, Koramangala", "456 Indiranagar, 5th Main", 55.00, OrderStatus.PENDING),
        Order("ORD-102", "Biryani House", "789 Jubilee Hills, Hyderabad", "101 Gachibowli Financial District", 70.00, OrderStatus.PENDING),
    ))

    private val sampleRiders = mutableMapOf(
        1L to RiderProfile(1L, "rider_one", "Suresh Kumar", "Honda Activa", "AP 39 AB 1234", null, true)
    )

    val orders: StateFlow<List<Order>> = sampleOrders.asStateFlow()

    fun getRiderProfile(riderId: Long): Flow<RiderProfile?> {
        return flowOf(sampleRiders[riderId])
    }

    suspend fun updateRiderStatus(riderId: Long, isAvailable: Boolean) {
        delay(300) // Simulate network
        sampleRiders[riderId]?.let {
            sampleRiders[riderId] = it.copy(isAvailable = isAvailable)
        }
    }

    suspend fun updateRiderProfile(riderId: Long, name: String, vehicleModel: String, vehicleNumber: String, imageUri: Uri?): Result<Unit> {
        delay(1000) // Simulate network
        val currentProfile = sampleRiders[riderId]
        if (currentProfile != null) {
            sampleRiders[riderId] = currentProfile.copy(
                name = name,
                vehicleModel = vehicleModel,
                vehicleNumber = vehicleNumber,
                imageUrl = imageUri?.toString() ?: currentProfile.imageUrl
            )
            return Result.success(Unit)
        }
        return Result.failure(Exception("Rider not found"))
    }

    suspend fun acceptOrder(orderId: String) {
        updateOrderStatus(orderId, OrderStatus.ACCEPTED)
    }

    suspend fun rejectOrder(orderId: String) {
        updateOrderStatus(orderId, OrderStatus.REJECTED)
    }

    private fun updateOrderStatus(orderId: String, newStatus: OrderStatus) {
        sampleOrders.update { currentOrders ->
            currentOrders.map { if (it.id == orderId) it.copy(status = newStatus) else it }
        }
    }
}


// ================================================================================
// 2. VIEWMODELS
// ================================================================================

class RiderViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = FakeRepository
    private val riderId = SessionManager.getRiderId(application)

    private val _profileState = MutableStateFlow<RiderProfile?>(null)
    val profileState = _profileState.asStateFlow()

    private val _uiEvent = MutableSharedFlow<UiEvent>()
    val uiEvent = _uiEvent.asSharedFlow()

    val orders = repository.orders

    init {
        loadProfile()
    }

    private fun loadProfile() {
        if (riderId != -1L) {
            viewModelScope.launch {
                repository.getRiderProfile(riderId).collect {
                    _profileState.value = it
                }
            }
        }
    }

    fun toggleAvailability() {
        viewModelScope.launch {
            val currentStatus = _profileState.value?.isAvailable ?: return@launch
            repository.updateRiderStatus(riderId, !currentStatus)
            loadProfile() // Refresh profile
            _uiEvent.emit(UiEvent.ShowToast(if (!currentStatus) "You are now online!" else "You are now offline."))
        }
    }

    fun acceptOrder(orderId: String) {
        viewModelScope.launch {
            repository.acceptOrder(orderId)
            _uiEvent.emit(UiEvent.ShowToast("Order Accepted!"))
        }
    }

    fun rejectOrder(orderId: String) {
        viewModelScope.launch {
            repository.rejectOrder(orderId)
            _uiEvent.emit(UiEvent.ShowToast("Order Rejected."))
        }
    }

    fun logout() {
        SessionManager.clearSession(getApplication())
    }

    suspend fun updateProfile(name: String, vehicleModel: String, vehicleNumber: String, imageUri: Uri?): Boolean {
        val result = repository.updateRiderProfile(riderId, name, vehicleModel, vehicleNumber, imageUri)
        return result.fold(
            onSuccess = {
                loadProfile()
                _uiEvent.emit(UiEvent.ShowToast("Profile Updated Successfully!"))
                true
            },
            onFailure = {
                _uiEvent.emit(UiEvent.ShowToast("Failed to update profile."))
                false
            }
        )
    }
}

class RiderViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RiderViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return RiderViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

sealed class UiEvent {
    data class ShowToast(val message: String) : UiEvent()
}


// ================================================================================
// 3. THEME
// ================================================================================

private val PrimaryRed = Color(0xFFE53935)
private val DarkGray = Color(0xFF424242)
private val SurfaceGrey = Color(0xFFF5F5F5)
private val LightGray = Color(0xFFBDBDBD)
private val GreenAccept = Color(0xFF4CAF50)

private val AppColorScheme = lightColorScheme(
    primary = PrimaryRed,
    onPrimary = Color.White,
    secondary = DarkGray,
    onSecondary = Color.White,
    background = Color(0xFFFAFAFA),
    onBackground = DarkGray,
    surface = Color.White,
    onSurface = DarkGray,
    surfaceVariant = SurfaceGrey
)

@Composable
fun HoneyRiderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        typography = Typography(
            headlineLarge = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
            titleLarge = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            bodyLarge = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp)
        ),
        shapes = Shapes(
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp)
        ),
        content = content
    )
}

// ================================================================================
// 4. NAVIGATION
// ================================================================================

object AppRoutes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val PROFILE = "profile"
    const val EDIT_PROFILE = "edit_profile"
}

@Composable
fun AppNavigation(navController: NavHostController, riderViewModel: RiderViewModel) {
    NavHost(navController = navController, startDestination = AppRoutes.LOGIN) {
        composable(AppRoutes.LOGIN) {
            LoginScreen(navController = navController)
        }
        composable(AppRoutes.HOME) {
            HomeScreen(navController = navController, viewModel = riderViewModel)
        }
        composable(AppRoutes.PROFILE) {
            ProfileScreen(navController = navController, viewModel = riderViewModel)
        }
        composable(AppRoutes.EDIT_PROFILE) {
            EditProfileScreen(navController = navController, viewModel = riderViewModel)
        }
    }
}

// ================================================================================
// 5. UI SCREENS & COMPONENTS
// ================================================================================

// --- Login Screen ---
@Composable
fun LoginScreen(navController: NavController) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.TwoWheeler, "Rider Icon", modifier = Modifier.size(80.dp), tint = PrimaryRed)
        Spacer(Modifier.height(16.dp))
        Text("Rider Login", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                if (username.isNotBlank() && password.isNotBlank()) {
                    SessionManager.saveRiderId(context, 1L)
                    navController.navigate(AppRoutes.HOME) {
                        popUpTo(AppRoutes.LOGIN) { inclusive = true }
                    }
                } else {
                    Toast.makeText(context, "Please enter username and password", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("Login")
        }
    }
}


// --- Home Screen ---
@Composable
fun HomeScreen(navController: NavController, viewModel: RiderViewModel) {
    val profile by viewModel.profileState.collectAsState()
    val orders by viewModel.orders.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            if (event is UiEvent.ShowToast) {
                Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    profile?.let { validProfile ->
        // *** THE FIX IS HERE ***
        // The main Column now has statusBarsPadding() to prevent overlap.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            HomeTopBar(
                profile = validProfile,
                onStatusChangeClick = { viewModel.toggleAvailability() },
                onProfileClick = { navController.navigate(AppRoutes.PROFILE) }
            )

            val pendingOrders = orders.filter { it.status == OrderStatus.PENDING }
            if (!validProfile.isAvailable) {
                EmptyState(message = "You are currently offline. Go online to receive new order requests.")
            } else if (pendingOrders.isEmpty()) {
                EmptyState(message = "No new orders right now. We'll notify you!")
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(pendingOrders) { order ->
                        OrderRequestCard(
                            order = order,
                            onAccept = { viewModel.acceptOrder(order.id) },
                            onReject = { viewModel.rejectOrder(order.id) }
                        )
                    }
                }
            }
        }
    } ?: Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun HomeTopBar(profile: RiderProfile, onStatusChangeClick: () -> Unit, onProfileClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Welcome, ${profile.name}!",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = profile.vehicleNumber,
                style = MaterialTheme.typography.bodyMedium,
                color = LightGray
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AvailabilityIndicator(isAvailable = profile.isAvailable, onClick = onStatusChangeClick)

            val placeholderPainter = rememberVectorPainter(image = Icons.Default.AccountCircle)
            AsyncImage(
                model = profile.imageUrl,
                contentDescription = "Rider Profile",
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onProfileClick),
                contentScale = ContentScale.Crop,
                placeholder = placeholderPainter,
                error = placeholderPainter
            )
        }
    }
}

@Composable
fun AvailabilityIndicator(isAvailable: Boolean, onClick: () -> Unit) {
    val (text, color, icon) = if (isAvailable) {
        Triple("Online", GreenAccept, Icons.Default.CheckCircle)
    } else {
        Triple("Offline", Color.Gray, Icons.Default.Cancel)
    }

    Card(
        shape = CircleShape,
        colors = CardDefaults.cardColors(containerColor = color),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Text(text, color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun OrderRequestCard(order: Order, onAccept: () -> Unit, onReject: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(4.dp),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("New Order Request!", style = MaterialTheme.typography.labelMedium, color = PrimaryRed)
            Text(order.restaurantName, style = MaterialTheme.typography.titleLarge)
            Text(order.pickupAddress, style = MaterialTheme.typography.bodyMedium)
            Divider(modifier = Modifier.padding(vertical = 12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                InfoColumn("Pickup", order.pickupAddress)
                InfoColumn("Drop", order.deliveryAddress, alignEnd = true)
            }
            Divider(modifier = Modifier.padding(vertical = 12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Earnings", style = MaterialTheme.typography.bodyLarge)
                Text("₹${order.earnings}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = GreenAccept)
            }
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = onReject,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) { Text("Reject") }
                Button(onClick = onAccept, modifier = Modifier.weight(1f)) { Text("Accept") }
            }
        }
    }
}

@Composable
fun InfoColumn(label: String, value: String, alignEnd: Boolean = false) {
    Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start, modifier = Modifier.widthIn(max = 150.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, textAlign = if (alignEnd) TextAlign.End else TextAlign.Start, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun EmptyState(message: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text = message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

// --- Profile Screens ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(navController: NavController, viewModel: RiderViewModel) {
    val profile by viewModel.profileState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Profile") },
                navigationIcon = { IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { navController.navigate(AppRoutes.EDIT_PROFILE) }) {
                Icon(Icons.Default.Edit, "Edit Profile")
            }
        }
    ) { padding ->
        profile?.let {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val placeholderPainter = rememberVectorPainter(image = Icons.Default.AccountCircle)
                AsyncImage(
                    model = it.imageUrl,
                    contentDescription = "Profile Picture",
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop,
                    placeholder = placeholderPainter,
                    error = placeholderPainter
                )
                Text(it.name, style = MaterialTheme.typography.headlineSmall)
                Text("@${it.username}", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
                Divider()
                ProfileInfoRow(icon = Icons.Default.TwoWheeler, label = "Vehicle Model", value = it.vehicleModel)
                ProfileInfoRow(icon = Icons.Default.ConfirmationNumber, label = "Vehicle Number", value = it.vehicleNumber)
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        viewModel.logout()
                        navController.navigate(AppRoutes.LOGIN) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Logout")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(navController: NavController, viewModel: RiderViewModel) {
    val profile by viewModel.profileState.collectAsState()
    val scope = rememberCoroutineScope()
    var name by remember(profile) { mutableStateOf(profile?.name ?: "") }
    var vehicleModel by remember(profile) { mutableStateOf(profile?.vehicleModel ?: "") }
    var vehicleNumber by remember(profile) { mutableStateOf(profile?.vehicleNumber ?: "") }
    var imageUri by remember { mutableStateOf<Uri?>(null) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? -> imageUri = uri }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Profile") },
                navigationIcon = { IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            if (viewModel.updateProfile(name, vehicleModel, vehicleNumber, imageUri)) {
                                navController.popBackStack()
                            }
                        }
                    }) { Icon(Icons.Default.Save, "Save") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { imagePickerLauncher.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    val placeholderPainter = rememberVectorPainter(image = Icons.Default.AccountCircle)
                    AsyncImage(
                        model = imageUri ?: profile?.imageUrl,
                        contentDescription = "Profile Image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        placeholder = placeholderPainter,
                        error = placeholderPainter
                    )
                    Icon(Icons.Default.Edit, "Edit", tint = Color.White.copy(alpha = 0.7f))
                }
            }
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Full Name") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = vehicleModel,
                    onValueChange = { vehicleModel = it },
                    label = { Text("Vehicle Model") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = vehicleNumber,
                    onValueChange = { vehicleNumber = it },
                    label = { Text("Vehicle Number") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun ProfileInfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = PrimaryRed, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Color.Gray)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

// ================================================================================
// 6. MAIN ACTIVITY
// ================================================================================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // This makes the app go edge-to-edge
        // WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            HoneyRiderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val riderViewModel: RiderViewModel = viewModel(factory = RiderViewModelFactory(application))
                    AppNavigation(navController = navController, riderViewModel = riderViewModel)
                }
            }
        }
    }
}