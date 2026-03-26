package com.markusmaribu.picochat.util

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

object CompressionUtil {

    fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size / 4)
        val buf = ByteArray(1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buf)
            out.write(buf, 0, count)
        }
        deflater.end()
        return out.toByteArray()
    }

    fun inflate(data: ByteArray, expectedSize: Int): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val result = ByteArray(expectedSize)
        inflater.inflate(result)
        inflater.end()
        return result
    }
}
