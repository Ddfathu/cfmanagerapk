package com.cf.dfathu.data

import java.util.regex.Pattern

enum class BindingType {
    KV_NAMESPACE,
    R2_BUCKET,
    D1_DATABASE,
    PLAIN_TEXT
}

data class DetectedBinding(
    val variableName: String,
    val type: BindingType,
    val confidenceReason: String
)

object SmartWranglerParser {
    fun parseScriptBindings(scriptContent: String): List<DetectedBinding> {
        val detected = mutableMapOf<String, DetectedBinding>()

        // 1. Deteksi D1 Database: env.VAR.prepare(...) atau env.VAR.batch(...) atau env.VAR.exec(...)
        val d1Pattern = Pattern.compile("""(?:env|c\.env)\.([a-zA-Z0-9_]+)\s*\.\s*(?:prepare|batch|exec)\s*\(""")
        val d1Matcher = d1Pattern.matcher(scriptContent)
        while (d1Matcher.find()) {
            val varName = d1Matcher.group(1) ?: continue
            detected[varName] = DetectedBinding(varName, BindingType.D1_DATABASE, "Memanggil method D1 (.prepare/.exec)")
        }

        // 2. Deteksi R2 Bucket: env.VAR.createMultipartUpload(...) atau env.VAR.head(...) atau env.VAR.put(..., { httpMetadata })
        val r2Pattern = Pattern.compile("""(?:env|c\.env)\.([a-zA-Z0-9_]+)\s*\.\s*(?:createMultipartUpload|head|writeHttpMetadata)\s*\(""")
        val r2Matcher = r2Pattern.matcher(scriptContent)
        while (r2Matcher.find()) {
            val varName = r2Matcher.group(1) ?: continue
            detected[varName] = DetectedBinding(varName, BindingType.R2_BUCKET, "Memanggil method khas R2 Storage")
        }

        // 3. Deteksi KV Namespace vs R2 (keduanya punya .get, .put, .delete)
        val kvOrR2Pattern = Pattern.compile("""(?:env|c\.env)\.([a-zA-Z0-9_]+)\s*\.\s*(?:get|put|delete|list)\s*\(""")
        val kvMatcher = kvOrR2Pattern.matcher(scriptContent)
        while (kvMatcher.find()) {
            val varName = kvMatcher.group(1) ?: continue
            if (!detected.containsKey(varName)) {
                // Analisa tipe: jika ada teks 'bucket' atau dipanggil dengan stream/blob -> R2, default -> KV
                if (varName.contains("bucket", ignoreCase = true) || varName.contains("r2", ignoreCase = true)) {
                    detected[varName] = DetectedBinding(varName, BindingType.R2_BUCKET, "Terindikasi R2 Bucket API")
                } else {
                    detected[varName] = DetectedBinding(varName, BindingType.KV_NAMESPACE, "Memanggil API KV (.get/.put/.delete)")
                }
            }
        }

        // 4. Deteksi Plain Text / Secret ENV: env.VAR_NAME (tanpa method call)
        val envPattern = Pattern.compile("""(?:env|c\.env)\.([A-Z0-9_]{3,})\b(?!\s*\()""")
        val envMatcher = envPattern.matcher(scriptContent)
        while (envMatcher.find()) {
            val varName = envMatcher.group(1) ?: continue
            if (!detected.containsKey(varName)) {
                detected[varName] = DetectedBinding(varName, BindingType.PLAIN_TEXT, "Variabel Environment")
            }
        }

        return detected.values.toList()
    }
}
