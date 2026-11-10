package com.tisma.scanner.cloud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.tisma.scanner.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * El envío de un escaneo a Tisma Cloud como trabajo de WorkManager: sigue con la app en segundo plano o
 * cerrada (con su notificación de progreso), sólo corre con red, y si la red se corta lo reintenta solo
 * cuando vuelve, desde donde quedó (CloudSend guarda el id de la subida y del trabajo).
 */
class CloudSendWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val zip = File(inputData.getString(KEY_ZIP) ?: return@withContext Result.failure(error("sin escaneo")))
        if (!zip.exists()) return@withContext Result.failure(error("el escaneo ya no está en el teléfono"))
        val prefs = applicationContext.getSharedPreferences("tisma", Context.MODE_PRIVATE)
        val token = prefs.getString("cloud_token", "") ?: ""
        val url = inputData.getString(KEY_URL) ?: ""
        if (token.isBlank()) return@withContext Result.failure(error("Entra con tu cuenta de Tisma Cloud (⚙ → Nube)", 401))
        val store = prefsStore(prefs)
        runCatching { setForeground(foreground("Enviando a Tisma Cloud…", -1)) }
        // Por qué se está esperando, para mostrarlo mientras WorkManager tiene el envío en cola para reintentar.
        fun waitReason(text: String?) = prefs.edit().apply { if (text == null) remove(KEY_WAIT) else putString(KEY_WAIT, text) }.apply()
        waitReason(null)
        try {
            val result = CloudSend(CloudClient(url, token), store).run(zip, onStatus = { text, p ->
                val percent = if (p >= 0f) (p * 100).toInt() else -1
                setProgressAsync(workDataOf(KEY_TEXT to text, KEY_PROGRESS to p))
                runCatching { setForegroundAsync(foreground(text, percent)) }
            }, isCancelled = { isStopped })
            Result.success(workDataOf(KEY_STL to result.stl.path, KEY_SUMMARY to result.summary, KEY_JOB to result.jobId))
        } catch (e: CloudClient.HttpError) {
            // Un error de la petición (sesión vencida, plan sin escaneos…): reintentar no lo arregla.
            if (e.code in 400..499 && e.code != 408 && e.code != 429) Result.failure(error(e.message ?: "error", e.code))
            else {
                waitReason(if (e.code == CloudClient.SERVER_FULL) e.message else "El servidor no responde: se reintenta solo")
                retryOrFail(e)
            }
        } catch (e: CloudSend.JobFailed) {
            Result.failure(error(e.message ?: "el escaneo falló en el servidor"))
        } catch (e: IOException) {
            waitReason("Sin conexión: se retoma sola al volver la red")
            retryOrFail(e)  // corte de red: WorkManager lo retoma cuando vuelve la red
        }
    }

    private fun retryOrFail(e: Exception): Result =
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure(error(CloudClient.userMessage(e)))

    private fun error(text: String, code: Int = 0): Data = workDataOf(KEY_ERROR to text, KEY_CODE to code)

    private fun foreground(text: String, percent: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Envío a Tisma Cloud", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Tisma Scanner")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .apply { if (percent in 0..100) setProgress(100, percent, false) else setProgress(0, 0, true) }
            .build()
        return ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val UNIQUE = "envio_nube"
        const val KEY_ZIP = "zip"
        const val KEY_URL = "url"
        const val KEY_TEXT = "texto"
        const val KEY_PROGRESS = "progreso"
        const val KEY_STL = "stl"
        const val KEY_SUMMARY = "medidas"
        /** El trabajo de escaneo en la nube: «Imprimir» guarda su resultado en la cuenta. */
        const val KEY_JOB = "trabajo"
        const val KEY_ERROR = "error"
        const val KEY_CODE = "codigo"
        /** En las preferencias: por qué el envío espera para reintentar (sin red, servidor sin lugar…). */
        const val KEY_WAIT = "cloud_wait"
        private const val CHANNEL = "nube"
        private const val NOTIFICATION_ID = 43
        /** Cortes de red seguidos antes de rendirse (con esperas crecientes: varias horas en total). */
        const val MAX_ATTEMPTS = 20

        /**
         * Lo hecho del envío (id de la subida y del trabajo) en las preferencias, escrito en el disco en el momento
         * (commit, en el hilo del trabajo): con apply() se escribe más tarde, y si el sistema cierra la app justo
         * después de crear la subida, el próximo intento no la encuentra y vuelve a subir todo el escaneo.
         */
        fun prefsStore(prefs: android.content.SharedPreferences) = object : CloudSend.Store {
            override fun get(key: String) = prefs.getString(key, null)
            override fun put(key: String, value: String?) {
                val e = prefs.edit()
                if (value == null) e.remove(key) else e.putString(key, value)
                if (!e.commit()) throw IOException("no se pudo guardar el avance del envío")
            }
        }

        /** La etiqueta del trabajo con el zip que manda: así la app sabe de qué escaneo es cada resultado. */
        fun tagFor(zip: File) = "zip:" + zip.absolutePath

        /**
         * Si un trabajo (por sus etiquetas) es del escaneo `zip` que la app tiene ahora. Sin escaneo (la app recién
         * abierta) vale cualquiera: es el último envío. Uno sin etiqueta de zip (de una versión anterior) sólo entonces.
         */
        fun isFor(tags: Set<String>, zip: File?): Boolean =
            zip == null || tagFor(zip) in tags

        /** Pone el envío en cola: uno a la vez; si ya hay uno en curso, sigue ése. */
        fun enqueue(ctx: Context, zip: File, url: String) {
            val req = OneTimeWorkRequestBuilder<CloudSendWorker>()
                .setInputData(workDataOf(KEY_ZIP to zip.path, KEY_URL to url))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(tagFor(zip))
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, req)
        }
    }
}
