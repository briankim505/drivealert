package com.bkfamily.drivealert

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** 블루투스 연결/해제 신호를 받아 주행 모드를 켜고 끈다 */
class BtReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        val address = device?.address ?: return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> DriveControl.onCarConnected(context, address)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> DriveControl.onCarDisconnected(context, address)
        }
    }
}
