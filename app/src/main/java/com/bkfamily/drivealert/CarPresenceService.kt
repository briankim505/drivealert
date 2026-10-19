package com.bkfamily.drivealert

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.os.Build
import androidx.annotation.RequiresApi

/** 안드로이드가 등록된 차량(테슬라)의 연결을 알려주면 주행 모드를 켠다 */
@RequiresApi(31)
class CarPresenceService : CompanionDeviceService() {

    @Deprecated("API 33 미만 호환")
    override fun onDeviceAppeared(address: String) {
        DriveControl.onCarConnected(this, address)
    }

    @Deprecated("API 33 미만 호환")
    override fun onDeviceDisappeared(address: String) {
        DriveControl.onCarDisconnected(this, address)
    }

    @RequiresApi(33)
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        DriveControl.onCarConnected(this, associationInfo.deviceMacAddress?.toString())
    }

    @RequiresApi(33)
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        DriveControl.onCarDisconnected(this, associationInfo.deviceMacAddress?.toString())
    }

    companion object {
        val supported get() = Build.VERSION.SDK_INT >= 31
    }
}
