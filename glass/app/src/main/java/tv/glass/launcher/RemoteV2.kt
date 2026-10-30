package tv.glass.launcher

import android.content.Context

/**
 * Android TV Remote protocol v2 was tried first for phone keys (CLAUDE.md rule 9), but a TV's remote service
 * refuses key connections that come from the TV itself, so Glass can't be its own client. Phone keys go through
 * KeyService (accessibility) and AudioManager instead. The client was removed from the public build; this only
 * deletes the test key an earlier build may have left behind (the TV trusted it for LAN control).
 */
object RemoteV2 {
    fun retire(ctx: Context) {
        java.io.File(ctx.filesDir, "atvremote.p12").delete()
        Store.of(ctx).p.edit().remove("remote_paired_v2").remove("remote_cert").remove("p12pw").apply()
    }
}
