package com.elceller.bebilo.recipes

import com.elceller.bebilo.recipes.config.AppConfigLoader
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main(args: Array<String>) {
    val config = AppConfigLoader.load()
    if (args.isNotEmpty()) {
        require(args.toList() in listOf(listOf("--inventory-image-urls"), listOf("--migrate-image-urls")))
        kotlin.system.exitProcess(com.elceller.bebilo.recipes.data.migration.migrateImageUrls(config, args.single() == "--migrate-image-urls"))
    }
    embeddedServer(Netty, port = config.port) { module() }.start(wait = true)
}
