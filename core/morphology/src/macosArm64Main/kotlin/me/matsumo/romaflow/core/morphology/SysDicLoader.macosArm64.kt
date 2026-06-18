package me.matsumo.romaflow.core.morphology

import io.github.tokuhirom.momiji.ipadic.sys.SYS
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
internal actual fun loadSysDicBytes(): ByteArray = Base64.decode(SYS)
