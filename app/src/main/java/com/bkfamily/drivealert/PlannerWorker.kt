package com.bkfamily.drivealert

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** 15분마다 캘린더를 보고, 곧 시작하는 일정이 있으면 '테슬라로 보내기' 알림 */
class PlannerWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val p = Prefs(applicationContext)
        if (p.notifyBefore) {
            // 시작 1시간 이내 일정, 일정마다 한 번만
            Schedule.notifyNext(applicationContext, withinHours = 1, onlyOnce = true)
        }
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)
            if (Prefs(ctx).notifyBefore) {
                wm.enqueueUniquePeriodicWork(
                    "planner", ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<PlannerWorker>(15, TimeUnit.MINUTES).build()
                )
            } else wm.cancelUniqueWork("planner")
        }
    }
}
