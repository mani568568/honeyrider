package com.ss.honeyrider

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.ss.honeyrider.ui.theme.HoneyRiderTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query


// --- Data Classes ---
data class RiderDeliveryDetails(
    val orderId: Long,
    val status: String,
    val vendorName: String,
    val vendorAddress: String,
    val vendorLatitude: Double,
    val vendorLongitude: Double,
    val customerAddress: String,
    val customerLatitude: Double,
    val customerLongitude: Double,
    val pickupOtp: String?
)

// --- ApiService Interface ---
interface ApiService {
    @GET("api/orders/{orderId}/delivery-details")
    suspend fun getDeliveryDetails(@Path("orderId") orderId: Long): RiderDeliveryDetails

    @POST("api/orders/{orderId}/confirm-pickup")
    suspend fun confirmPickup(@Path("orderId") orderId: Long, @Query("otp") otp: String)

    // Add other necessary endpoints here
}

// --- ViewModel ---
class DeliveryViewModel(private val apiService: ApiService) : ViewModel() {

    private val _deliveryDetails = MutableStateFlow<RiderDeliveryDetails?>(null)
    val deliveryDetails: StateFlow<RiderDeliveryDetails?> = _deliveryDetails.asStateFlow()

    private val _isPickupConfirmed = MutableStateFlow(false)
    val isPickupConfirmed: StateFlow<Boolean> = _isPickupConfirmed.asStateFlow()

    fun loadDeliveryDetails(orderId: Long) {
        viewModelScope.launch {
            try {
                val details = apiService.getDeliveryDetails(orderId)
                _deliveryDetails.value = details
                if (details.status == "OUT_FOR_DELIVERY" || details.status == "DELIVERED") {
                    _isPickupConfirmed.value = true
                }
            } catch (e: Exception) {
                // Handle exceptions
            }
        }
    }

    fun confirmPickup(orderId: Long, otp: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                apiService.confirmPickup(orderId, otp)
                _isPickupConfirmed.value = true
                onResult(true)
            } catch (e: Exception) {
                onResult(false)
            }
        }
    }
}

// --- ViewModel Factory ---
class DeliveryViewModelFactory(private val apiService: ApiService) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DeliveryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DeliveryViewModel(apiService) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dummy ApiService instance for demonstration.
        // In a real app, this would be provided by a dependency injection framework like Hilt.
        val apiService = object : ApiService {
            // Mock implementations for testing UI
            override suspend fun getDeliveryDetails(orderId: Long): RiderDeliveryDetails {
                return RiderDeliveryDetails(
                    orderId = 101, status = "RIDER_ASSIGNED",
                    vendorName = "Gourmet Kitchen", vendorAddress = "123 Food Street, Bhimavaram",
                    vendorLatitude = 16.5449, vendorLongitude = 81.5212,
                    customerAddress = "456 Home Avenue, Bhimavaram",
                    customerLatitude = 16.533, customerLongitude = 81.522,
                    pickupOtp = "1234"
                )
            }
            override suspend fun confirmPickup(orderId: Long, otp: String) {
                // No-op for mock
            }
        }

        setContent {
            HoneyRiderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Pass a sample orderId for demonstration
                    ActiveDeliveryScreen(
                        orderId = 101L,
                        viewModel = viewModel(factory = DeliveryViewModelFactory(apiService))
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveDeliveryScreen(orderId: Long, viewModel: DeliveryViewModel) {
    val deliveryDetails by viewModel.deliveryDetails.collectAsStateWithLifecycle()
    val isPickupConfirmed by viewModel.isPickupConfirmed.collectAsStateWithLifecycle()

    LaunchedEffect(orderId) {
        viewModel.loadDeliveryDetails(orderId)
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Active Delivery") })
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (deliveryDetails == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                AnimatedContent(targetState = isPickupConfirmed, label = "DeliveryStepAnimation") { confirmed ->
                    if (!confirmed) {
                        PickupStep(details = deliveryDetails!!, viewModel = viewModel)
                    } else {
                        DeliveryStep(details = deliveryDetails!!)
                    }
                }
            }
        }
    }
}

@Composable
fun PickupStep(details: RiderDeliveryDetails, viewModel: DeliveryViewModel) {
    val context = LocalContext.current
    var enteredOtp by remember { mutableStateOf("") }

    val vendorLocation = LatLng(details.vendorLatitude, details.vendorLongitude)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(vendorLocation, 15f)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxWidth().weight(1f),
            cameraPositionState = cameraPositionState
        ) {
            Marker(state = MarkerState(position = vendorLocation), title = "Pickup Location", snippet = details.vendorName)
        }

        Column(modifier = Modifier.padding(16.dp)) {
            Text("Head to Vendor", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(details.vendorName, style = MaterialTheme.typography.titleLarge)
            Text(details.vendorAddress, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Text("Confirmation OTP", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                details.pickupOtp ?: "N/A",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 8.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth()
            )
            OutlinedTextField(
                value = enteredOtp,
                onValueChange = { if (it.length <= 4) enteredOtp = it },
                label = { Text("Enter OTP from Vendor") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    viewModel.confirmPickup(details.orderId, enteredOtp) { success ->
                        if (!success) {
                            Toast.makeText(context, "Invalid OTP. Please try again.", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.padding(top = 16.dp).fillMaxWidth().height(50.dp),
                enabled = enteredOtp.length == 4
            ) {
                Text("Confirm Pickup")
            }
        }
    }
}

@Composable
fun DeliveryStep(details: RiderDeliveryDetails) {
    val vendorLocation = LatLng(details.vendorLatitude, details.vendorLongitude)
    val customerLocation = LatLng(details.customerLatitude, details.customerLongitude)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(customerLocation, 15f)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxWidth().weight(1f),
            cameraPositionState = cameraPositionState
        ) {
            Marker(state = MarkerState(position = vendorLocation), title = "Pickup Location")
            Marker(state = MarkerState(position = customerLocation), title = "Delivery Destination")
        }

        Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Deliver To", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(details.customerAddress, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { /* TODO: Call viewModel.markAsDelivered() */ },
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    Text("Mark as Delivered")
                }
            }
        }
    }
}