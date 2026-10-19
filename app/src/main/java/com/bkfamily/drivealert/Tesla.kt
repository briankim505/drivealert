package com.bkfamily.drivealert

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** 테슬라 앱의 '공유' 기능으로 차량 내비에 목적지를 보낸다 */
object Tesla {
    const val PACKAGE = "com.teslamotors.tesla"

    fun shareIntent(place: Place): Intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, place.shareText)
        .setPackage(PACKAGE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installed(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PACKAGE, 0); true
    } catch (_: Exception) { false }

    fun send(ctx: Context, place: Place) {
        if (!installed(ctx)) {
            Toast.makeText(ctx, "테슬라 앱이 설치되어 있지 않습니다", Toast.LENGTH_LONG).show()
            return
        }
        try {
            ctx.startActivity(shareIntent(place))
            Toast.makeText(ctx, "테슬라로 보냈습니다: ${place.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, "테슬라 앱이 공유를 받지 않았습니다 (${e.javaClass.simpleName})", Toast.LENGTH_LONG).show()
        }
    }

    /** 알림을 누르면 바로 테슬라로 보내는 PendingIntent */
    fun pending(ctx: Context, place: Place, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            ctx, requestCode, shareIntent(place),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
}
