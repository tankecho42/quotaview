package com.tankecho.quotaview

import android.content.Context
import android.os.Bundle
import org.json.JSONObject
import java.util.UUID

/**
 * OPPO ColorOS 流体云 端侧意图共享 (设备本地, 无需网络).
 * 文档: open.oppomobile.com id=13558 (意图共享端侧) / id=13565 (数据结构)
 *
 * 权威调用方式:
 *   client = contentResolver.acquireUnstableContentProviderClient("IntelligentIntent")
 *   client.call("shareIntent", null, Bundle{ putString("intentData", json) })
 * 返回 Bundle.getString("result") → {"code":0,...} / 错误码表:
 *   10101001 无意图共享权限 · 10102001 参数错误 · 10103003 意图共享开关关闭 · ...
 */
object OppoFluidCloud {

    fun share(context: Context, usedPct: Int, timePct: Int, provName: String, windowLabel: String): JSONObject {
        val prefs = context.getSharedPreferences("qv", Context.MODE_PRIVATE)
        val ident = prefs.getString("fluid_ident", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("fluid_ident", it).apply()
        }

        val data = JSONObject().apply {
            put("intentName", "QuotaView.Island")
            put("identifier", ident)
            put("timestamp", System.currentTimeMillis())
            put("serviceId", JSONObject().apply {
                put("launcher", "999800001")
                put("fluidCloud", "999900001")
            })
            put("intentAction", JSONObject().apply { put("actionStatus", 1) }) // 0创卡 1刷新 2销卡
            put("intentEntity", JSONObject().apply {
                put("entityName", "TASK")
                put("entityId", "qv_$provName")
                put("milestone", JSONObject().apply { put("code", 20); put("text", "in_progress") })
                put("capsule", JSONObject().apply {
                    put("rightText", "用量 ${usedPct}%")
                    put("legacyText", "${provName} ${usedPct}%")
                })
                put("primary", JSONObject().apply {
                    put("title", JSONObject().apply { put("text", "$provName · $windowLabel") })
                    put("content", "用量 ${usedPct}% · 时间已过 ${timePct}%")
                })
                put("secondaryData", JSONObject().apply {
                    put("type", "PROGRESS")
                    put("progress", usedPct)
                    put("style", "inside")
                })
            })
        }

        val result = try {
            // 依次尝试: 文档简写 authority → 枚举系统 provider 匹配 intent 关键词
            val authorities = mutableListOf("IntelligentIntent")
            try {
                val pm = context.packageManager
                @Suppress("DEPRECATION")
                val providers = pm.getInstalledPackages(android.content.pm.PackageManager.GET_PROVIDERS)
                providers.forEach { pi ->
                    pi.providers?.forEach { pr ->
                        val a = pr.authority ?: return@forEach
                        if (a.contains("ntelligent", true) || a.contains("fluid", true) || a.contains("intent", true) && a.contains("oplus", true)) {
                            authorities.add(a)
                        }
                    }
                }
            } catch (_: Exception) {}
        var okResult: JSONObject? = null
        val errors = JSONObject()
        authorities.distinct().forEach { authority ->
            if (okResult != null) return@forEach
            try {
                val client = context.contentResolver.acquireUnstableContentProviderClient(authority)
                if (client != null) {
                    val bundle = client.call("shareIntent", null, Bundle().apply { putString("intentData", data.toString()) })
                    client.close()
                    val raw = bundle?.getString("result")
                    if (raw != null) {
                        val jo = JSONObject(raw)
                        jo.put("authority", authority)
                        okResult = jo
                    } else {
                        errors.put(authority.takeLast(30), "空返回")
                    }
                } else {
                    errors.put(authority.takeLast(30), "client=null")
                }
            } catch (e: Exception) {
                errors.put(authority.takeLast(30), (e.message ?: e.javaClass.simpleName).take(80))
            }
        }
        okResult ?: JSONObject().put("code", -1).put("errors", errors)
        } catch (e: Exception) {
            JSONObject().put("code", -3).put("message", e.message ?: e.javaClass.simpleName)
        }

        prefs.edit().putString("fluid_result", result.toString())
            .putLong("fluid_ts", System.currentTimeMillis()).apply()
        android.util.Log.i("QVIsland", "fluidCloud share → $result")
        return result
    }
}
