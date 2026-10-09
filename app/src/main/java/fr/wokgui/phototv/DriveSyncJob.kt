package fr.wokgui.phototv

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

class DriveSyncJob : JobService() {
    private var cancelled = AtomicBoolean(false)
    override fun onStartJob(params: JobParameters): Boolean {
        cancelled.set(true)
        val jobCancelled = AtomicBoolean(false)
        cancelled = jobCancelled
        Thread {
            val result = runCatching { DriveSync.sync(this) { jobCancelled.get() } }
            android.os.Handler(mainLooper).post { if (!jobCancelled.get()) jobFinished(params, result.isFailure) }
        }.start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { cancelled.set(true); return true }
    companion object {
        private const val ID = 2001
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (scheduler.allPendingJobs.any { it.id == ID }) return
            scheduler.schedule(JobInfo.Builder(ID, ComponentName(context, DriveSyncJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setPersisted(true).setPeriodic(6 * 60 * 60 * 1000L).build())
        }
        fun cancel(context: Context) { (context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).cancel(ID) }
    }
}
