package com.markusmaribu.picochat.online

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.Realtime

object SupabaseProvider {
    val client = createSupabaseClient(
        supabaseUrl = "https://uupbbvetwmrsldupxcvo.supabase.co",
        supabaseKey = "sb_publishable_Dt7czpY_j0TMq18VMQKl-Q_-eDs5oXN"
    ) {
        install(Realtime)
    }

    @Volatile
    var pendingChannelCleanup: kotlinx.coroutines.Job? = null
}
