package com.kanedias.dybr.fair.markdown

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.ClickableSpan
import android.text.style.MetricAffectingSpan
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.SimpleTarget
import com.bumptech.glide.request.transition.Transition
import com.ftinc.scoop.binding.AbstractBinding
import com.kanedias.dybr.fair.service.SpanCache
import com.kanedias.dybr.fair.BuildConfig
import com.kanedias.dybr.fair.service.Network
import com.kanedias.dybr.fair.R
import com.kanedias.dybr.fair.databinding.ViewImageOverlayBinding
import com.kanedias.html2md.Html2Markdown
import com.stfalcon.imageviewer.StfalconImageViewer
import io.noties.markwon.*
import io.noties.markwon.core.spans.LinkSpan
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.html.HtmlTag
import io.noties.markwon.html.tag.SimpleTagHandler
import io.noties.markwon.image.AsyncDrawableScheduler
import io.noties.markwon.image.AsyncDrawableSpan
import io.noties.markwon.image.glide.GlideImagesPlugin
import io.noties.markwon.utils.NoCopySpannableFactory
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.*

/**
 * Get markdown setup from context.
 *
 * This is stripped-down version for use in notifications and other
 * cases where user interaction is not needed.
 *
 * @param ctx context to initialize from
 */
fun mdRendererFrom(ctx: Context): Markwon {
    return Markwon.builder(ctx)
            .usePlugin(HtmlPlugin.create().addHandler(OfftopSpanHandler()))
            .usePlugin(GlideImagesPlugin.create(GlideGifSupportStore(ctx)))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(ctx))
            .build()
}

/**
 * Handle `<span class="offtop">` text. Basically just make it a bit less visible than surroundings.
 */
class OfftopSpanHandler : SimpleTagHandler() {

    override fun getSpans(configuration: MarkwonConfiguration, renderProps: RenderProps, tag: HtmlTag): Any? {
        if (tag.attributes()["class"] == "offtop") {
            return object: MetricAffectingSpan() {
                override fun updateMeasureState(tp: TextPaint) {
                    tp.color = ColorUtils.setAlphaComponent(tp.color, 128)
                }

                override fun updateDrawState(tp: TextPaint) {
                    tp.color = ColorUtils.setAlphaComponent(tp.color, 128)
                }
            }
        }

        return null
    }

    override fun supportedTags(): Collection<String> {
        return listOf("span")
    }

}

/**
 * Perform all necessary steps to view Markdown in this text view.
 * Parses input with html2md library and converts resulting markdown to spanned string.
 * @param html input markdown to show
 */
infix fun TextView.handleMarkdown(html: String) {
    setSpannableFactory(NoCopySpannableFactory())

    val renderer = mdRendererFrom(context)
    val converter = {
        val mdContent = Html2Markdown().parseExtended(html)
        val spanned = renderer.toMarkdown(mdContent) as SpannableStringBuilder
        postProcessSpans(spanned, context)

        spanned
    }

    val cachedMd = SpanCache.forMessageId(html.hashCode(), converter)

    // FIXME: see https://github.com/noties/Markwon/issues/120
    addOnAttachStateChangeListener(object: View.OnAttachStateChangeListener {
        override fun onViewDetachedFromWindow(v: View) {

        }

        override fun onViewAttachedToWindow(v: View) {
            AsyncDrawableScheduler.schedule(v as TextView)
        }

    })

    renderer.setParsedMarkdown(this, cachedMd)
}

const val MORE_START_PATTERN = "\\[MORE=(.*?)]"
const val MORE_END_PATTERN = "\\[/MORE]"

const val MORE_TAG_START_PATTERN = "<details><summary>(.*?)</summary>"
const val MORE_TAG_END_PATTERN = "</details>"

val MORE_START_REGEX = Regex(MORE_START_PATTERN, RegexOption.DOT_MATCHES_ALL)
val MORE_END_REGEX = Regex(MORE_END_PATTERN, RegexOption.DOT_MATCHES_ALL)

val MORE_TAG_START_REGEX = Regex(MORE_TAG_START_PATTERN, RegexOption.DOT_MATCHES_ALL)
val MORE_TAG_END_REGEX = Regex(MORE_TAG_END_PATTERN, RegexOption.DOT_MATCHES_ALL)

// starting ,* is required to capture only inner MORE, see https://regex101.com/r/zbpWUK/1
val MORE_FULL_REGEX = Regex(".*($MORE_START_PATTERN(.*?)$MORE_END_PATTERN)", RegexOption.DOT_MATCHES_ALL)

/**
 * Post-process spans like MORE or image loading
 * @param spanned editable spannable to change
 * @param ctx context that this processing is performed in
 */
fun postProcessSpans(spanned: SpannableStringBuilder, ctx: Context) {
    postProcessDrawables(spanned, ctx)
    postProcessLinks(spanned, ctx)
    postProcessMore(spanned, ctx)
}

/**
 * Post-process links so they are absolute, not relative.
 * Android doesn't support relative links in TextViews
 * @param spanned editable spannable to change
 * @param ctx context that this processing is performed in
 */
fun postProcessLinks(spanned: SpannableStringBuilder, ctx: Context) {
    val linkSpans = spanned.getSpans(0, spanned.length, LinkSpan::class.java)
    for (span in linkSpans) {
        val field = span.javaClass.getDeclaredField("link").apply { isAccessible = true }
        val link = Network.resolve(field.get(span)?.toString())
        link?.let { field.set(span, it.toString()) }
    }
}

/**
 * Post-process drawables, so you can click on them to see them in full-screen
 * @param spanned editable spannable to change
 * @param ctx context that this processing is performed in
 */
fun postProcessDrawables(spanned: SpannableStringBuilder, ctx: Context) {
    val imgSpans = spanned.getSpans(0, spanned.length, AsyncDrawableSpan::class.java)
    imgSpans.sortBy { spanned.getSpanStart(it) }

    for (img in imgSpans) {
        val start = spanned.getSpanStart(img)
        val end = spanned.getSpanEnd(img)
        val spansToWrap = spanned.getSpans(start, end, CharacterStyle::class.java)
        if (spansToWrap.any { it is ClickableSpan }) {
            // the image is already clickable, we can't replace it
            continue
        }

        val wrapperClick = object : ClickableSpan() {
            override fun onClick(widget: View) {
                val index = imgSpans.indexOf(img)
                if (index == -1) {
                    // something modified spannable in a way image is no longer here
                    return
                }

                val overlay = ImageShowOverlay(widget.context)
                overlay.update(imgSpans[index])

                StfalconImageViewer.Builder(widget.context, imgSpans) { view, span ->
                    val resolved = Network.resolve(span.drawable.destination) ?: return@Builder
                    Glide.with(view).load(resolved.toString()).into(view)
                }
                        .withOverlayView(overlay)
                        .withStartPosition(index)
                        .withImageChangeListener { position -> overlay.update(imgSpans[position])}
                        .allowSwipeToDismiss(true)
                        .show()
            }
        }

        spanned.setSpan(wrapperClick, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

/**
 * Post-process MORE statements in the text. They act like `<spoiler>` or `<cut>` tag in some websites
 * @param spanned text to be modified to cut out MORE tags and insert replacements instead of them
 * @param ctx context that this processing is performed in
 */
fun postProcessMore(spanned: SpannableStringBuilder, ctx: Context) {
    while (true) {
        // we need to process all MOREs in the text, start from inner ones, get back to outer in next loops
        val match = MORE_FULL_REGEX.find(spanned) ?: break
        // we have a match, make a replacement

        // get group content out of regex
        val outerRange = match.groups[1]!!.range // from start of [MORE] to the end of [/MORE]
        val moreRange = match.groups[2]!!.range // content inside opening tag [MORE=...]
        val innerRange = match.groups[3]!!.range // range between opening and closing tag of MORE

        // spoiler text inside opening tag [MORE=...] with all spans there
        var moreSpanned = spanned.subSequence(moreRange.first, moreRange.last + 1) as SpannableStringBuilder
        if (moreSpanned.isEmpty()) {
            // it was degenerate [MORE=][/MORE] tag without spoiler text, use default instead
            moreSpanned = SpannableStringBuilder(ctx.getString(R.string.more_tag_default))
        }

        // content between opening and closing tag with all spans there
        val innerSpanned = spanned.subSequence(innerRange.first, innerRange.last + 1) as SpannableStringBuilder

        // don't want any links where [MORE] expansion should be
        moreSpanned.getSpans(0, moreSpanned.length, LinkSpan::class.java).forEach { moreSpanned.removeSpan(it) }
        moreSpanned.getSpans(0, moreSpanned.length, ClickableSpan::class.java).forEach { moreSpanned.removeSpan(it) }

        spanned.replace(outerRange.first, outerRange.last + 1, moreSpanned) // replace it just with text
        val wrapper = object : ClickableSpan() {

            override fun onClick(widget: View) {
                val textView = widget as TextView
                // replace wrappers with real previous spans

                val start = spanned.getSpanStart(this)
                val end = spanned.getSpanEnd(this)

                moreSpanned.getSpans(0, moreSpanned.length, Any::class.java).forEach { spanned.removeSpan(it) }
                spanned.removeSpan(this)
                spanned.replace(start, end, innerSpanned)

                textView.text = spanned
                AsyncDrawableScheduler.schedule(textView)
            }
        }
        spanned.setSpan(wrapper, outerRange.first, outerRange.first + moreSpanned.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

/**
 * Version without parsing html
 * @see handleMarkdown
 */
infix fun TextView.handleMarkdownRaw(markdown: String) {
    val renderer = mdRendererFrom(context)
    val spanned = renderer.toMarkdown(markdown) as SpannableStringBuilder
    postProcessSpans(spanned, context)

    renderer.setParsedMarkdown(this, spanned)
}

/**
 * Helper class that tints drawable according to current style.
 * In short, this makes placeholders dark in light theme and light in dark one.
 */
class DrawableBinding(drawable: Drawable, topping: Int): AbstractBinding(topping) {

    private val dwRef = WeakReference(drawable)

    override fun update(color: Int) {
        dwRef.get()?.let {
            val wrapped = DrawableCompat.wrap(it)
            DrawableCompat.setTint(wrapped, color)
        }
    }

    override fun unbind() {
        dwRef.clear()
    }

}

/**
 * Class responsible for showing "Share" and "Download" buttons when viewing images in full-screen.
 */
class ImageShowOverlay(ctx: Context,
                       attrs: AttributeSet? = null,
                       defStyleAttr: Int = 0) : FrameLayout(ctx, attrs, defStyleAttr) {

    private val binding = ViewImageOverlayBinding.inflate(LayoutInflater.from(ctx), this)

    fun update(span: AsyncDrawableSpan) {
        val resolved = Network.resolve(span.drawable.destination) ?: return

        // share button: share the image using file provider
        binding.overlayShare.setOnClickListener {
            Glide.with(it).asFile().load(resolved.toString()).into(object: SimpleTarget<File>() {

                override fun onResourceReady(resource: File, transition: Transition<in File>?) {
                    val shareUri = saveToShared(resource)

                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/*"
                        putExtra(Intent.EXTRA_STREAM, shareUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_image_using)))
                }

            })
        }

        // download button: download the image to Download folder on internal SD
        binding.overlayDownload.setOnClickListener {
            val activity = context as? Activity ?: return@setOnClickListener

            // request SD write permissions if we don't have it already
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 0)
                return@setOnClickListener
            }

            Toast.makeText(context, R.string.downloading_image, Toast.LENGTH_SHORT).show()
            Glide.with(it).asFile().load(resolved.toString()).into(object: SimpleTarget<File>() {

                override fun onResourceReady(resource: File, transition: Transition<in File>?) {
                    val downloadDir = Environment.DIRECTORY_DOWNLOADS
                    val filename = resolved.pathSegments().last()

                    // on API >= 29 we must use media store API, direct access to SD-card is no longer available
                    val ostream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val resolver = context.contentResolver
                        val contentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                            put(MediaStore.MediaColumns.MIME_TYPE, "image/*")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, downloadDir)
                        }
                        val imageUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)!!
                        resolver.openOutputStream(imageUri)!!
                    } else {
                        @Suppress("DEPRECATION") // should work on API < 29
                        val downloads = Environment.getExternalStoragePublicDirectory(downloadDir)
                        File(downloads, filename).outputStream()
                    }

                    ostream.write(resource.readBytes())

                    val report = context.getString(R.string.image_saved_as) + " $downloadDir/$filename"
                    Toast.makeText(context, report, Toast.LENGTH_SHORT).show()
                }

            })
        }
    }

    /**
     * Save image file to location that is shared from xml/shared_paths.
     * After that it's possible to share this file to other applications
     * using [FileProvider].
     *
     * @param image image file to save
     * @return Uri that file-provider returns, or null if unable to
     */
    private fun saveToShared(image: File) : Uri? {
        try {
            val sharedImgs = File(context.cacheDir, "shared_images")
            if (!sharedImgs.exists() && !sharedImgs.mkdir()) {
                Log.e("Fair/Markdown", "Couldn't create dir for shared imgs! Path: $sharedImgs")
                return null
            }

            // cleanup old images
            for (oldImg in sharedImgs.listFiles().orEmpty()) {
                if (!oldImg.delete()) {
                    Log.w("Fair/Markdown", "Couldn't delete old image file! Path $oldImg")
                }
            }

            val imgTmpFile = File(sharedImgs, UUID.randomUUID().toString())
            imgTmpFile.writeBytes(image.readBytes())

            return FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".fileprovider", imgTmpFile)
        } catch (e: IOException) {
            Log.d("Fair/Markdown", "IOException while trying to write file for sharing", e)
        }

        return null
    }
}

/**
 * Converts Markdown to HTML.
 * Required for posting and commenting routines.
 */
fun markdownToHtml(md: String): String {
    // we have to convert MORE tags to normal HTML tags
    // otherwise we can end up in the situation when HTML tag, say, <p>, is opened
    // outside the MORE and closed inside, causing messy logic on the website version
    // to close MORE as well, before it really needs to be closed
    // see example text here: https://regex101.com/r/ruWyDj/1
    // without postprocessing it is converted into this: https://regex101.com/r/HNrPE9/1
    val mdPostProcessed = md
            .replace(MORE_START_REGEX, "<details><summary>$1</summary>")
            .replace(MORE_END_REGEX, "</details>")

    val extensions = listOf(StrikethroughExtension.create(), TablesExtension.create())
    val parser = Parser.builder().extensions(extensions).build()
    val document = parser.parse(mdPostProcessed)
    val html = HtmlRenderer.builder().extensions(extensions).build().render(document)

    // and then we convert it back to BB-style tags so the logic on the website
    // can pick it up from here. Uhh, this is clearly a hack and I'm so not happy about this...
    return html
            .replace(MORE_TAG_START_REGEX, "[MORE=$1]")
            .replace(MORE_TAG_END_REGEX, "[/MORE]")
}