// MainActivity.kt
package com.cryptonite.lora

import android.hardware.usb.UsbManager
import android.os.Bundle
import android.preference.PreferenceManager.OnActivityDestroyListener
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue

class MainActivity : AppCompatActivity() {
    private lateinit var textViewSerialData: TextView
    private lateinit var textViewVerbose: TextView  // Verbose log text view
    private lateinit var buttonTest: Button         // Test button
    private lateinit var buttonSend: Button         // New button to send packet
    private var serialPort: UsbSerialPort? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    // Message handling components
    private val messageBuffer = StringBuilder()
    private val processedMessages = ConcurrentLinkedQueue<BridgeProtocol>()
    //private var lastMessage: String = "Waiting for USB Serial Data"
    private var counter = 0
    // Protocol Constants
    companion object {
        private const val TAG = "UsbSerialProtocolReader"
        private const val BAUD_RATE = 9600
        private const val READ_BUFFER_SIZE = 1024
        private const val READ_TIMEOUT = 1000

        // Protocol Framing Constants
        private const val HEADER_BYTE = 0xEA.toByte()  // Start of message marker
        private const val TYPE_MANAGEMENT = 0x01.toByte()
        private const val TYPE_DATA = 0x02.toByte()
        private const val FOOTER_BYTE = 0x55.toByte()  // End of message marker
    }

    // Protocol Message Class
    data class BridgeProtocol(
        val type: Byte,
        val length: Byte,
        val payload: ByteArray,
        val hexPayload: String,
        val receivedChecksum: Byte,
        val calculatedChecksum: Byte
    ) {
        val isChecksumValid: Boolean = receivedChecksum == calculatedChecksum

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as BridgeProtocol

            if (type != other.type) return false
            if (length != other.length) return false
            if (!payload.contentEquals(other.payload)) return false
            if (receivedChecksum != other.receivedChecksum) return false

            return true
        }

        override fun hashCode(): Int {
            var result = type.toInt()
            result = 31 * result + length
            result = 31 * result + payload.contentHashCode()
            result = 31 * result + receivedChecksum
            return result
        }
    }

    // ESP32-style Checksum Calculation
    private fun calculateChecksum(type: Byte, length: Byte, payload: ByteArray): Byte {
        var checksum = type.toInt() xor length.toInt()
        for (byte in payload) {
            checksum = checksum xor byte.toInt()
        }
        return checksum.toByte()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textViewSerialData = findViewById(R.id.textViewSerialData)
        textViewVerbose = findViewById(R.id.textViewVerbose)
        buttonTest = findViewById(R.id.buttonTest)
        buttonSend = findViewById(R.id.buttonSend) // Initialize new send button

        // Set test button click listener to simulate receiving a test message
        buttonTest.setOnClickListener {
            // Test message: "AA0204456467652555"
            val testMessage = "EA02094C4544206973206F6E5D55"
            appendVerboseLog("Test button pressed. Simulating message: $testMessage")
            processIncomingBytes(testMessage)
        }

        // Set send button click listener to send packet with data = "on"
        buttonSend.setOnClickListener {
            sendPacketToEsp32()
        }

        // Start message processing coroutine
        startMessageProcessing()

        // Attempt to connect to USB serial device
        connectToUsbSerial()
    }

    // Helper function to append verbose log messages to the UI
    private fun appendVerboseLog(message: String) {
        runOnUiThread {
            textViewVerbose.append("$message\n")
        }
    }

    private fun startMessageProcessing() {
        coroutineScope.launch {
            while (isActive) {
                // Process complete messages
                val message = processedMessages.poll()
                if (message != null) {
                    withContext(Dispatchers.Main) {
                        updateUI(formatMessageForDisplay(message))
                    }
                    appendVerboseLog("Processed message: ${formatMessageForDisplay(message)}")
                }
                kotlinx.coroutines.delay(50) // Prevent tight loop
            }
        }
    }

    private fun hexToAscii(hex: String): String {
        val output = StringBuilder()
        // Process every two hex characters as one byte
        for (i in hex.indices step 2) {
            val str = hex.substring(i, i + 2)
            val charCode = str.toInt(16)
            output.append(charCode.toChar())
        }
        return output.toString()
    }

    private fun formatMessageForDisplay(message: BridgeProtocol): String {
        val asciiPayload = hexToAscii(message.hexPayload)
        return buildString {
            append(
                when (message.type) {
                    TYPE_MANAGEMENT -> "MGMT: "
                    TYPE_DATA -> "DATA: "
                    else -> "UNKNOWN: "
                }
            )
            append("${message.hexPayload} ")
            append("(ASCII: $asciiPayload, ")
            append("Len: ${message.length}, ")
            append("Checksum: ${String.format("%02X", message.receivedChecksum)}, ")
            append("Valid: ${message.isChecksumValid})")
        }
    }

    private fun connectToUsbSerial() {
        counter = 0;
        val manager = getSystemService(USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)

        appendVerboseLog("Available USB drivers: ${availableDrivers.size}")
        Log.d(TAG, "Available USB drivers: ${availableDrivers.size}")

        if (availableDrivers.isEmpty()) {
            updateUI("No USB serial devices found")
            appendVerboseLog("No USB serial devices found")
            return
        }

        // Get the first available driver
        val driver = availableDrivers[0]
        val connection = manager.openDevice(driver.device)

        if (connection == null) {
            updateUI("Could not open USB device")
            appendVerboseLog("Could not open USB device")
            Log.e(TAG, "USB device connection failed")
            return
        }

        try {
            serialPort = driver.ports[0] // Most drivers have just one port
            serialPort?.let { port ->
                port.open(connection)
                port.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

                appendVerboseLog("Serial port opened successfully. Baud rate: $BAUD_RATE")
                Log.d(TAG, "Serial port opened successfully. Baud rate: $BAUD_RATE")

                // Start reading data
                startReadingSerialData()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening serial port", e)
            updateUI("Error: ${e.message}")
            appendVerboseLog("Error opening serial port: ${e.message}")
        }
    }

    private fun startReadingSerialData() {
        coroutineScope.launch {
            val buffer = ByteArray(READ_BUFFER_SIZE)
            while (isActive) {
                try {
                    val bytesRead = serialPort?.read(buffer, READ_TIMEOUT) ?: 0

                    if (bytesRead > 0) {
                        // Convert bytes to string assuming device sends ASCII hex data
                        val hexChunk = buffer.copyOfRange(0, bytesRead).joinToString(separator = "") { String.format("%02X", it) }
                        appendVerboseLog("Received Hex Chunk: $hexChunk")
                        Log.d(TAG, "Received Hex Chunk: $hexChunk")

                        processIncomingBytes(hexChunk)
                    }
                } catch (e: Exception) {
                    if(counter<3) {
                        Log.e(TAG, "Error reading serial data", e)
                        withContext(Dispatchers.Main) {
                            updateUI("Error reading data: ${e.message}")
                        }
                        appendVerboseLog("Error reading serial data: ${e.message}")
                    }
                    counter++
                    //onDestroy()
                }
            }
        }
    }

    private fun processIncomingBytes(hexChunk: String) {
        messageBuffer.append(hexChunk)

        while (true) {
            // Look for a complete message with header and footer markers
            val startIndex = messageBuffer.indexOf(String.format("%02X", HEADER_BYTE))
            val footerString = String.format("%02X", FOOTER_BYTE)
            val endIndex = messageBuffer.toString().lastIndexOf(footerString)
            if (startIndex == -1 || endIndex == -1 || endIndex <= startIndex) break

            // Extract the potential message including the footer (2 hex digits)
            val potentialMessage = messageBuffer.substring(startIndex, endIndex + 2)
            appendVerboseLog("Potential message found: $potentialMessage")

            // Remove processed portion
            messageBuffer.delete(0, endIndex + 2)

            // Parse and validate the message
            val parsedMessage = parseMessage(potentialMessage)
            if (parsedMessage != null) {
                processedMessages.offer(parsedMessage)
                appendVerboseLog("Message parsed and queued: ${parsedMessage.hexPayload}")
            } else {
                appendVerboseLog("Failed to parse message: $potentialMessage")
            }
        }
    }

    private fun parseMessage(hexMessage: String): BridgeProtocol? {
        try {
            // Remove header and footer markers
            val cleanMessage = hexMessage.replace(String.format("%02X", HEADER_BYTE), "")
                .replace(String.format("%02X", FOOTER_BYTE), "")

            // Ensure minimum length (type + length + payload + checksum)
            if (cleanMessage.length < 6) return null

            // Extract message components
            val messageType = cleanMessage.substring(0, 2).toInt(16).toByte()
            val messageLength = cleanMessage.substring(2, 4).toInt(16).toByte()

            // Calculate expected payload length (in hex digits)
            val expectedPayloadLength = messageLength.toInt() * 2

            // Validate message length
            val payloadEndIndex = 4 + expectedPayloadLength
            val checksumIndex = payloadEndIndex

            if (cleanMessage.length < checksumIndex + 2) return null

            // Extract payload
            val payloadHex = cleanMessage.substring(4, payloadEndIndex)
            val payloadBytes = payloadHex.chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()

            // Extract received checksum
            val receivedChecksum = cleanMessage.substring(checksumIndex, checksumIndex + 2).toInt(16).toByte()

            // Calculate checksum
            val calculatedChecksum = calculateChecksum(messageType, messageLength, payloadBytes)

            return BridgeProtocol(
                type = messageType,
                length = messageLength,
                payload = payloadBytes,
                hexPayload = payloadHex,
                receivedChecksum = receivedChecksum,
                calculatedChecksum = calculatedChecksum
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message: $hexMessage", e)
            appendVerboseLog("Error parsing message: $hexMessage, Exception: ${e.message}")
            return null
        }
    }

    // New function to send a packet with data = "on" to the ESP32
    private fun sendPacketToEsp32() {
        // Construct the packet:
        // HEADER (AA), TYPE (we use management: 01), LENGTH (payload length), PAYLOAD ("on"),
        // CHECKSUM (XOR of type, length, and payload bytes), FOOTER (55)
        val payload = "on".toByteArray()
        val type: Byte = TYPE_MANAGEMENT
        val length: Byte = payload.size.toByte()
        val checksum = calculateChecksum(type, length, payload)
        // Build the raw packet as a byte array.
        val packetBytes = byteArrayOf(HEADER_BYTE, type, length) +
                payload +
                byteArrayOf(checksum, FOOTER_BYTE)

        coroutineScope.launch {
            try {
                // Write the raw packet directly to the serial port.
                serialPort?.write(packetBytes, READ_TIMEOUT)
                // Log the packet in hex format for debugging purposes.
                appendVerboseLog("Packet sent to ESP32: ${packetBytes.joinToString(" ") { String.format("%02X", it) }}")
            } catch (e: Exception) {
                appendVerboseLog("Error sending packet: ${e.message}")
                Log.e(TAG, "Error sending packet", e)
            }
        }
    }



    private fun updateUI(message: String) {
        runOnUiThread {
            textViewSerialData.text = message
            appendVerboseLog("UI Updated: $message")
            Log.d(TAG, "UI Updated: $message")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Close the serial port and cancel coroutines
        try {
            serialPort?.close()
            appendVerboseLog("Serial port closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing serial port", e)
            appendVerboseLog("Error closing serial port: ${e.message}")
        }
        coroutineScope.cancel()
    }
}
