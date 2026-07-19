package com.zhenshi.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import com.zhenshi.capture.media.usb.UsbHostCoordinator
import com.zhenshi.capture.navigation.AppNavigationRequests
import com.zhenshi.capture.screens.AppNav
import com.zhenshi.capture.screens.theme.ZhenShiTheme
import com.zhenshi.capture.util.enableDarkEdgeToEdge
import com.zhenshi.capture.util.isUvcCandidate
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var navigationRequests: AppNavigationRequests

    @Inject
    lateinit var usbHostCoordinator: UsbHostCoordinator

    private var usbBroadcastReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableDarkEdgeToEdge()
        super.onCreate(savedInstanceState)
        registerUsbTopologyReceiver()
        setContent {
            ZhenShiTheme {
                AppNav(navigationRequests = navigationRequests)
            }
        }
        // 非 USB 启动时首帧后再枚举设备
        val launchedFromUsb = intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED
        if (launchedFromUsb) {
            handleUsbIntent(intent)
        } else {
            window.decorView.post {
                usbHostCoordinator.reconcileDevices()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        usbHostCoordinator.onTopologyChanged()
    }

    override fun onDestroy() {
        usbBroadcastReceiver?.let { unregisterReceiver(it) }
        usbBroadcastReceiver = null
        super.onDestroy()
    }

    private fun handleUsbIntent(intent: Intent?) {
        when (intent?.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED ->
                handleUsbAttached(intent.readUsbDevice())
            UsbManager.ACTION_USB_DEVICE_DETACHED ->
                usbHostCoordinator.onTopologyChanged()
        }
    }

    private fun handleUsbAttached(device: UsbDevice?) {
        if (device != null && device.isUvcCandidate()) {
            usbHostCoordinator.onExternalAttach()
        } else {
            usbHostCoordinator.onTopologyChanged()
        }
    }

    private fun registerUsbTopologyReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED ->
                        handleUsbAttached(intent.readUsbDevice())
                    UsbManager.ACTION_USB_DEVICE_DETACHED ->
                        usbHostCoordinator.onTopologyChanged()
                }
            }
        }
        usbBroadcastReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            this,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun Intent.readUsbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
}
