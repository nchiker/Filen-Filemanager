package app.filenfiles

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import app.filenfiles.core.MaterializedFile
import java.io.File

object OpenWith {
    fun open(ctx: Context, f: MaterializedFile) = launch(ctx, f, Intent.ACTION_VIEW)
    fun share(ctx: Context, f: MaterializedFile) = launch(ctx, f, Intent.ACTION_SEND)

    private fun launch(ctx: Context, f: MaterializedFile, action: String) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", File(f.path))
        val mime = f.mime ?: "*/*"
        val intent = if (action == Intent.ACTION_SEND)
            Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
        else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(Intent.createChooser(intent, f.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, "No app can open this file", Toast.LENGTH_SHORT).show()
        }
    }
}
