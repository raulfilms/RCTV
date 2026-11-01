package com.nuvio.tv.core.image

import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.size.Dimension
import com.nuvio.tv.core.network.RctvServers

/**
 * RCTV: sports logos and photos and channel logos arrive already the size of their tile (a 500-px
 * logo for a 180-px box made the TV decode and keep far more than it shows): ESPN's from ESPN's own
 * resizer, the rest from rctv-imagenes ([RctvServers.shrunkImage]). When that fails, the image is
 * loaded from its own address as before. Other images (posters, backdrops) aren't touched.
 */
class ServerImageInterceptor : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val raw = chain.request.data as? String ?: return chain.proceed()
        val forced = raw.startsWith(RctvServers.SHRINK_MARK)
        val original = if (forced) raw.removePrefix(RctvServers.SHRINK_MARK) else raw
        val width = (chain.size.width as? Dimension.Pixels)?.px ?: 0
        val height = (chain.size.height as? Dimension.Pixels)?.px ?: 0
        val shrunk = RctvServers.shrunkImage(original, width, height, forced = forced)
        if (shrunk == null) {
            return if (forced) chain.withRequest(chain.request.newBuilder().data(original).build()).proceed() else chain.proceed()
        }
        val result = chain.withRequest(chain.request.newBuilder().data(shrunk).build()).proceed()
        if (result !is ErrorResult) return result
        if (shrunk.startsWith(RctvServers.IMAGES)) RctvServers.failed(shrunk)
        return chain.withRequest(chain.request.newBuilder().data(original).build()).proceed()
    }
}
