package com.dongfg.cardvault

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform