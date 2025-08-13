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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

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
    // val earnings: Double, // REMOVED
    var status: OrderStatus,
    var tipAmount: Double, // Tip is now mutable and starts at 0
    val itemCount: Int,
    val timeLimitMinutes: Int,
    val acceptedTimestamp: Long? = null,
    val completionTimestamp: Long? = null,
    val orderTotal: Double,
    val deliveryCharge: Double,
    val surgeCharge: Double
) {
    val cashToCollect: Double
        get() = orderTotal + deliveryCharge + surgeCharge
}


data class BalanceSheet(
    // val totalEarnings: Double, // REMOVED
    val tips: Double
)

enum class OrderStatus {
    PENDING, ACCEPTED, REJECTED, COMPLETED
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
        // "earnings" parameter removed from Order constructor
        Order("ORD-101", "Pizza Junction", "123 MG Road, Koramangala", "456 Indiranagar, 5th Main", OrderStatus.PENDING, 0.0, 3, 5, orderTotal = 750.0, deliveryCharge = 40.0, surgeCharge = 10.0)
    ))

    private val sampleRiders = mutableMapOf(
        1L to RiderProfile(1L, "rider_one", "Suresh Kumar", "Honda Activa", "AP 39 AB 1234", null, true)
    )

    // "totalEarnings" removed from BalanceSheet
    private val balanceSheet = MutableStateFlow(BalanceSheet(tips = 0.0))


    val orders: StateFlow<List<Order>> = sampleOrders.asStateFlow()
    val balance: StateFlow<BalanceSheet> = balanceSheet.asStateFlow()

    fun getRiderProfile(riderId: Long): Flow<RiderProfile?> {
        return flowOf(sampleRiders[riderId])
    }

    suspend fun updateRiderStatus(riderId: Long, isAvailable: Boolean) {
        delay(300)
        sampleRiders[riderId]?.let {
            sampleRiders[riderId] = it.copy(isAvailable = isAvailable)
        }
    }

    suspend fun updateRiderProfile(riderId: Long, name: String, vehicleModel: String, vehicleNumber: String, imageUri: Uri?): Result<Unit> {
        delay(1000)
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
        sampleOrders.update { currentOrders ->
            currentOrders.map {
                if (it.id == orderId) {
                    it.copy(status = OrderStatus.ACCEPTED, acceptedTimestamp = System.currentTimeMillis())
                } else it
            }
        }
    }

    suspend fun completeOrder(orderId: String, additionalTip: Double) {
        val order = sampleOrders.value.find { it.id == orderId }
        if (order != null) {
            // Update to no longer add to totalEarnings
            balanceSheet.update {
                it.copy(
                    tips = it.tips + additionalTip
                )
            }
            // Update the specific order with the new tip and status
            sampleOrders.update { currentOrders ->
                currentOrders.map {
                    if (it.id == orderId) {
                        it.copy(
                            status = OrderStatus.COMPLETED,
                            completionTimestamp = System.currentTimeMillis(),
                            tipAmount = additionalTip
                        )
                    } else it
                }
            }
        }
    }

    suspend fun abortOrder(orderId: String) {
        updateOrderStatus(orderId, OrderStatus.REJECTED)
    }

    private fun updateOrderStatus(orderId: String, newStatus: OrderStatus) {
        sampleOrders.update { currentOrders ->
            currentOrders.map {
                if (it.id == orderId) it.copy(status = newStatus, completionTimestamp = System.currentTimeMillis()) else it
            }
        }
    }
}


// ================================================================================
// 2. VIEWMODELS
// ================================================================================

enum class ProcessedOrderFilter { ACCEPTED, COMPLETED }

class RiderViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = FakeRepository
    private val riderId = SessionManager.getRiderId(application)

    private val _profileState = MutableStateFlow<RiderProfile?>(null)
    val profileState = _profileState.asStateFlow()

    private val _uiEvent = MutableSharedFlow<UiEvent>()
    val uiEvent = _uiEvent.asSharedFlow()

    private val _processedOrderFilter = MutableStateFlow(ProcessedOrderFilter.ACCEPTED)
    val processedOrderFilter = _processedOrderFilter.asStateFlow()

    val pendingOrders = repository.orders.map { orders ->
        orders.filter { it.status == OrderStatus.PENDING }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val processedOrders = combine(repository.orders, _processedOrderFilter) { orders, filter ->
        orders.filter { it.status.name == filter.name }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())


    // Updated default BalanceSheet value
    val balanceSheet = repository.balance.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BalanceSheet(0.0))


    init {
        loadProfile()
    }

    fun setProcessedOrderFilter(filter: ProcessedOrderFilter) {
        _processedOrderFilter.value = filter
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
            loadProfile()
            _uiEvent.emit(UiEvent.ShowToast(if (!currentStatus) "You are now online!" else "You are now offline."))
        }
    }

    fun acceptOrder(orderId: String) {
        viewModelScope.launch {
            val wasLastOrder = pendingOrders.value.size == 1 && pendingOrders.value.first().id == orderId
            repository.acceptOrder(orderId)
            _uiEvent.emit(UiEvent.ShowToast("Order Accepted!"))
            if (wasLastOrder) {
                _uiEvent.emit(UiEvent.NavigateToOrders)
            }
        }
    }

    fun completeOrder(orderId: String, additionalTip: Double) {
        viewModelScope.launch {
            repository.completeOrder(orderId, additionalTip)
            _uiEvent.emit(UiEvent.ShowToast("Order Marked as Completed."))
        }
    }

    fun abortOrder(orderId: String) {
        viewModelScope.launch {
            repository.abortOrder(orderId)
            _uiEvent.emit(UiEvent.ShowToast("Order Aborted."))
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
    data object NavigateToOrders : UiEvent()
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
    const val ORDERS = "orders"
    const val PROFILE = "profile"
    const val EDIT_PROFILE = "edit_profile"
}

@Composable
fun MainScreen(riderViewModel: RiderViewModel) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val bottomNavRoutes = setOf(AppRoutes.HOME, AppRoutes.ORDERS, AppRoutes.PROFILE)
    val shouldShowBottomBar = currentRoute in bottomNavRoutes

    Scaffold(
        bottomBar = {
            AnimatedVisibility(visible = shouldShowBottomBar) {
                AppBottomNavigation(navController = navController)
            }
        }
    ) { innerPadding ->
        AppNavigation(
            navController = navController,
            riderViewModel = riderViewModel,
            paddingValues = innerPadding
        )
    }
}


@Composable
fun AppNavigation(navController: NavHostController, riderViewModel: RiderViewModel, paddingValues: PaddingValues) {
    NavHost(
        navController = navController,
        startDestination = AppRoutes.LOGIN,
        modifier = Modifier.padding(paddingValues)
    ) {
        composable(AppRoutes.LOGIN) {
            LoginScreen(navController = navController)
        }
        composable(AppRoutes.HOME) {
            HomeScreen(navController = navController, viewModel = riderViewModel)
        }
        composable(AppRoutes.ORDERS) {
            OrdersScreen(viewModel = riderViewModel)
        }
        composable(AppRoutes.PROFILE) {
            ProfileScreen(navController = navController, viewModel = riderViewModel)
        }
        composable(AppRoutes.EDIT_PROFILE) {
            EditProfileScreen(navController = navController, viewModel = riderViewModel)
        }
    }
}

@Composable
fun AppBottomNavigation(navController: NavController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val items = listOf(
        AppRoutes.HOME to Icons.Default.Home,
        AppRoutes.ORDERS to Icons.AutoMirrored.Filled.ReceiptLong,
        AppRoutes.PROFILE to Icons.Default.Person
    )

    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        items.forEach { (route, icon) ->
            val label = route.replaceFirstChar { it.titlecase() }
            NavigationBarItem(
                selected = currentRoute == route,
                onClick = {
                    navController.navigate(route) {
                        popUpTo(navController.graph.startDestinationId) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(icon, contentDescription = label) },
                label = { Text(label) }
            )
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
        OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
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
        ) { Text("Login") }
    }
}


// --- Home Screen (Upcoming Orders) ---
@Composable
fun HomeScreen(navController: NavController, viewModel: RiderViewModel) {
    val profile by viewModel.profileState.collectAsState()
    val pendingOrders by viewModel.pendingOrders.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                is UiEvent.ShowToast -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
                is UiEvent.NavigateToOrders -> {
                    navController.navigate(AppRoutes.ORDERS)
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        profile?.let {
            HomeTopBar(
                profile = it,
                onStatusChangeClick = { viewModel.toggleAvailability() },
                onProfileClick = { navController.navigate(AppRoutes.PROFILE) }
            )
        }

        if (profile?.isAvailable == false) {
            EmptyState(message = "You are currently offline. Go online to receive new order requests.")
        } else if (pendingOrders.isEmpty()) {
            EmptyState(message = "No new orders right now. We'll notify you!")
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(pendingOrders) { order ->
                    OrderRequestCard(
                        order = order,
                        onAccept = { viewModel.acceptOrder(order.id) },
                        onDeny = { viewModel.abortOrder(order.id) }
                    )
                }
            }
        }
    }
}

// --- Orders Screen (Processed Orders & Balance) ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersScreen(viewModel: RiderViewModel) {
    val processedOrders by viewModel.processedOrders.collectAsState()
    val balanceSheet by viewModel.balanceSheet.collectAsState()
    val selectedFilter by viewModel.processedOrderFilter.collectAsState()

    var showFilterMenu by remember { mutableStateOf(false) }
    var orderForConfirmation by remember { mutableStateOf<Pair<String, String>?>(null) }
    var orderForCollection by remember { mutableStateOf<Order?>(null) }

    if (orderForConfirmation != null) {
        val (action, orderId) = orderForConfirmation!!
        ConfirmationDialog(
            action = action,
            onConfirm = {
                if (action == "Abort") {
                    viewModel.abortOrder(orderId)
                } else {
                    orderForCollection = processedOrders.find { it.id == orderId }
                }
                orderForConfirmation = null
            },
            onDismiss = { orderForConfirmation = null }
        )
    }

    if (orderForCollection != null) {
        AmountCollectionDialog(
            order = orderForCollection!!,
            onDismiss = { orderForCollection = null },
            onSubmit = { collectedAmount, addChangeAsTip ->
                val order = orderForCollection!!
                val change = collectedAmount - order.cashToCollect
                val tipFromChange = if (addChangeAsTip && change > 0) change else 0.0
                viewModel.completeOrder(order.id, tipFromChange)
                orderForCollection = null
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Order History & Tips") }, // UPDATED TITLE
                actions = {
                    Box {
                        IconButton(onClick = { showFilterMenu = true }) { Icon(Icons.Default.FilterList, contentDescription = "Filter Orders") }
                        DropdownMenu(expanded = showFilterMenu, onDismissRequest = { showFilterMenu = false }) {
                            DropdownMenuItem(text = { Text("Accepted") }, onClick = { viewModel.setProcessedOrderFilter(ProcessedOrderFilter.ACCEPTED); showFilterMenu = false })
                            DropdownMenuItem(text = { Text("Completed") }, onClick = { viewModel.setProcessedOrderFilter(ProcessedOrderFilter.COMPLETED); showFilterMenu = false })
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { BalanceSheetCard(balance = balanceSheet) }
            item { Text("Orders (${selectedFilter.name.lowercase().replaceFirstChar { it.titlecase() }})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }

            if (processedOrders.isEmpty()) {
                item { EmptyState(message = "No orders found for this filter.") }
            } else {
                items(processedOrders) { order ->
                    ProcessedOrderCard(
                        order = order,
                        onEndOrder = { orderForConfirmation = "End" to order.id },
                        onAbortOrder = { orderForConfirmation = "Abort" to order.id }
                    )
                }
            }
        }
    }
}

// --- Dialogs ---
@Composable
fun ConfirmationDialog(action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm Action") },
        text = { Text("Are you sure you want to '$action' this order?") },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = if (action == "Abort") PrimaryRed else GreenAccept)
            ) { Text("Yes, $action") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun AmountCollectionDialog(order: Order, onDismiss: () -> Unit, onSubmit: (Double, Boolean) -> Unit) {
    var amountText by remember { mutableStateOf("") }
    var addChangeAsTip by remember { mutableStateOf(false) }

    val collectedAmount = amountText.toDoubleOrNull() ?: 0.0
    val isAmountSufficient = collectedAmount >= order.cashToCollect
    val change = collectedAmount - order.cashToCollect

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Collect Payment") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Please collect ₹%.2f".format(order.cashToCollect), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter { char -> char.isDigit() || char == '.' } },
                    label = { Text("Amount Received") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = !isAmountSufficient && amountText.isNotEmpty()
                )
                if (!isAmountSufficient && amountText.isNotEmpty()) {
                    Text("Insufficient amount", color = MaterialTheme.colorScheme.error)
                }
                if (isAmountSufficient && change > 0) {
                    Text("Change to give back: ₹%.2f".format(change), color = GreenAccept)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = addChangeAsTip, onCheckedChange = { addChangeAsTip = it })
                        Text("Add change as tip")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(collectedAmount, addChangeAsTip) },
                enabled = isAmountSufficient
            ) { Text("Submit") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}


@Composable
fun BalanceSheetCard(balance: BalanceSheet) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(4.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your Tips", style = MaterialTheme.typography.headlineSmall) // UPDATED TITLE
            Divider()
            // BalanceRow("Total Earnings", balance.totalEarnings) // REMOVED
            BalanceRow("Tips Collected", balance.tips)
        }
    }
}

@Composable
fun BalanceRow(label: String, amount: Double) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Text(text = "₹%.2f".format(amount), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ProcessedOrderCard(order: Order, onEndOrder: () -> Unit, onAbortOrder: () -> Unit) {
    var isExpanded by remember { mutableStateOf(false) }
    val rotationAngle by animateFloatAsState(targetValue = if (isExpanded) 180f else 0f, label = "expansion_arrow")

    Card(modifier = Modifier.fillMaxWidth().clickable { isExpanded = !isExpanded }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(order.restaurantName, fontWeight = FontWeight.Bold)
                    Text("Order #${order.id}", style = MaterialTheme.typography.bodySmall)
                }
                if (order.status == OrderStatus.ACCEPTED) {
                    OrderTimer(acceptedTimestamp = order.acceptedTimestamp, timeLimitMinutes = order.timeLimitMinutes)
                } else if (order.completionTimestamp != null && order.acceptedTimestamp != null) {
                    val timeTaken = order.completionTimestamp - order.acceptedTimestamp
                    val minutes = TimeUnit.MILLISECONDS.toMinutes(timeTaken)
                    val seconds = TimeUnit.MILLISECONDS.toSeconds(timeTaken) % 60
                    Text(text = "Took ${minutes}m ${seconds}s", style = MaterialTheme.typography.bodySmall, color = DarkGray, modifier = Modifier.padding(horizontal = 8.dp))
                }
                OrderStatusChip(status = order.status)
                Icon(imageVector = Icons.Default.KeyboardArrowDown, contentDescription = "Expand", modifier = Modifier.rotate(rotationAngle))
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    Divider()
                    Spacer(Modifier.height(12.dp))
                    OrderDetailRow(icon = Icons.Default.Store, label = "Pickup", value = order.pickupAddress)
                    OrderDetailRow(icon = Icons.Default.Home, label = "Delivery", value = order.deliveryAddress)
                    OrderDetailRow(icon = Icons.Default.ShoppingBag, label = "Items", value = "${order.itemCount} Items")
                    Divider(modifier = Modifier.padding(vertical = 12.dp))
                    OrderDetailRow(icon = Icons.Default.Receipt, label = "Order Total", value = "₹%.2f".format(order.orderTotal))
                    OrderDetailRow(icon = Icons.Default.TwoWheeler, label = "Delivery Charge", value = "₹%.2f".format(order.deliveryCharge))
                    OrderDetailRow(icon = Icons.Default.FlashOn, label = "Surge Charge", value = "₹%.2f".format(order.surgeCharge))
                    OrderDetailRow(icon = Icons.Default.Payments, label = "Amount to Collect", value = "₹%.2f".format(order.cashToCollect), isHighlight = true)
                    Divider(modifier = Modifier.padding(vertical = 12.dp))
                    if (order.tipAmount > 0) {
                        OrderDetailRow(icon = Icons.Default.Favorite, label = "Tip Received", value = "₹%.2f".format(order.tipAmount))
                    }
                    // OrderDetailRow for "Your Earnings" has been removed
                }
            }

            if (order.status == OrderStatus.ACCEPTED) {
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onAbortOrder, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer)) { Text("Abort Order") }
                    Button(onClick = onEndOrder, modifier = Modifier.weight(1f)) { Text("End Order") }
                }
            }
        }
    }
}

@Composable
fun OrderTimer(acceptedTimestamp: Long?, timeLimitMinutes: Int) {
    if (acceptedTimestamp == null) return

    var remainingTime by remember { mutableStateOf(0L) }

    LaunchedEffect(key1 = acceptedTimestamp) {
        while (true) {
            val elapsedMillis = System.currentTimeMillis() - acceptedTimestamp
            remainingTime = TimeUnit.MINUTES.toMillis(timeLimitMinutes.toLong()) - elapsedMillis
            delay(1000)
        }
    }

    val isOvertime = remainingTime < 0
    val absRemainingTime = abs(remainingTime)

    val minutes = TimeUnit.MILLISECONDS.toMinutes(absRemainingTime)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(absRemainingTime) % 60

    val timeString = String.format("%s%02d:%02d", if (isOvertime) "-" else "", minutes, seconds)
    val color = if (isOvertime) PrimaryRed else GreenAccept

    Text(text = timeString, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp))
}


@Composable
fun OrderDetailRow(icon: ImageVector, label: String, value: String, isHighlight: Boolean = false) {
    Row(modifier = Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(20.dp), tint = if (isHighlight) PrimaryRed else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Color.Gray)
            Text(text = value, style = MaterialTheme.typography.bodyLarge, fontWeight = if (isHighlight) FontWeight.Bold else FontWeight.Normal, color = if (isHighlight) PrimaryRed else MaterialTheme.colorScheme.onSurface)
        }
    }
}


@Composable
fun OrderStatusChip(status: OrderStatus) {
    val (text, color) = when (status) {
        OrderStatus.ACCEPTED -> "Accepted" to GreenAccept
        OrderStatus.REJECTED -> "Rejected" to PrimaryRed
        OrderStatus.COMPLETED -> "Completed" to DarkGray
        else -> status.name to LightGray
    }
    Text(text = text, color = Color.White, modifier = Modifier.background(color, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
}


@Composable
fun HomeTopBar(profile: RiderProfile, onStatusChangeClick: () -> Unit, onProfileClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "Welcome, ${profile.name}!", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text = profile.vehicleNumber, style = MaterialTheme.typography.bodyMedium, color = LightGray)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AvailabilityIndicator(isAvailable = profile.isAvailable, onClick = onStatusChangeClick)
            val placeholderPainter = rememberVectorPainter(image = Icons.Default.AccountCircle)
            AsyncImage(model = profile.imageUrl, contentDescription = "Rider Profile", modifier = Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onProfileClick), contentScale = ContentScale.Crop, placeholder = placeholderPainter, error = placeholderPainter)
        }
    }
}

@Composable
fun AvailabilityIndicator(isAvailable: Boolean, onClick: () -> Unit) {
    val (text, color, icon) = if (isAvailable) Triple("Online", GreenAccept, Icons.Default.CheckCircle) else Triple("Offline", Color.Gray, Icons.Default.Cancel)
    Card(shape = CircleShape, colors = CardDefaults.cardColors(containerColor = color), modifier = Modifier.clickable(onClick = onClick)) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Text(text, color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun OrderRequestCard(order: Order, onAccept: () -> Unit, onDeny: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(4.dp), shape = MaterialTheme.shapes.medium) {
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

            // The "Earnings" Row has been removed from here.

            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onDeny, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) { Text("Deny") }
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
        topBar = { TopAppBar(title = { Text("My Profile") }) },
        floatingActionButton = { FloatingActionButton(onClick = { navController.navigate(AppRoutes.EDIT_PROFILE) }) { Icon(Icons.Default.Edit, "Edit Profile") } }
    ) { padding ->
        profile?.let {
            Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val placeholderPainter = rememberVectorPainter(image = Icons.Default.AccountCircle)
                AsyncImage(model = it.imageUrl, contentDescription = "Profile Picture", modifier = Modifier.size(120.dp).clip(CircleShape), contentScale = ContentScale.Crop, placeholder = placeholderPainter, error = placeholderPainter)
                Text(it.name, style = MaterialTheme.typography.headlineSmall)
                Text("@${it.username}", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
                Divider()
                ProfileInfoRow(icon = Icons.Default.TwoWheeler, label = "Vehicle Model", value = it.vehicleModel)
                ProfileInfoRow(icon = Icons.Default.ConfirmationNumber, label = "Vehicle Number", value = it.vehicleNumber)
                Spacer(Modifier.weight(1f))
                Button(onClick = { viewModel.logout(); navController.navigate(AppRoutes.LOGIN) { popUpTo(0) { inclusive = true } } }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Logout") }
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

    val imagePickerLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { uri: Uri? -> imageUri = uri }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Profile") },
                navigationIcon = { IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { scope.launch { if (viewModel.updateProfile(name, vehicleModel, vehicleNumber, imageUri)) navController.popBackStack() } }) { Icon(Icons.Default.Save, "Save") } }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Box(modifier = Modifier.size(120.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clickable { imagePickerLauncher.launch("image/*") }, contentAlignment = Alignment.Center) {
                    val placeholderPainter = rememberVectorPainter(image = Icons.Default.AccountCircle)
                    AsyncImage(model = imageUri ?: profile?.imageUrl, contentDescription = "Profile Image", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, placeholder = placeholderPainter, error = placeholderPainter)
                    Icon(Icons.Default.Edit, "Edit", tint = Color.White.copy(alpha = 0.7f))
                }
            }
            item { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Full Name") }, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(value = vehicleModel, onValueChange = { vehicleModel = it }, label = { Text("Vehicle Model") }, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(value = vehicleNumber, onValueChange = { vehicleNumber = it }, label = { Text("Vehicle Number") }, modifier = Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
fun ProfileInfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
        setContent {
            HoneyRiderTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val riderViewModel: RiderViewModel = viewModel(factory = RiderViewModelFactory(application))
                    MainScreen(riderViewModel = riderViewModel)
                }
            }
        }
    }
}