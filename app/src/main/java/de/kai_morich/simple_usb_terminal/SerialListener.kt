package de.kai_morich.simple_usb_terminal

import java.util.ArrayDeque

interface SerialListener {
    fun onSerialConnect()
    fun onSerialConnectError(e: Exception?)
    fun onSerialRead(data: ByteArray?) // socket -> service
    fun onSerialRead(datas: ArrayDeque<ByteArray?>?) // service -> UI thread
    fun onSerialIoError(e: Exception?)
}
