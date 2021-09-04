package com.kanedias.dybr.fair.markdown

import android.content.Context
import android.graphics.Bitmap
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.kanedias.dybr.fair.service.Network
import com.kanedias.dybr.fair.R
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.glide.GlideImagesPlugin
import java.nio.charset.Charset
import java.security.MessageDigest


/**
 * Glide store that scales up smilies
 *
 * @author Kanedias
 *
 * Created on 2020-01-07
 */
class GlideGifSupportStore(ctx: Context): GlideImagesPlugin.GlideStore {

    private val requestManager = Glide.with(ctx)
        .applyDefaultRequestOptions(
            RequestOptions()
                .centerInside()
                .override(ctx.resources.displayMetrics.widthPixels, Target.SIZE_ORIGINAL)
                .transform(ScaleToDensity(ctx))
                .placeholder(R.drawable.image)
                .error(R.drawable.image_broken))

    override fun cancel(target: Target<*>) = requestManager.clear(target)

    override fun load(drawable: AsyncDrawable) =
            requestManager.load(Network.resolve(drawable.destination).toString())


    /**
     * scales small images to match density of the screen. Mainly needed for smiley pictures.
     */
    class ScaleToDensity(ctx: Context): BitmapTransformation() {
        companion object {
            const val ID = "com.kanedias.holywarsoo.markdown.ScaleToDensity"
            val ID_BYTES = ID.toByteArray(Charset.forName("UTF-8"))
        }

        private val density = ctx.resources.displayMetrics.density            // e.g. 1.5 or 2.0
        private val screenWidth = ctx.resources.displayMetrics.widthPixels    // e.g. 1080

        override fun transform(pool: BitmapPool, toTransform: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
            if (outHeight <= 100) {
                // too small bitmap (possibly smilie), scale it up
                val scaledWidth = (toTransform.width * density).toInt()
                val scaledHeight = (toTransform.height * density).toInt()
                return Bitmap.createScaledBitmap(toTransform, scaledWidth, scaledHeight, true)
            }

            if (outWidth > screenWidth) {
                // too large bitmap, downscale the image
                val downscaleRatio = screenWidth.toFloat() / outWidth // e.g. 0.5
                val downscaledWidth = (toTransform.width * downscaleRatio).toInt() // 2160 * 0.5 = 1080
                val downscaledHeight = (toTransform.height * downscaleRatio).toInt() // 3840 * 0.5 = 1920
                return Bitmap.createScaledBitmap(toTransform, downscaledWidth, downscaledHeight, true)
            }

            return toTransform
        }

        override fun equals(other: Any?) = other is ScaleToDensity

        override fun hashCode() = ID.hashCode()

        override fun updateDiskCacheKey(messageDigest: MessageDigest) {
            messageDigest.update(ID_BYTES)
        }
    }
}