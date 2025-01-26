package com.homesoft.mediacodectest

import java.nio.ByteBuffer

class NalUnitFinder(private val array:ByteArray) {
    private val END = array.size - PREFIX_LEN

    fun findNext(last:Int):Int {
        var index =
        if (last == -1) {
            0
        } else {
            last + PREFIX_LEN
        }
        while (index < END) {
            if (array[index] == 0.toByte()
                && array[index + 1] == 0.toByte()
                && array[index + 2] == 0.toByte()
                && array[index + 3] == 1.toByte()) {
                return index
            }
            index++
        }
        return array.size
    }

    fun isEnd(index:Int):Boolean {
        return index == array.size
    }

    fun put(byteBuffer: ByteBuffer, start:Int, end:Int):Int {
        val endIndex = if (end == -1) array.size else end
        val length = endIndex - start
        byteBuffer.put(array, start, length)
        byteBuffer.flip()
        return length
    }

    fun getType(index:Int):Int {
        if (index in 0..<END) {
            return getNalType(array[index + PREFIX_LEN])
        }
        return -1
    }

    companion object {
        const val PREFIX_LEN = 4
        const val NAL_TYPE_IDR = 5
        const val NAL_TYPE_SPS = 7

        fun getNalType(byte:Byte):Int {
            return byte.toInt().and(0x1f)
        }
    }
}