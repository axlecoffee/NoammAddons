package noammaddons.features.impl.misc

import noammaddons.features.impl.DevOptions
import noammaddons.NoammAddons.Companion.MOD_ID
import noammaddons.NoammAddons.Companion.MOD_NAME
import noammaddons.NoammAddons.Companion.MOD_VERSION
import org.apache.commons.io.IOUtils.*
import org.lwjgl.opengl.Display.*
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import javax.imageio.ImageIO

object ClientBranding {
    fun setCustomIcon() {
        if (! DevOptions.clientBranding) return;

        var icon16: InputStream? = null
        var icon32: InputStream? = null

        try {
            icon16 = this.javaClass.getResourceAsStream("/assets/$MOD_ID/menu/icons/logo-64x.png")
            icon32 = this.javaClass.getResourceAsStream("/assets/$MOD_ID/menu/icons/logo-32x.png")

            if (icon16 != null && icon32 != null) {
                val icons = arrayOf(
                    readImageToBuffer(icon16),
                    readImageToBuffer(icon32)
                )
                setIcon(icons)
            }
        }
        catch (e: IOException) {
            e.printStackTrace()
        }
        finally {
            closeQuietly(icon16)
            closeQuietly(icon32)
        }
    }

    fun setCustomTitle() {
        if (! DevOptions.clientBranding) return;
        setTitle("$MOD_NAME - $MOD_VERSION    ||   Noamm is the best!")
    }

    private fun readImageToBuffer(imageStream: InputStream): ByteBuffer {
        val image = ImageIO.read(imageStream)
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)

        val buffer = ByteBuffer.allocate(4 * pixels.size)
        for (pixel in pixels) {
            buffer.put(((pixel shr 16) and 0xFF).toByte())  // Red
            buffer.put(((pixel shr 8) and 0xFF).toByte())   // Green
            buffer.put((pixel and 0xFF).toByte())           // Blue
            buffer.put(((pixel shr 24) and 0xFF).toByte())  // Alpha
        }

        buffer.flip()
        return buffer
    }
}