package org.voxis.dictation.engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object ModelManager {

    private const val TAG = "ModelManager"
    private const val PREFS_NAME = "voxis_model_prefs"
    private const val KEY_ACTIVE_MODEL_ID = "active_model_id"
    private const val KEY_CUSTOM_MODELS = "custom_models_json"

    fun getModelsDir(context: Context): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getAvailableModels(context: Context): List<SpeechModel> {
        val modelsDir = getModelsDir(context)
        val predefined = SpeechModel.ALL_PREDEFINED.map { model ->
            val localFile = File(modelsDir, model.fileName)
            val unzippedDir = File(modelsDir, model.fileName.substringBeforeLast("."))
            if (unzippedDir.exists() && unzippedDir.isDirectory) {
                model.copy(localPath = unzippedDir.absolutePath)
            } else if (localFile.exists() && localFile.length() > 500 * 1024) {
                model.copy(localPath = localFile.absolutePath)
            } else {
                model.copy(localPath = null)
            }
        }

        val custom = getCustomModels(context).map { model ->
            val localFile = File(modelsDir, model.fileName)
            val unzippedDir = File(modelsDir, model.fileName.substringBeforeLast("."))
            if (unzippedDir.exists() && unzippedDir.isDirectory) {
                model.copy(localPath = unzippedDir.absolutePath)
            } else if (localFile.exists() && localFile.length() > 0) {
                model.copy(localPath = localFile.absolutePath)
            } else {
                model
            }
        }

        return predefined + custom
    }

    fun getActiveModel(context: Context): SpeechModel {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val activeId = prefs.getString(KEY_ACTIVE_MODEL_ID, SpeechModel.HINGLISH_TINY.id)
            ?: SpeechModel.HINGLISH_TINY.id
        val all = getAvailableModels(context)
        return all.find { it.id == activeId } ?: all.firstOrNull() ?: SpeechModel.HINGLISH_TINY
    }

    fun setActiveModel(context: Context, modelId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ACTIVE_MODEL_ID, modelId).apply()
    }

    suspend fun downloadModel(
        context: Context,
        model: SpeechModel,
        onProgress: (progress: Float, downloaded: Long, total: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val modelsDir = getModelsDir(context)
        val finalFile = File(modelsDir, model.fileName)
        val tempFile = File(modelsDir, "${model.fileName}.tmp")

        try {
            var targetUrl = model.downloadUrl
            var connection: HttpURLConnection
            var redirects = 0
            val maxRedirects = 6

            while (true) {
                val url = URL(targetUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.setRequestProperty("User-Agent", "Voxis-Offline-Dictation/1.0")

                val status = connection.responseCode
                if (status in 300..399) {
                    val newLocation = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (newLocation != null && redirects < maxRedirects) {
                        targetUrl = newLocation
                        redirects++
                        continue
                    } else {
                        return@withContext Result.failure(IllegalStateException("Too many redirects or missing Location header"))
                    }
                } else if (status == HttpURLConnection.HTTP_OK) {
                    break
                } else {
                    connection.disconnect()
                    return@withContext Result.failure(IllegalStateException("HTTP error code $status from $targetUrl"))
                }
            }

            val totalBytes = connection.contentLengthLong.let { if (it > 0) it else (model.approximateSizeMb * 1024L * 1024L) }
            var downloadedBytes = 0L

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    var lastProgressUpdate = 0L

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read

                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdate > 200) {
                            lastProgressUpdate = now
                            val progress = (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                            onProgress(progress, downloadedBytes, totalBytes)
                        }
                    }
                    output.flush()
                }
            }
            connection.disconnect()

            if (tempFile.exists() && tempFile.length() > 500 * 1024) {
                if (finalFile.exists()) {
                    finalFile.delete()
                }
                if (tempFile.renameTo(finalFile)) {
                    val resolvedFile = if (finalFile.name.endsWith(".zip", ignoreCase = true)) {
                        val unzipped = unzipModelArchive(finalFile, modelsDir)
                        unzipped ?: finalFile
                    } else {
                        finalFile
                    }

                    model.localPath = resolvedFile.absolutePath
                    onProgress(1f, finalFile.length(), finalFile.length())
                    Result.success(resolvedFile)
                } else {
                    Result.failure(IllegalStateException("Failed to rename temporary download file"))
                }
            } else {
                tempFile.delete()
                Result.failure(IllegalStateException("Downloaded file is incomplete or corrupted (< 500KB)"))
            }
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            Result.failure(e)
        }
    }

    suspend fun importModelFromUri(
        context: Context,
        uri: Uri,
        fileNameHint: String?,
        targetLanguage: String
    ): Result<SpeechModel> = withContext(Dispatchers.IO) {
        try {
            var fileName = fileNameHint ?: "custom_model.bin"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    val name = cursor.getString(nameIndex)
                    if (!name.isNullOrBlank()) {
                        fileName = name
                    }
                }
            }

            val safeFileName = fileName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
            val targetFile = File(getModelsDir(context), safeFileName)

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            } ?: return@withContext Result.failure(IllegalStateException("Cannot open input stream for selected model"))

            val resolvedFile = if (targetFile.name.endsWith(".zip", ignoreCase = true)) {
                unzipModelArchive(targetFile, getModelsDir(context)) ?: targetFile
            } else {
                targetFile
            }

            val sizeMb = (targetFile.length() / (1024 * 1024)).toInt().coerceAtLeast(1)
            val langCode = when {
                targetLanguage.contains("Hinglish", ignoreCase = true) -> "hi-en"
                targetLanguage.contains("Hindi", ignoreCase = true) -> "hi"
                else -> "en"
            }

            val customModel = SpeechModel(
                id = "custom_${System.currentTimeMillis()}",
                displayName = safeFileName.substringBeforeLast("."),
                language = targetLanguage,
                languageCode = langCode,
                approximateSizeMb = sizeMb,
                description = "User-imported local model ($safeFileName) from device storage.",
                downloadUrl = "",
                fileName = safeFileName,
                isBundled = false,
                isCustom = true,
                localPath = resolvedFile.absolutePath
            )

            saveCustomModel(context, customModel)
            setActiveModel(context, customModel.id)

            Result.success(customModel)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun unzipModelArchive(zipFile: File, destDir: File): File? {
        try {
            val baseName = zipFile.name.substringBeforeLast(".")
            val targetFolder = File(destDir, baseName)
            if (!targetFolder.exists()) {
                targetFolder.mkdirs()
            }

            ZipInputStream(FileInputStream(zipFile)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val cleanName = entry.name.substringAfter("/")
                    if (cleanName.isNotBlank()) {
                        val newFile = File(targetFolder, cleanName)
                        if (entry.isDirectory) {
                            newFile.mkdirs()
                        } else {
                            newFile.parentFile?.mkdirs()
                            FileOutputStream(newFile).use { fos ->
                                zis.copyTo(fos)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            return targetFolder
        } catch (e: Exception) {
            Log.e(TAG, "Unzip error: ${e.message}", e)
            return null
        }
    }

    fun deleteModel(context: Context, model: SpeechModel): Boolean {
        var deleted = false
        if (!model.localPath.isNullOrBlank()) {
            val file = File(model.localPath!!)
            if (file.exists()) {
                deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
            }
        }

        if (model.isCustom) {
            val customs = getCustomModels(context).toMutableList()
            customs.removeAll { it.id == model.id }
            saveCustomModelsList(context, customs)
        }

        val activeId = getActiveModel(context).id
        if (activeId == model.id) {
            setActiveModel(context, SpeechModel.HINGLISH_TINY.id)
        }
        return deleted
    }

    private fun getCustomModels(context: Context): List<SpeechModel> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_CUSTOM_MODELS, "[]") ?: "[]"
        val list = mutableListOf<SpeechModel>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val fileName = obj.optString("fileName", "model.bin")
                val localFile = File(getModelsDir(context), fileName)
                val unzippedDir = File(getModelsDir(context), fileName.substringBeforeLast("."))
                val resolvedPath = if (unzippedDir.exists() && unzippedDir.isDirectory) {
                    unzippedDir.absolutePath
                } else if (localFile.exists()) {
                    localFile.absolutePath
                } else null

                list.add(
                    SpeechModel(
                        id = obj.getString("id"),
                        displayName = obj.getString("displayName"),
                        language = obj.getString("language"),
                        languageCode = obj.optString("languageCode", "en"),
                        approximateSizeMb = obj.optInt("approximateSizeMb", 10),
                        description = obj.optString("description", "Custom local model"),
                        downloadUrl = "",
                        fileName = fileName,
                        isBundled = false,
                        isCustom = true,
                        localPath = resolvedPath
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    private fun saveCustomModel(context: Context, model: SpeechModel) {
        val list = getCustomModels(context).toMutableList()
        list.removeAll { it.id == model.id }
        list.add(model)
        saveCustomModelsList(context, list)
    }

    private fun saveCustomModelsList(context: Context, list: List<SpeechModel>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = JSONArray()
        for (m in list) {
            val obj = JSONObject().apply {
                put("id", m.id)
                put("displayName", m.displayName)
                put("language", m.language)
                put("languageCode", m.languageCode)
                put("approximateSizeMb", m.approximateSizeMb)
                put("description", m.description)
                put("fileName", m.fileName)
            }
            arr.put(obj)
        }
        prefs.edit().putString(KEY_CUSTOM_MODELS, arr.toString()).apply()
    }
}
