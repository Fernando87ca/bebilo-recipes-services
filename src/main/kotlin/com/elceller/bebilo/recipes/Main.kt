package com.elceller.bebilo.recipes

import com.elceller.bebilo.recipes.config.AppConfigLoader
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    val config = AppConfigLoader.load()
    embeddedServer(Netty, port = config.port) { module() }.start(wait = true)
}
