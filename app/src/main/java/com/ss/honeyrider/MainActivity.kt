package com.ss.honeyrider


import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import java.util.concurrent.TimeUnit
import kotlin.math.abs

// ================================================================================
// 1. DATA LAYER (MODELS, API, REPOSITORY, SESSION MANAGER)
// ================================================================================

// --- NEW Data classes for Login ---
data class LoginRequest(val username: String, val password: String)
data class LoginResponse(val token: String, val id: Long)


data class RiderProfile(
    val id: Long,
    val username: String,
    val name: String,
    val vehicleModel: String,
    val vehicleNumber: String,
    val imageUrl: String?,
    val isAvailable: Boolean
)

@JvmInline
value class OrderLine(private val line: Any)

// --- CHANGE #1: MODIFIED Order data class ---
data class Order(
    @SerializedName(value = "id", alternate = ["orderId"])
    val id: Long,

    @SerializedName("vendorName")
    val vendorName: String,

    @SerializedName("deliveryAddress")
    val deliveryAddress: String,

    @SerializedName("status")
    var status: String,

    @SerializedName("totalAmount")
    val totalAmount: Double,

    @SerializedName("orderLines")
    val orderLines: List<OrderLine>,

    @SerializedName("otp") // Added OTP field
    val otp: String? = null,

    // Renamed for clarity and to separate from OTP verification
    val tipAmount: Double = 0.0,
    val acceptedTimestamp: Long? = null,
    val pickupTimestamp: Long? = null, // This will now start the timer
    val completionTimestamp: Long? = null
) {
    val itemCount: Int get() = orderLines.size
    val pickupAddress: String get() = "Vendor Location"
    val timeLimitMinutes: Int = 30
    val deliveryCharge: Double = 30.0
    val surgeCharge: Double = 0.0
    val cashToCollect: Double get() = totalAmount + deliveryCharge + surgeCharge
}

data class BalanceSheet(
    val tips: Double
)

// --- CHANGE #2: UPDATED OrderStatus enum ---
enum class OrderStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    COMPLETED,
    READY, // Status when vendor has prepared the order
    ACCEPTED_BY_RIDER, // Status when rider accepts the job
    OUT_FOR_DELIVERY // Status after OTP verification
}

object SessionManager {
    private const val PREFS_NAME = "RiderPrefs"
    private const val KEY_RIDER_ID = "rider_id"
    private const val KEY_AUTH_TOKEN = "auth_token"
    private const val DEFAULT_RIDER_ID = -1L

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveAuthToken(context: Context, token: String) {
        getPrefs(context).edit().putString(KEY_AUTH_TOKEN, token).apply()
    }

    fun getAuthToken(context: Context): String? {
        return getPrefs(context).getString(KEY_AUTH_TOKEN, null)
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

// --- CHANGE #3: ADDED verifyOtp to ApiService ---
interface ApiService {
    @POST("api/auth/rider/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @GET("api/riders/{id}/profile")
    suspend fun getRiderProfile(@Path("id") riderId: Long): RiderProfile

    @PUT("api/riders/{id}/availability")
    suspend fun updateRiderStatus(@Path("id") riderId: Long, @Body isAvailable: Map<String, Boolean>): Response<Unit>

    @Multipart
    @PUT("api/riders/{id}/profile")
    suspend fun updateRiderProfile(
        @Path("id") riderId: Long,
        @Part("name") name: RequestBody,
        @Part("vehicleModel") vehicleModel: RequestBody,
        @Part("vehicleNumber") vehicleNumber: RequestBody,
        @Part image: MultipartBody.Part?
    ): Response<Unit>

    @PUT("api/orders/{id}/accept-by-rider")
    suspend fun acceptOrderByRider(@Path("id") orderId: Long): Response<Unit>

    @POST("api/orders/{id}/verify-otp") // New endpoint for OTP verification
    suspend fun verifyOtp(@Path("id") orderId: Long, @Body payload: Map<String, String>): Response<Unit>

    @PUT("api/orders/{id}/complete-by-rider")
    suspend fun completeOrderByRider(@Path("id") orderId: Long, @Body tip: Map<String, Double>): Response<Unit>

    @PUT("api/orders/{id}/abort-by-rider")
    suspend fun abortOrderByRider(@Path("id") orderId: Long): Response<Unit>
}

object RetrofitClient {
    private const val BASE_URL = "http://192.168.31.242:8080/"

    fun getInstance(context: Context): ApiService {
        val authInterceptor = Interceptor { chain ->
            val token = SessionManager.getAuthToken(context)
            val requestBuilder = chain.request().newBuilder()
            if (token != null) {
                requestBuilder.addHeader("Authorization", "Bearer $token")
            }
            chain.proceed(requestBuilder.build())
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .build()

        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}

class AuthRepository(private val apiService: ApiService) {
    suspend fun login(loginRequest: LoginRequest): Result<LoginResponse> {
        return try {
            val response = apiService.login(loginRequest)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Login failed: ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

// --- CHANGE #4: ADDED verifyOtp to RiderRepository ---
class RiderRepository(private val apiService: ApiService) {
    suspend fun getProfile(riderId: Long) = apiService.getRiderProfile(riderId)
    suspend fun updateStatus(riderId: Long, isAvailable: Boolean) = apiService.updateRiderStatus(riderId, mapOf("isAvailable" to isAvailable))
    suspend fun acceptOrder(orderId: Long) = apiService.acceptOrderByRider(orderId)
    suspend fun verifyOtp(orderId: Long, otp: String) = apiService.verifyOtp(orderId, mapOf("otp" to otp)) // New function
    suspend fun completeOrder(orderId: Long, tip: Double) = apiService.completeOrderByRider(orderId, mapOf("tip" to tip))
    suspend fun abortOrder(orderId: Long) = apiService.abortOrderByRider(orderId)

    suspend fun updateProfile(riderId: Long, name: String, vehicleModel: String, vehicleNumber: String, imageUri: Uri?, context: Context): Boolean {
        val nameBody = name.toRequestBody("text/plain".toMediaTypeOrNull())
        val modelBody = vehicleModel.toRequestBody("text/plain".toMediaTypeOrNull())
        val numberBody = vehicleNumber.toRequestBody("text/plain".toMediaTypeOrNull())

        var imagePart: MultipartBody.Part? = null
        if (imageUri != null) {
            val stream = context.contentResolver.openInputStream(imageUri)
            val bytes = stream?.readBytes()
            stream?.close()
            if (bytes != null) {
                val requestFile = bytes.toRequestBody("image/*".toMediaTypeOrNull())
                imagePart = MultipartBody.Part.createFormData("image", "profile.jpg", requestFile)
            }
        }
        val response = apiService.updateRiderProfile(riderId, nameBody, modelBody, numberBody, imagePart)
        return response.isSuccessful
    }
}


// ================================================================================
// 2. VIEWMODEL
// ================================================================================

data class RiderUiState(
    val isLoading: Boolean = true,
    val profile: RiderProfile? = null,
    val pendingOrders: List<Order> = emptyList(),
    val processedOrders: List<Order> = emptyList(),
    val balanceSheet: BalanceSheet = BalanceSheet(0.0),
    val processedOrderFilter: ProcessedOrderFilter = ProcessedOrderFilter.ALL
)

enum class ProcessedOrderFilter { ALL, ACCEPTED, COMPLETED, REJECTED }

sealed class UiEvent {
    data class ShowToast(val message: String) : UiEvent()
    object NavigateToHome : UiEvent()
}

// --- CHANGE #5: UPDATED RiderViewModel with new OTP logic ---
class RiderViewModel(
    application: Application,
    private val repository: RiderRepository,
    private val authRepository: AuthRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(RiderUiState())
    val uiState: StateFlow<RiderUiState> = _uiState.asStateFlow()

    private val _uiEvent = Channel<UiEvent>()
    val uiEvent = _uiEvent.receiveAsFlow()

    private val allProcessedOrders = mutableStateListOf<Order>()

    init {
        checkIfLoggedIn()
    }

    private fun checkIfLoggedIn() {
        val riderId = SessionManager.getRiderId(getApplication())
        if (riderId != -1L) {
            fetchProfile(riderId)
        } else {
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun onLoginClicked(username: String, password: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = authRepository.login(LoginRequest(username, password))
            result.onSuccess { response ->
                SessionManager.saveAuthToken(getApplication(), response.token)
                SessionManager.saveRiderId(getApplication(), response.id)
                fetchProfile(response.id)
                sendEvent(UiEvent.NavigateToHome)
            }.onFailure {
                sendEvent(UiEvent.ShowToast(it.message ?: "Login failed"))
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    private fun fetchProfile(riderId: Long) {
        viewModelScope.launch {
            try {
                val profile = repository.getProfile(riderId)
                _uiState.update { it.copy(profile = profile, isLoading = false) }
            } catch (e: Exception) {
                sendEvent(UiEvent.ShowToast("Failed to load profile"))
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }


    fun addNewOrderFromSocket(newOrder: Order) = viewModelScope.launch {
        // Check if the order is already in the processed list.
        val processedIndex = allProcessedOrders.indexOfFirst { it.id == newOrder.id }
        if (processedIndex != -1) {
            // This is an update to a processed order (e.g., READY -> OUT_FOR_DELIVERY)
            val updatedOrder = allProcessedOrders[processedIndex].copy(
                status = newOrder.status,
                otp = newOrder.otp ?: allProcessedOrders[processedIndex].otp,
                pickupTimestamp = if (newOrder.status.equals(OrderStatus.OUT_FOR_DELIVERY.name, true))
                    System.currentTimeMillis()
                else allProcessedOrders[processedIndex].pickupTimestamp
            )
            allProcessedOrders[processedIndex] = updatedOrder
            filterProcessedOrders()
            sendEvent(UiEvent.ShowToast("Order #${newOrder.id} status updated to ${newOrder.status.replace("_", " ")}"))
            return@launch
        }

        // Check if the order is already in the pending list.
        val pendingIndex = _uiState.value.pendingOrders.indexOfFirst { it.id == newOrder.id }
        if (pendingIndex != -1) {
            // This is an update to a pending order (e.g., ACCEPTED -> READY)
            if (newOrder.status.equals(OrderStatus.READY.name, true)) {
                val updatedOrder = _uiState.value.pendingOrders[pendingIndex].copy(
                    status = newOrder.status
                )
                _uiState.update { it.copy(
                    pendingOrders = it.pendingOrders.toMutableList().also { list -> list[pendingIndex] = updatedOrder }
                ) }
                sendEvent(UiEvent.ShowToast("Order #${newOrder.id} is ready for pickup!"))
            } else {
                _uiState.update { it.copy(
                    pendingOrders = it.pendingOrders.toMutableList().also { list -> list[pendingIndex] = newOrder }
                ) }
            }
            return@launch
        }

        // If the order is not found in any list, treat it as a new job offer.
        // This handles the initial broadcasting of orders.
        if (newOrder.status.equals(OrderStatus.ACCEPTED.name, true) || newOrder.status.equals(OrderStatus.READY.name, true)) {
            _uiState.update { it.copy(pendingOrders = it.pendingOrders + newOrder) }
            sendEvent(UiEvent.ShowToast("New Order #${newOrder.id} is now available!"))
        }
    }


    fun toggleAvailability() {
        viewModelScope.launch {
            val currentProfile = _uiState.value.profile ?: return@launch
            val riderId = SessionManager.getRiderId(getApplication())
            val newAvailability = !currentProfile.isAvailable

            if (!newAvailability) {
                getApplication<Application>().stopService(Intent(getApplication(), OrderSocketService::class.java))
            }

            try {
                val response = repository.updateStatus(riderId, newAvailability)
                if (response.isSuccessful) {
                    _uiState.update { it.copy(profile = currentProfile.copy(isAvailable = newAvailability)) }
                } else {
                    sendEvent(UiEvent.ShowToast("Failed to update status"))
                }
            } catch (e: Exception) {
                sendEvent(UiEvent.ShowToast("Network error"))
            }
        }
    }

    fun acceptOrder(orderId: Long) {
        viewModelScope.launch {
            try {
                val response = repository.acceptOrder(orderId)
                if (response.isSuccessful) {
                    val acceptedOrder = _uiState.value.pendingOrders.find { it.id == orderId }
                    if (acceptedOrder != null) {
                        _uiState.update {
                            it.copy(
                                pendingOrders = it.pendingOrders.filter { o -> o.id != orderId }
                            )
                        }
                        // Update status, but DO NOT start the timer here
                        val updatedOrder = acceptedOrder.copy(status = "ACCEPTED_BY_RIDER", acceptedTimestamp = System.currentTimeMillis())
                        allProcessedOrders.add(updatedOrder)
                        filterProcessedOrders()
                        sendEvent(UiEvent.ShowToast("Order Accepted! Proceed to vendor for pickup."))
                    }
                } else {
                    sendEvent(UiEvent.ShowToast("Failed to accept order"))
                }
            } catch (e: Exception) {
                sendEvent(UiEvent.ShowToast("Network error"))
            }
        }
    }

    // New function to handle OTP verification
    fun verifyOtp(orderId: Long, otp: String) {
        viewModelScope.launch {
            try {
                val response = repository.verifyOtp(orderId, otp)
                if (response.isSuccessful) {
                    // Find the order, update its status, AND set the pickupTimestamp to start the timer
                    updateLocalOrderStatus(orderId, "OUT_FOR_DELIVERY", newPickupTimestamp = System.currentTimeMillis())
                    sendEvent(UiEvent.ShowToast("OTP Verified! Delivery started."))
                } else {
                    sendEvent(UiEvent.ShowToast("Invalid OTP. Please try again."))
                }
            } catch (e: Exception) {
                sendEvent(UiEvent.ShowToast("Network error during OTP verification."))
            }
        }
    }

    fun completeOrder(orderId: Long, tip: Double) {
        viewModelScope.launch {
            try {
                val response = repository.completeOrder(orderId, tip)
                if(response.isSuccessful) {
                    updateLocalOrderStatus(orderId, "COMPLETED", newTip = tip)
                    sendEvent(UiEvent.ShowToast("Order Completed!"))
                } else {
                    sendEvent(UiEvent.ShowToast("Failed to complete order"))
                }
            } catch (e: Exception) {
                sendEvent(UiEvent.ShowToast("Network error"))
            }
        }
    }


    fun setProcessedOrderFilter(filter: ProcessedOrderFilter) {
        _uiState.update { it.copy(processedOrderFilter = filter) }
        filterProcessedOrders()
    }

    private fun filterProcessedOrders() {
        val filter = _uiState.value.processedOrderFilter
        val filteredList = if (filter == ProcessedOrderFilter.ALL) {
            allProcessedOrders
        } else {
            allProcessedOrders.filter { it.status.equals(filter.name, ignoreCase = true) }
        }
        _uiState.update { it.copy(processedOrders = filteredList.sortedByDescending { o -> o.acceptedTimestamp }) }
    }


    fun abortOrder(orderId: Long) {
        viewModelScope.launch {
            try {
                val response = repository.abortOrder(orderId)
                if(response.isSuccessful) {
                    if (_uiState.value.pendingOrders.any { it.id == orderId }) {
                        _uiState.update {
                            it.copy(pendingOrders = it.pendingOrders.filter { o -> o.id != orderId })
                        }
                    } else {
                        updateLocalOrderStatus(orderId, "REJECTED")
                    }
                    sendEvent(UiEvent.ShowToast("Order Aborted"))
                } else {
                    sendEvent(UiEvent.ShowToast("Failed to abort order"))
                }
            } catch (e: Exception) {
                sendEvent(UiEvent.ShowToast("Network error"))
            }
        }
    }

    // Updated function to handle different types of local updates
    private fun updateLocalOrderStatus(
        orderId: Long,
        newStatus: String,
        newTip: Double? = null,
        newPickupTimestamp: Long? = null
    ) {
        val index = allProcessedOrders.indexOfFirst { it.id == orderId }
        if (index != -1) {
            val originalOrder = allProcessedOrders[index]
            val updatedOrder = originalOrder.copy(
                status = newStatus,
                completionTimestamp = if (newStatus == "COMPLETED") System.currentTimeMillis() else originalOrder.completionTimestamp,
                tipAmount = newTip ?: originalOrder.tipAmount,
                pickupTimestamp = newPickupTimestamp ?: originalOrder.pickupTimestamp
            )
            allProcessedOrders[index] = updatedOrder

            if(newStatus.equals("COMPLETED", ignoreCase = true) && newTip != null) {
                _uiState.update {
                    it.copy(balanceSheet = it.balanceSheet.copy(tips = it.balanceSheet.tips + newTip))
                }
            }
            filterProcessedOrders()
        }
    }

    suspend fun updateProfile(name: String, vehicleModel: String, vehicleNumber: String, imageUri: Uri?): Boolean {
        return try {
            val riderId = SessionManager.getRiderId(getApplication())
            val success = repository.updateProfile(riderId, name, vehicleModel, vehicleNumber, imageUri, getApplication())
            if(success) {
                fetchProfile(riderId)
                sendEvent(UiEvent.ShowToast("Profile Updated!"))
            } else {
                sendEvent(UiEvent.ShowToast("Profile update failed"))
            }
            success
        } catch (e: Exception) {
            sendEvent(UiEvent.ShowToast("Network Error"))
            false
        }
    }

    fun logout() {
        SessionManager.clearSession(getApplication())
        _uiState.value = RiderUiState(isLoading = false)
    }

    private fun sendEvent(event: UiEvent) {
        viewModelScope.launch {
            _uiEvent.send(event)
        }
    }
}

class RiderViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RiderViewModel::class.java)) {
            val apiService = RetrofitClient.getInstance(application)
            val repository = RiderRepository(apiService)
            val authRepository = AuthRepository(apiService)
            @Suppress("UNCHECKED_CAST")
            return RiderViewModel(application, repository, authRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}


// ================================================================================
// 3. THEME
// ================================================================================

private val PrimaryRed = Color(0xFFE53935)
private val DarkGray = Color(0xFF424242)
private val SurfaceGrey = Color(0xFFF5F5F5)
private val LightGray = Color(0xFFBDBDBD)
private val GreenAccept = Color(0xFF4CAF50)
private val PurpleAccept = Color(0xFF673AB7)
private val OrangeAccepted = Color(0xFFFFA726)
private val BlueVerified = Color(0xFF2196F3) // New color for verified status

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
        typography = Typography(),
        shapes = Shapes(
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp)
        ),
        content = content
    )
}

// ================================================================================
// 4. NAVIGATION & SERVICE
// ================================================================================

object AppRoutes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val ORDERS = "orders"
    const val PROFILE = "profile"
    const val EDIT_PROFILE = "edit_profile"
}

class OrderSocketService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): OrderSocketService = this@OrderSocketService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectionJob: Job? = null
    private var riderId: Long = -1L

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var webSocket: WebSocket? = null
    private val gson = Gson()

    private val _orderNotifications = MutableStateFlow<Order?>(null)
    val orderNotifications = _orderNotifications.asStateFlow()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(RIDER_ID_EXTRA, -1L) ?: -1L
        if (id != -1L) {
            this.riderId = id
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
            startWebSocketConnection()
        }
        return START_STICKY
    }


    private fun startWebSocketConnection() {
        if (webSocket == null) {
            Log.d("RiderSocketService", "Attempting WebSocket connection...")
            connect()
        }
    }

    private fun connect() {
        // FIX: Added the riderId as a query parameter to the WebSocket URL.
        val request = Request.Builder()
            .url("ws://192.168.31.242:8080/ws/orders?riderId=$riderId")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                Log.i("RiderSocketService", "SUCCESS: WebSocket Connection Opened for Rider ID: $riderId")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.i("RiderSocketService", "NEW ORDER MESSAGE: $text")
                try {
                    val order = gson.fromJson(text, Order::class.java)
                    _orderNotifications.value = order
                } catch (e: Exception) {
                    Log.e("RiderSocketService", "Error parsing order JSON", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                Log.e("RiderSocketService", "Connection Failure: ${t.message}")
                this@OrderSocketService.webSocket = null
                reconnect()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("RiderSocketService", "WebSocket Closing: $reason")
                this@OrderSocketService.webSocket = null
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("RiderSocketService", "WebSocket Closed: $reason")
                this@OrderSocketService.webSocket = null
                reconnect()
            }
        })
    }

    private fun reconnect() {
        serviceScope.launch {
            Log.d("RiderSocketService", "Reconnecting in 5 seconds...")
            delay(5000)
            startWebSocketConnection()
        }
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Rider Notifications",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Honey Rider")
            .setContentText("Online and listening for new jobs.")
            .setSmallIcon(R.drawable.ic_baseline_two_wheeler_24)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        webSocket?.close(1000, "Service Destroyed")
    }

    companion object {
        const val RIDER_ID_EXTRA = "com.ss.honeyrider.RIDER_ID"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "RiderOrderServiceChannel"
    }
}


@Composable
fun MainScreen(riderViewModel: RiderViewModel) {
    val uiState by riderViewModel.uiState.collectAsState()
    val navController = rememberNavController()

    LaunchedEffect(key1 = uiState.profile, key2 = uiState.isLoading) {
        if (!uiState.isLoading) {
            val route = if (uiState.profile != null) AppRoutes.HOME else AppRoutes.LOGIN
            navController.navigate(route) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    if (uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        AppScaffold(navController = navController, riderViewModel = riderViewModel)
    }
}

@Composable
fun AppScaffold(navController: NavHostController, riderViewModel: RiderViewModel) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val bottomNavRoutes = setOf(AppRoutes.HOME, AppRoutes.ORDERS, AppRoutes.PROFILE)
    val shouldShowBottomBar = currentRoute in bottomNavRoutes

    Scaffold(
        bottomBar = {
            if (shouldShowBottomBar) {
                AppBottomNavigation(navController = navController)
            }
        },
        floatingActionButton = {
            if (currentRoute == AppRoutes.PROFILE) {
                FloatingActionButton(onClick = { navController.navigate(AppRoutes.EDIT_PROFILE) }) {
                    Icon(Icons.Default.Edit, "Edit Profile")
                }
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
            LoginScreen(navController = navController, viewModel = riderViewModel)
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

@Composable
fun LoginScreen(navController: NavController, viewModel: RiderViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                is UiEvent.ShowToast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                is UiEvent.NavigateToHome -> {
                    navController.navigate(AppRoutes.HOME) {
                        popUpTo(AppRoutes.LOGIN) { inclusive = true }
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            var username by remember { mutableStateOf("") }
            var password by remember { mutableStateOf("") }

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
                        viewModel.onLoginClicked(username, password)
                    } else {
                        Toast.makeText(context, "Please enter username and password", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) { Text("Login") }
        }

        if (uiState.isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
fun HomeScreen(navController: NavController, viewModel: RiderViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            if (event is UiEvent.ShowToast) {
                Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            uiState.profile?.let {
                HomeTopBar(
                    profile = it,
                    onStatusChangeClick = { viewModel.toggleAvailability() },
                    onProfileClick = { navController.navigate(AppRoutes.PROFILE) }
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (uiState.profile == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (!uiState.profile!!.isAvailable) {
                EmptyState(message = "You are currently offline. Go online to receive new order requests.")
            } else if (uiState.pendingOrders.isEmpty()) {
                EmptyState(message = "No new orders right now. We'll notify you!")
            } else {
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(uiState.pendingOrders) { order ->
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
}


// --- CHANGE #6: MAJOR REFACTOR of OrdersScreen and its components ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersScreen(viewModel: RiderViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var showFilterMenu by remember { mutableStateOf(false) }
    var orderForConfirmation by remember { mutableStateOf<Order?>(null) }
    var confirmationAction by remember { mutableStateOf("") }
    var orderForCollection by remember { mutableStateOf<Order?>(null) }
    var orderForOtp by remember { mutableStateOf<Order?>(null) }

    // Confirmation for Abort/End
    if (orderForConfirmation != null) {
        ConfirmationDialog(
            action = confirmationAction,
            onConfirm = {
                orderForConfirmation?.let { order ->
                    if (confirmationAction == "Abort") viewModel.abortOrder(order.id)
                    else if (confirmationAction == "End") orderForCollection = order
                }
                orderForConfirmation = null
            },
            onDismiss = { orderForConfirmation = null }
        )
    }

    // Dialog for collecting cash
    if (orderForCollection != null) {
        AmountCollectionDialog(
            order = orderForCollection!!,
            onDismiss = { orderForCollection = null },
            onSubmit = { collectedAmount ->
                val order = orderForCollection!!
                val change = collectedAmount - order.cashToCollect
                val tip = if (change > 0) change else 0.0
                viewModel.completeOrder(order.id, tip)
                orderForCollection = null
            }
        )
    }

    // New Dialog for OTP Verification
    if (orderForOtp != null) {
        OtpVerificationDialog(
            order = orderForOtp!!,
            onDismiss = { orderForOtp = null },
            onSubmit = { otp ->
                viewModel.verifyOtp(orderForOtp!!.id, otp)
                orderForOtp = null
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Order History & Tips") },
                actions = {
                    Box {
                        IconButton(onClick = { showFilterMenu = true }) { Icon(Icons.Default.FilterList, contentDescription = "Filter Orders") }
                        DropdownMenu(expanded = showFilterMenu, onDismissRequest = { showFilterMenu = false }) {
                            DropdownMenuItem(text = { Text("All") }, onClick = { viewModel.setProcessedOrderFilter(ProcessedOrderFilter.ALL); showFilterMenu = false })
                            DropdownMenuItem(text = { Text("Accepted") }, onClick = { viewModel.setProcessedOrderFilter(ProcessedOrderFilter.ACCEPTED); showFilterMenu = false })
                            DropdownMenuItem(text = { Text("Completed") }, onClick = { viewModel.setProcessedOrderFilter(ProcessedOrderFilter.COMPLETED); showFilterMenu = false })
                            DropdownMenuItem(text = { Text("Aborted") }, onClick = { viewModel.setProcessedOrderFilter(ProcessedOrderFilter.REJECTED); showFilterMenu = false })
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { BalanceSheetCard(balance = uiState.balanceSheet) }
            item {
                val filterText = when (uiState.processedOrderFilter) {
                    ProcessedOrderFilter.ALL -> "All"
                    ProcessedOrderFilter.REJECTED -> "Aborted"
                    else -> uiState.processedOrderFilter.name.lowercase().replaceFirstChar { it.titlecase() }
                }
                Text("Orders ($filterText)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            }

            if (uiState.processedOrders.isEmpty()) {
                item { EmptyState(message = "No orders found for this filter.") }
            } else {
                items(uiState.processedOrders) { order ->
                    ProcessedOrderCard(
                        order = order,
                        onVerifyPickup = { orderForOtp = order },
                        onEndOrder = {
                            confirmationAction = "End"
                            orderForConfirmation = order
                        },
                        onAbortOrder = {
                            confirmationAction = "Abort"
                            orderForConfirmation = order
                        }
                    )
                }
            }
        }
    }
}

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
fun AmountCollectionDialog(order: Order, onDismiss: () -> Unit, onSubmit: (Double) -> Unit) {
    var amountText by remember { mutableStateOf("") }

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
                    Text(
                        "Note: Extra amount of ₹%.2f will be added as a tip.".format(change),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(collectedAmount) },
                enabled = isAmountSufficient
            ) { Text("Submit") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// New Dialog for OTP
@Composable
fun OtpVerificationDialog(order: Order, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var otpText by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Verify Pickup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter the 4-digit OTP from the vendor to confirm you have picked up the order.")
                OutlinedTextField(
                    value = otpText,
                    onValueChange = { if (it.length <= 4) otpText = it.filter { char -> char.isDigit() } },
                    label = { Text("Enter OTP") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(otpText) },
                enabled = otpText.length == 4
            ) { Text("Verify & Start Delivery") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun BalanceSheetCard(balance: BalanceSheet) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(4.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your Tips", style = MaterialTheme.typography.headlineSmall)
            Divider()
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
fun ProcessedOrderCard(
    order: Order,
    onVerifyPickup: () -> Unit,
    onEndOrder: () -> Unit,
    onAbortOrder: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    val rotationAngle by animateFloatAsState(targetValue = if (isExpanded) 180f else 0f, label = "expansion_arrow")

    val statusEnum = try {
        OrderStatus.valueOf(order.status.uppercase())
    } catch (e: IllegalArgumentException) {
        OrderStatus.PENDING
    }

    // This condition is now updated to show the OTP for both statuses
    val shouldShowOtp = (statusEnum == OrderStatus.ACCEPTED_BY_RIDER || statusEnum == OrderStatus.READY) && !order.otp.isNullOrBlank()

    val cardColors = when (statusEnum) {
        OrderStatus.COMPLETED -> CardDefaults.cardColors(containerColor = GreenAccept, contentColor = Color.White)
        OrderStatus.ACCEPTED_BY_RIDER -> CardDefaults.cardColors(containerColor = OrangeAccepted, contentColor = DarkGray)
        OrderStatus.OUT_FOR_DELIVERY -> CardDefaults.cardColors(containerColor = BlueVerified, contentColor = Color.White)
        OrderStatus.REJECTED -> CardDefaults.cardColors(containerColor = PrimaryRed, contentColor = Color.White)
        else -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { isExpanded = !isExpanded },
        colors = cardColors
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(order.vendorName, fontWeight = FontWeight.Bold)
                    Text("Order #${order.id}", style = MaterialTheme.typography.bodySmall)
                }
                if (statusEnum == OrderStatus.OUT_FOR_DELIVERY) {
                    OrderTimer(acceptedTimestamp = order.pickupTimestamp, timeLimitMinutes = order.timeLimitMinutes)
                } else if (order.completionTimestamp != null && order.pickupTimestamp != null) {
                    val timeTaken = order.completionTimestamp - order.pickupTimestamp
                    val minutes = TimeUnit.MILLISECONDS.toMinutes(timeTaken)
                    val seconds = TimeUnit.MILLISECONDS.toSeconds(timeTaken) % 60
                    Text(text = "Took ${minutes}m ${seconds}s", style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.8f), modifier = Modifier.padding(horizontal = 8.dp))
                }
                OrderStatusChip(status = statusEnum)
                Icon(imageVector = Icons.Default.KeyboardArrowDown, contentDescription = "Expand", modifier = Modifier.rotate(rotationAngle))
            }
            if (shouldShowOtp) {
                Spacer(Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Handover OTP",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = order.otp!!,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryRed
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }


            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    Divider(color = LocalContentColor.current.copy(alpha = 0.3f))
                    Spacer(Modifier.height(12.dp))
                    OrderDetailRow(icon = Icons.Default.Store, label = "Pickup", value = order.pickupAddress)
                    OrderDetailRow(icon = Icons.Default.Home, label = "Delivery", value = order.deliveryAddress)
                    OrderDetailRow(icon = Icons.Default.ShoppingBag, label = "Items", value = "${order.itemCount} Items")

                    Divider(modifier = Modifier.padding(vertical = 12.dp), color = LocalContentColor.current.copy(alpha = 0.3f))
                    OrderDetailRow(icon = Icons.Default.Receipt, label = "Order Total", value = "₹%.2f".format(order.totalAmount))
                    OrderDetailRow(icon = Icons.Default.TwoWheeler, label = "Delivery Charge", value = "₹%.2f".format(order.deliveryCharge))
                    OrderDetailRow(icon = Icons.Default.FlashOn, label = "Surge Charge", value = "₹%.2f".format(order.surgeCharge))
                    OrderDetailRow(icon = Icons.Default.Payments, label = "Amount to Collect", value = "₹%.2f".format(order.cashToCollect), isHighlight = true)

                    if (order.tipAmount > 0) {
                        Divider(modifier = Modifier.padding(vertical = 12.dp), color = LocalContentColor.current.copy(alpha = 0.3f))
                        OrderDetailRow(icon = Icons.Default.Favorite, label = "Tip Received", value = "₹%.2f".format(order.tipAmount))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (statusEnum != OrderStatus.COMPLETED && statusEnum != OrderStatus.REJECTED) {
                    Button(onClick = onAbortOrder, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer)) { Text("Abort Order") }
                }

                if (statusEnum == OrderStatus.OUT_FOR_DELIVERY) {
                    Button(onClick = onEndOrder, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = GreenAccept)) { Text("Complete Order") }
                }
            }
        }
    }
}

@Composable
fun OtpDisplayDialog(otp: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Handover OTP") },
        text = {
            Text(
                text = otp,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
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
    val color = if (isOvertime) PrimaryRed else if (LocalContentColor.current == Color.White) Color.White else GreenAccept

    Text(text = timeString, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp))
}


@Composable
fun OrderDetailRow(icon: ImageVector, label: String, value: String, isHighlight: Boolean = false) {
    val contentColor = LocalContentColor.current
    Row(modifier = Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(20.dp), tint = if (isHighlight) PrimaryRed else contentColor.copy(alpha = 0.7f))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, color = contentColor.copy(alpha = 0.7f))
            Text(text = value, style = MaterialTheme.typography.bodyLarge, fontWeight = if (isHighlight) FontWeight.Bold else FontWeight.Normal, color = if (isHighlight) PrimaryRed else contentColor)
        }
    }
}


@Composable
fun OrderStatusChip(status: OrderStatus) {
    val (text, bgColor) = when (status) {
        OrderStatus.ACCEPTED_BY_RIDER -> "Accepted" to OrangeAccepted
        OrderStatus.REJECTED -> "Aborted" to PrimaryRed
        OrderStatus.COMPLETED -> "Completed" to DarkGray
        OrderStatus.OUT_FOR_DELIVERY -> "In Transit" to BlueVerified
        // FIX: Add this case to handle the READY status
        OrderStatus.READY -> "Ready" to BlueVerified
        else -> status.name to LightGray
    }
    Text(
        text = text,
        color = if (bgColor == OrangeAccepted) DarkGray else Color.White,
        modifier = Modifier
            .background(bgColor, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium
    )
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
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = profile.name,
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
        Row(
            modifier = Modifier.padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AvailabilityIndicator(isAvailable = profile.isAvailable, onClick = onStatusChangeClick)
            val placeholderPainter = rememberVectorPainter(image = Icons.Default.AccountCircle)
            AsyncImage(model = profile.imageUrl, contentDescription = "Rider Profile", modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(onClick = onProfileClick), contentScale = ContentScale.Crop, placeholder = placeholderPainter, error = placeholderPainter)
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
            Text(order.vendorName, style = MaterialTheme.typography.titleLarge)
            Text(order.pickupAddress, style = MaterialTheme.typography.bodyMedium)
            Divider(modifier = Modifier.padding(vertical = 12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                InfoColumn("Pickup", order.pickupAddress)
                InfoColumn("Drop", order.deliveryAddress, alignEnd = true)
            }
            Divider(modifier = Modifier.padding(vertical = 12.dp))
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = onDeny,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryRed)
                ) { Text("Deny") }
                Button(
                    onClick = onAccept,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = PurpleAccept)
                ) { Text("Accept") }
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
    Box(modifier = Modifier
        .fillMaxSize()
        .padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text = message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(navController: NavController, viewModel: RiderViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Profile") },
            )
        }
    ) { padding ->
        uiState.profile?.let { profile ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val placeholderPainter = rememberVectorPainter(image = Icons.Default.AccountCircle)
                AsyncImage(model = profile.imageUrl, contentDescription = "Profile Picture", modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape), contentScale = ContentScale.Crop, placeholder = placeholderPainter, error = placeholderPainter)
                Text(profile.name, style = MaterialTheme.typography.headlineSmall)
                Text("@${profile.username}", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
                Divider()
                ProfileInfoRow(icon = Icons.Default.TwoWheeler, label = "Vehicle Model", value = profile.vehicleModel)
                ProfileInfoRow(icon = Icons.Default.ConfirmationNumber, label = "Vehicle Number", value = profile.vehicleNumber)
                Spacer(Modifier.weight(1f))
                Button(onClick = {
                    viewModel.logout()
                    navController.navigate(AppRoutes.LOGIN) { popUpTo(0) { inclusive = true } }
                }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Logout") }
            }
        } ?: Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(navController: NavController, viewModel: RiderViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    var name by remember(uiState.profile) { mutableStateOf(uiState.profile?.name ?: "") }
    var vehicleModel by remember(uiState.profile) { mutableStateOf(uiState.profile?.vehicleModel ?: "") }
    var vehicleNumber by remember(uiState.profile) { mutableStateOf(uiState.profile?.vehicleNumber ?: "") }
    var imageUri by remember { mutableStateOf<Uri?>(null) }

    val imagePickerLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { uri: Uri? -> imageUri = uri }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Profile") },
                navigationIcon = { IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { scope.launch { if (viewModel.updateProfile(name, vehicleModel, vehicleNumber, imageUri)) navController.popBackStack() } }) { Icon(Icons.Default.Save, "Save") } },
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier
            .padding(padding)
            .fillMaxSize(), contentPadding = PaddingValues(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Box(modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { imagePickerLauncher.launch("image/*") }, contentAlignment = Alignment.Center) {
                    val placeholderPainter = rememberVectorPainter(image = Icons.Default.AccountCircle)
                    AsyncImage(model = imageUri ?: uiState.profile?.imageUrl, contentDescription = "Profile Image", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, placeholder = placeholderPainter, error = placeholderPainter)
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
fun ProfileInfoRow(icon: ImageVector, label: String, value: String) {
    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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

    private var orderSocketService: OrderSocketService? = null
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as OrderSocketService.LocalBinder
            orderSocketService = binder.getService()
            isServiceBound = true

            val riderViewModel: RiderViewModel by viewModels { RiderViewModelFactory(application) }

            lifecycleScope.launch {
                orderSocketService?.orderNotifications?.collect { order ->
                    if (order != null) {
                        riderViewModel.addNewOrderFromSocket(order)
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HoneyRiderTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val riderViewModel: RiderViewModel = viewModel(factory = RiderViewModelFactory(application))

                    val uiState by riderViewModel.uiState.collectAsState()

                    LaunchedEffect(uiState.profile) {
                        if (uiState.profile != null && uiState.profile!!.isAvailable) {
                            val riderId = SessionManager.getRiderId(applicationContext)
                            if (riderId != -1L && !isServiceBound) {
                                val serviceIntent = Intent(applicationContext, OrderSocketService::class.java).apply {
                                    putExtra(OrderSocketService.RIDER_ID_EXTRA, riderId)
                                }
                                startService(serviceIntent)
                                bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
                            }
                        } else {
                            if (isServiceBound) {
                                unbindService(serviceConnection)
                                isServiceBound = false
                            }
                            stopService(Intent(applicationContext, OrderSocketService::class.java))
                        }
                    }

                    MainScreen(riderViewModel = riderViewModel)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
}