package filmlab.core.util

import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * 画素処理用の簡易並列化。行範囲をチャンクに分けてワーカースレッドで実行する。
 * ワーカー内からの入れ子呼び出しは直列実行にフォールバック（デッドロック防止）。
 */
object Parallel {
    val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)

    private const val WORKER_PREFIX = "filmlab-worker"

    private val pool: ExecutorService = Executors.newFixedThreadPool(threads) { r ->
        Thread(r, "$WORKER_PREFIX-${System.nanoTime()}").apply { isDaemon = true }
    }

    /** [0, n) を分割して body(start, endExclusive) を並列実行し、完了まで待つ。 */
    fun forChunks(n: Int, minChunk: Int = 8, body: (Int, Int) -> Unit) {
        if (n <= 0) return
        val inWorker = Thread.currentThread().name.startsWith(WORKER_PREFIX)
        val maxChunks = (n + minChunk - 1) / minChunk
        val chunks = minOf(threads * 3, maxChunks)
        if (inWorker || chunks <= 1 || threads == 1) {
            body(0, n)
            return
        }
        val size = (n + chunks - 1) / chunks
        val futures = ArrayList<Future<*>>(chunks)
        var start = 0
        while (start < n) {
            val s = start
            val e = minOf(n, start + size)
            futures.add(pool.submit(Runnable { body(s, e) }))
            start = e
        }
        var error: Throwable? = null
        for (f in futures) {
            try {
                f.get()
            } catch (e: ExecutionException) {
                if (error == null) error = e.cause ?: e
            }
        }
        if (error != null) throw error
    }
}
