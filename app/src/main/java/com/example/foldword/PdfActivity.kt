package com.example.foldword

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.foldword.data.DocumentAccess
import com.example.foldword.data.RecentDoc
import com.example.foldword.data.RecentStore
import com.google.android.material.appbar.MaterialToolbar
import java.util.concurrent.Executors

/** View-only PDF reader using Android's built-in PdfRenderer. Works offline; PDF editing is not offered. */
class PdfActivity : AppCompatActivity() {

    companion object {
        fun open(context: Context, uri: Uri) =
            context.startActivity(Intent(context, PdfActivity::class.java).putExtra(EditorActivity.EXTRA_URI, uri.toString()))
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var pfd: ParcelFileDescriptor? = null
    @Volatile private var renderer: PdfRenderer? = null
    private var pageSizes: List<Pair<Int, Int>> = emptyList()
    private var uri: Uri? = null
    private val cache = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        val target = intent.getStringExtra(EditorActivity.EXTRA_URI)?.let { Uri.parse(it) } ?: intent.data
        if (target == null) { finish(); return }
        uri = target
        val list = findViewById<RecyclerView>(R.id.pdfPages)
        list.layoutManager = LinearLayoutManager(this)
        io.execute {
            try {
                DocumentAccess.requireContentUri(target)
                val info = DocumentAccess.info(this, target)
                val fd = contentResolver.openFileDescriptor(target, "r") ?: throw java.io.IOException("This PDF could not be opened.")
                val r = try {
                    PdfRenderer(fd)
                } catch (e: SecurityException) {
                    fd.close()
                    throw java.io.IOException("Password-protected PDFs can't be opened in Fold Word.")
                }
                val sizes = (0 until r.pageCount).map { i -> r.openPage(i).use { p -> p.width to p.height } }
                main.post {
                    pfd = fd
                    renderer = r
                    pageSizes = sizes
                    toolbar.title = info.name
                    toolbar.subtitle = getString(R.string.pdf_subtitle, sizes.size, info.location)
                    RecentStore(this).add(RecentDoc(target.toString(), info.name, "pdf", info.location, System.currentTimeMillis()))
                    list.adapter = PageAdapter()
                }
            } catch (e: Exception) {
                main.post {
                    findViewById<TextView>(R.id.pdfMessage).text = DocumentAccess.friendly(e)
                }
            }
        }
    }

    override fun onDestroy() {
        io.execute {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
        }
        io.shutdown()
        cache.evictAll()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.m_share).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, 2, 0, R.string.open_with_other_app)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val target = uri ?: return false
        when (item.itemId) {
            1 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf")
                .putExtra(Intent.EXTRA_STREAM, target).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), getString(R.string.m_share)))
            2 -> runCatching {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(target, "application/pdf")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), getString(R.string.open_with_other_app)))
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private inner class PageHolder(val image: ImageView) : RecyclerView.ViewHolder(image) {
        var page = -1
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {
        override fun getItemCount() = pageSizes.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val iv = ImageView(parent.context)
            iv.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.setMargins(24, 12, 24, 12) }
            iv.adjustViewBounds = true
            iv.setBackgroundColor(Color.WHITE)
            iv.contentDescription = getString(R.string.pdf_page)
            return PageHolder(iv)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            holder.page = position
            val (w, h) = pageSizes[position]
            val width = (resources.displayMetrics.widthPixels - 48).coerceIn(300, 2200)
            val height = (width.toLong() * h / w.coerceAtLeast(1)).toInt().coerceAtLeast(1)
            holder.image.minimumHeight = height
            holder.image.contentDescription = getString(R.string.pdf_page_n, position + 1)
            val cached = cache.get(position)
            if (cached != null) { holder.image.setImageBitmap(cached); return }
            holder.image.setImageDrawable(null)
            io.execute {
                val r = renderer ?: return@execute
                val bmp = try {
                    r.openPage(position).use { page ->
                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(Color.WHITE)
                            page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                } catch (e: Exception) { null } ?: return@execute
                cache.put(position, bmp)
                main.post { if (holder.page == position) holder.image.setImageBitmap(bmp) }
            }
        }
    }
}
