package com.swipeguard.xposed.data

import com.swipeguard.xposed.model.SwipeGuardConfig
import kotlinx.serialization.json.Json

object JsonCodec {
    fun encode(config: SwipeGuardConfig): String = SwipeGuardConfig.toJson(config)
    fun decode(jsonStr: String): SwipeGuardConfig = SwipeGuardConfig.fromJson(jsonStr)
}
