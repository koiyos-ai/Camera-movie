package filmlab.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.util.Log

/**
 * 自動現像: 純正カメラが新しい DNG を保存したら（MediaStore の変更をトリガーに）
 * 現在の設定でフィルム現像して Pictures/FilmLab に保存する。
 * コンテンツ URI トリガーのジョブは 1 回限りなので、実行のたびに再登録する。
 */
class AutoDevelopJob : JobService() {
    @Volatile
    private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val ctx = applicationContext
        worker = Thread {
            try {
                runOnce(ctx)
            } catch (t: Throwable) {
                Log.e(TAG, "auto develop failed", t)
                Prefs.setLastMessage(ctx, "自動現像エラー: ${t.message}")
            } finally {
                jobFinished(params, false)
                if (Prefs.autoEnabled(ctx)) schedule(ctx)
            }
        }.apply {
            name = "filmlab-auto"
            start()
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val TAG = "FilmLab"
        const val JOB_ID = 4201
        private const val CHANNEL = "auto"

        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            val info = JobInfo.Builder(JOB_ID, ComponentName(ctx, AutoDevelopJob::class.java))
                .addTriggerContentUri(
                    JobInfo.TriggerContentUri(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                    )
                )
                .setTriggerContentUpdateDelay(1500)
                .setTriggerContentMaxDelay(10000)
                .build()
            js.schedule(info)
        }

        fun cancel(ctx: Context) {
            ctx.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        }

        /** 未処理の新しい DNG をすべて現像する（アプリ内の「未現像をまとめて現像」からも使う） */
        fun runOnce(ctx: Context, since: Long = Prefs.autoSince(ctx), onItem: ((String) -> Unit)? = null): Int {
            if (ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED &&
                ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) != PackageManager.PERMISSION_GRANTED
            ) return 0
            if (since == Long.MAX_VALUE) return 0
            val done = Prefs.processed(ctx)
            val items = MediaRepo.queryDngs(ctx, since, 200).filter { it.id.toString() !in done && it.size > 0 }
                .sortedBy { it.dateAddedSec }
            var count = 0
            for (item in items) {
                onItem?.invoke(item.name)
                try {
                    val params = Prefs.params(ctx)
                    val l = Processor.load(ctx, item.uri, item.name, item.dateTakenMs)
                    val an = l.session.analyze(params, l.reference, Processor.PREVIEW_SIZE)
                    Processor.save(ctx, l, params, an)
                    Prefs.markProcessed(ctx, item.id)
                    count++
                    notify(ctx, "${MediaRepo.baseName(item.name)} をフィルム現像しました")
                } catch (t: Throwable) {
                    Log.e(TAG, "develop ${item.name} failed", t)
                    Prefs.markProcessed(ctx, item.id) // 壊れたファイルで無限に再試行しない
                    Prefs.setLastMessage(ctx, "${item.name}: ${t.message}")
                }
                System.gc()
            }
            if (count > 0) Prefs.setLastMessage(ctx, "自動現像: ${count} 枚")
            return count
        }

        private fun notify(ctx: Context, text: String) {
            if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "自動現像", NotificationManager.IMPORTANCE_LOW))
            val pi = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("FilmLab")
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(1001, n)
        }
    }
}

/** 再起動・アプリ更新後に自動現像の監視を再登録 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs.autoEnabled(context)) AutoDevelopJob.schedule(context)
    }
}
