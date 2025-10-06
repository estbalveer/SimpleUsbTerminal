package de.kai_morich.simple_usb_terminal

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.hoho.android.usbserial.driver.SerialTimeoutException
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialPort.ControlLine
import com.hoho.android.usbserial.driver.UsbSerialPort.FlowControl
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.XonXoffFilter
import de.kai_morich.simple_usb_terminal.SerialService.SerialBinder
import de.kai_morich.simple_usb_terminal.TextUtil.HexWatcher
import de.kai_morich.simple_usb_terminal.TextUtil.fromHexString
import de.kai_morich.simple_usb_terminal.TextUtil.toCaretString
import java.io.IOException
import java.util.ArrayDeque
import java.util.Arrays

class TerminalFragment : Fragment(), ServiceConnection, SerialListener {
    private enum class Connected {
        False, Pending, True
    }

    companion object {
        private const val refreshInterval = 200 // msec
    }

    private val mainLooper: Handler
    private val broadcastReceiver: BroadcastReceiver
    private var deviceId = 0
    private var portNum = 0
    private var baudRate = 0
    private var usbSerialPort: UsbSerialPort? = null
    private var service: SerialService? = null

    private var receiveText: TextView? = null
    private var sendText: TextView? = null
    private var sendBtn: ImageButton? = null
    private var hexWatcher: HexWatcher? = null

    private var connected = Connected.False
    private var initialStart = true
    private var hexEnabled = false

    enum class SendButtonState {
        Idle, Busy, Disabled
    }

    private val controlLines = ControlLines()
    private var flowControlFilter: XonXoffFilter? = null

    private var pendingNewline = false
    private var newline = TextUtil.newline_crlf

    init {
        mainLooper = Handler(Looper.getMainLooper())
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent) {
                if (Constants.INTENT_ACTION_GRANT_USB == intent.getAction()) {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    connect(granted)
                }
            }
        }
    }

    /*
     * Lifecycle
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        setRetainInstance(true)
        deviceId = requireArguments().getInt("device")
        portNum = requireArguments().getInt("port")
        baudRate = requireArguments().getInt("baud")
    }

    override fun onDestroy() {
        if (connected != Connected.False) disconnect()
        getActivity()?.stopService(Intent(getActivity(), SerialService::class.java))
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        if (service != null) service!!.attach(this)
        else requireActivity().startService(
            Intent(
                getActivity(),
                SerialService::class.java
            )
        ) // prevents service destroy on unbind from recreated activity caused by orientation change

        ContextCompat.registerReceiver(
            requireActivity(),
            broadcastReceiver,
            IntentFilter(Constants.INTENT_ACTION_GRANT_USB),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        requireActivity().unregisterReceiver(broadcastReceiver)
        if (service != null && !requireActivity().isChangingConfigurations()) service!!.detach()
        super.onStop()
    }

    @Suppress("deprecation") // onAttach(context) was added with API 23. onAttach(activity) works for all API versions
    override fun onAttach(activity: Activity) {
        super.onAttach(activity)
        requireActivity().bindService(
            Intent(getActivity(), SerialService::class.java),
            this,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onDetach() {
        try {
            requireActivity().unbindService(this)
        } catch (ignored: Exception) {
        }
        super.onDetach()
    }

    override fun onResume() {
        super.onResume()
        if (initialStart && service != null) {
            initialStart = false
            requireActivity().runOnUiThread(Runnable { this.connect() })
        }
        if (connected == Connected.True) controlLines.start()
    }

    override fun onPause() {
        controlLines.stop()
        super.onPause()
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
        service = (binder as SerialBinder).service
        service!!.attach(this)
        if (initialStart && isResumed()) {
            initialStart = false
            requireActivity().runOnUiThread(Runnable { this.connect() })
        }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        service = null
    }

    /*
     * UI
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_terminal, container, false)
        receiveText =
            view.findViewById<TextView>(R.id.receive_text) // TextView performance decreases with number of spans
        receiveText!!.setTextColor(getResources().getColor(R.color.colorRecieveText)) // set as default color to reduce number of spans
        receiveText!!.setMovementMethod(ScrollingMovementMethod.getInstance())

        sendText = view.findViewById<TextView>(R.id.send_text)
        sendBtn = view.findViewById<ImageButton>(R.id.send_btn)
        hexWatcher = HexWatcher(sendText!!)
        hexWatcher!!.enable(hexEnabled)
        sendText!!.addTextChangedListener(hexWatcher)
        sendText!!.setHint(if (hexEnabled) "HEX mode" else "")

        val sendBtn = view.findViewById<View>(R.id.send_btn)
        sendBtn.setOnClickListener(View.OnClickListener { v: View? ->
            send(
                sendText!!.getText().toString()
            )
        })
        controlLines.onCreateView(view)
        return view
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_terminal, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        menu.findItem(R.id.hex).setChecked(hexEnabled)
        controlLines.onPrepareOptionsMenu(menu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            menu.findItem(R.id.backgroundNotification)
                .setChecked(service != null && service!!.areNotificationsEnabled())
        } else {
            menu.findItem(R.id.backgroundNotification).setChecked(true)
            menu.findItem(R.id.backgroundNotification).setEnabled(false)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.getItemId()
        if (id == R.id.clear) {
            receiveText!!.setText("")
            return true
        } else if (id == R.id.newline) {
            val newlineNames = getResources().getStringArray(R.array.newline_names)
            val newlineValues = getResources().getStringArray(R.array.newline_values)
            val pos = Arrays.asList<String?>(*newlineValues).indexOf(newline)
            val builder = AlertDialog.Builder(getActivity())
            builder.setTitle("Newline")
            builder.setSingleChoiceItems(
                newlineNames,
                pos,
                DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                    newline = newlineValues[which]
                    dialog!!.dismiss()
                })
            builder.create().show()
            return true
        } else if (id == R.id.hex) {
            hexEnabled = !hexEnabled
            sendText!!.setText("")
            hexWatcher!!.enable(hexEnabled)
            sendText!!.setHint(if (hexEnabled) "HEX mode" else "")
            item.setChecked(hexEnabled)
            return true
        } else if (id == R.id.controlLines) {
            item.setChecked(controlLines.showControlLines(!item.isChecked()))
            return true
        } else if (id == R.id.flowControl) {
            controlLines.selectFlowControl()
            return true
        } else if (id == R.id.backgroundNotification) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!service!!.areNotificationsEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissions(arrayOf<String>(Manifest.permission.POST_NOTIFICATIONS), 0)
                } else {
                    showNotificationSettings()
                }
            }
            return true
        } else if (id == R.id.sendBreak) {
            try {
                usbSerialPort!!.setBreak(true)
                Thread.sleep(100)
                status("send BREAK")
                usbSerialPort!!.setBreak(false)
            } catch (e: Exception) {
                status("send BREAK failed: " + e.message)
            }
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /*
     * Serial + UI
     */
    private fun connect(permissionGranted: Boolean? = null) {
        var device: UsbDevice? = null
        val usbManager = requireActivity().getSystemService(Context.USB_SERVICE) as UsbManager
        for (v in usbManager.getDeviceList().values) if (v.getDeviceId() == deviceId) device = v
        if (device == null) {
            status("connection failed: device not found")
            return
        }
        var driver = UsbSerialProber.getDefaultProber().probeDevice(device)
        if (driver == null) {
            driver = CustomProber.customProber.probeDevice(device)
        }
        if (driver == null) {
            status("connection failed: no driver for device")
            return
        }
        if (driver.getPorts().size < portNum) {
            status("connection failed: not enough ports at device")
            return
        }
        usbSerialPort = driver.getPorts().get(portNum)
        val usbConnection = usbManager.openDevice(driver.getDevice())
        if (usbConnection == null && permissionGranted == null && !usbManager.hasPermission(driver.getDevice())) {
            val flags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_MUTABLE else 0
            val intent = Intent(Constants.INTENT_ACTION_GRANT_USB)
            intent.setPackage(requireActivity().getPackageName())
            val usbPermissionIntent = PendingIntent.getBroadcast(getActivity(), 0, intent, flags)
            usbManager.requestPermission(driver.getDevice(), usbPermissionIntent)
            return
        }
        if (usbConnection == null) {
            if (!usbManager.hasPermission(driver.getDevice())) status("connection failed: permission denied")
            else status("connection failed: open failed")
            return
        }

        connected = Connected.Pending
        try {
            usbSerialPort!!.open(usbConnection)
            try {
                usbSerialPort!!.setParameters(
                    baudRate,
                    UsbSerialPort.DATABITS_8,
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE
                )
            } catch (e: UnsupportedOperationException) {
                status("Setting serial parameters failed: " + e.message)
            }
            val socket =
                SerialSocket(requireActivity().getApplicationContext(), usbConnection, usbSerialPort)
            service!!.connect(socket)
            // usb connect is not asynchronous. connect-success and connect-error are returned immediately from socket.connect
            // for consistency to bluetooth/bluetooth-LE app use same SerialListener and SerialService classes
            onSerialConnect()
        } catch (e: Exception) {
            onSerialConnectError(e)
        }
    }

    private fun disconnect() {
        connected = Connected.False
        controlLines.stop()
        service!!.disconnect()
        updateSendBtn(SendButtonState.Idle)
        usbSerialPort = null
    }

    private fun send(str: String) {
        if (connected != Connected.True) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show()
            return
        }
        val msg: String?
        val data: ByteArray
        if (hexEnabled) {
            val sb = StringBuilder()
            TextUtil.toHexString(sb, fromHexString(str))
            TextUtil.toHexString(sb, newline.toByteArray())
            msg = sb.toString()
            data = fromHexString(msg)
        } else {
            msg = str
            data = (str + newline).toByteArray()
        }
        try {
            val spn = SpannableStringBuilder(msg + '\n')
            spn.setSpan(
                ForegroundColorSpan(getResources().getColor(R.color.colorSendText)),
                0,
                spn.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            receiveText!!.append(spn)
            service!!.write(data)
        } catch (e: SerialTimeoutException) { // e.g. writing large data at low baud rate or suspended by flow control
            mainLooper.post(Runnable { sendAgain(data, e.bytesTransferred) })
        } catch (e: Exception) {
            onSerialIoError(e)
        }
    }

    private fun sendAgain(data0: ByteArray, offset: Int) {
        updateSendBtn(if (controlLines.sendAllowed) SendButtonState.Busy else SendButtonState.Disabled)
        if (connected != Connected.True) {
            return
        }
        val data: ByteArray
        if (offset == 0) {
            data = data0
        } else {
            data = ByteArray(data0.size - offset)
            System.arraycopy(data0, offset, data, 0, data.size)
        }
        try {
            service!!.write(data)
        } catch (e: SerialTimeoutException) {
            mainLooper.post(Runnable { sendAgain(data, e.bytesTransferred) })
            return
        } catch (e: Exception) {
            onSerialIoError(e)
        }
        updateSendBtn(if (controlLines.sendAllowed) SendButtonState.Idle else SendButtonState.Disabled)
    }

    private fun receive(datas: ArrayDeque<ByteArray?>) {
        val spn = SpannableStringBuilder()
        for (data in datas) {
            var data = data
            if (flowControlFilter != null) data = flowControlFilter!!.filter(data)
            if (hexEnabled) {
                spn.append(TextUtil.toHexString(data!!)).append('\n')
            } else {
                var msg = kotlin.text.String(data!!)
                if (newline == TextUtil.newline_crlf && msg.length > 0) {
                    // don't show CR as ^M if directly before LF
                    msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf)
                    // special handling if CR and LF come in separate fragments
                    if (pendingNewline && msg.get(0) == '\n') {
                        if (spn.length >= 2) {
                            spn.delete(spn.length - 2, spn.length)
                        } else {
                            val edt = receiveText!!.getEditableText()
                            if (edt != null && edt.length >= 2) edt.delete(
                                edt.length - 2,
                                edt.length
                            )
                        }
                    }
                    pendingNewline = msg.get(msg.length - 1) == '\r'
                }
                spn.append(toCaretString(msg, newline.length != 0))
            }
        }
        receiveText!!.append(spn)
    }

    fun status(str: String?) {
        val spn = SpannableStringBuilder(str + '\n')
        spn.setSpan(
            ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)),
            0,
            spn.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        receiveText!!.append(spn)
    }

    fun updateSendBtn(state: SendButtonState?) {
        sendBtn!!.setEnabled(state == SendButtonState.Idle)
        sendBtn!!.setImageAlpha(if (state == SendButtonState.Idle) 255 else 64)
        sendBtn!!.setImageResource(if (state == SendButtonState.Disabled) R.drawable.ic_block_white_24dp else R.drawable.ic_send_white_24dp)
    }

    /*
     * starting with Android 14, notifications are not shown in notification bar by default when App is in background
     */
    private fun showNotificationSettings() {
        val intent = Intent()
        intent.setAction("android.settings.APP_NOTIFICATION_SETTINGS")
        intent.putExtra("android.provider.extra.APP_PACKAGE", requireActivity().getPackageName())
        startActivity(intent)
    }


    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String?>,
        grantResults: IntArray
    ) {
        if (permissions.contentEquals(arrayOf<String>(Manifest.permission.POST_NOTIFICATIONS)) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !service!!.areNotificationsEnabled()) showNotificationSettings()
    }

    /*
     * SerialListener
     */
    override fun onSerialConnect() {
        status("connected")
        connected = Connected.True
        controlLines.start()
    }

    override fun onSerialConnectError(e: Exception?) {
        status("connection failed: " + e?.message)
        disconnect()
    }

    override fun onSerialRead(data: ByteArray?) {
        val datas = ArrayDeque<ByteArray?>()
        datas.add(data)
        receive(datas)
    }

    override fun onSerialRead(datas: ArrayDeque<ByteArray?>?) {

    }

    override fun onSerialIoError(e: Exception?) {
        status("connection lost: " + e?.message)
        disconnect()
    }

    internal inner class ControlLines {
        private val runnable: Runnable

        private var frame: View? = null
        private var rtsBtn: ToggleButton? = null
        private var ctsBtn: ToggleButton? = null
        private var dtrBtn: ToggleButton? = null
        private var dsrBtn: ToggleButton? = null
        private var cdBtn: ToggleButton? = null
        private var riBtn: ToggleButton? = null

        private var showControlLines = false // show & update control line buttons
        private var flowControl: FlowControl? = FlowControl.NONE // !NONE: update send button state

        var sendAllowed: Boolean = true

        init {
            runnable =
                Runnable { this.run() } // w/o explicit Runnable, a new lambda would be created on each postDelayed, which would not be found again by removeCallbacks
        }

        fun onCreateView(view: View) {
            frame = view.findViewById<View>(R.id.controlLines)
            rtsBtn = view.findViewById<ToggleButton>(R.id.controlLineRts)
            ctsBtn = view.findViewById<ToggleButton>(R.id.controlLineCts)
            dtrBtn = view.findViewById<ToggleButton>(R.id.controlLineDtr)
            dsrBtn = view.findViewById<ToggleButton>(R.id.controlLineDsr)
            cdBtn = view.findViewById<ToggleButton>(R.id.controlLineCd)
            riBtn = view.findViewById<ToggleButton>(R.id.controlLineRi)
            rtsBtn!!.setOnClickListener(View.OnClickListener { v: View? -> this.toggle(v) })
            dtrBtn!!.setOnClickListener(View.OnClickListener { v: View? -> this.toggle(v) })
        }

        fun onPrepareOptionsMenu(menu: Menu) {
            try {
                val scl = usbSerialPort!!.getSupportedControlLines()
                val sfc = usbSerialPort!!.getSupportedFlowControl()
                menu.findItem(R.id.controlLines).setEnabled(!scl.isEmpty())
                menu.findItem(R.id.controlLines).setChecked(showControlLines)
                menu.findItem(R.id.flowControl).setEnabled(sfc.size > 1)
            } catch (ignored: Exception) {
            }
        }

        fun selectFlowControl() {
            val sfc = usbSerialPort!!.getSupportedFlowControl()
            val fc = usbSerialPort!!.getFlowControl()
            val names = ArrayList<String?>()
            val values = ArrayList<FlowControl?>()
            var pos = 0
            names.add("<none>")
            values.add(FlowControl.NONE)
            if (sfc.contains(FlowControl.RTS_CTS)) {
                names.add("RTS/CTS control lines")
                values.add(FlowControl.RTS_CTS)
                if (fc == FlowControl.RTS_CTS) pos = names.size - 1
            }
            if (sfc.contains(FlowControl.DTR_DSR)) {
                names.add("DTR/DSR control lines")
                values.add(FlowControl.DTR_DSR)
                if (fc == FlowControl.DTR_DSR) pos = names.size - 1
            }
            if (sfc.contains(FlowControl.XON_XOFF)) {
                names.add("XON/XOFF characters")
                values.add(FlowControl.XON_XOFF)
                if (fc == FlowControl.XON_XOFF) pos = names.size - 1
            }
            if (sfc.contains(FlowControl.XON_XOFF_INLINE)) {
                names.add("XON/XOFF characters")
                values.add(FlowControl.XON_XOFF_INLINE)
                if (fc == FlowControl.XON_XOFF_INLINE) pos = names.size - 1
            }
            val builder = AlertDialog.Builder(getActivity())
            builder.setTitle("Flow Control")
            builder.setSingleChoiceItems(
                names.toTypedArray<CharSequence?>(),
                pos,
                DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                    dialog!!.dismiss()
                    try {
                        flowControl = values.get(which)
                        usbSerialPort!!.setFlowControl(flowControl)
                        flowControlFilter =
                            if (usbSerialPort!!.getFlowControl() == FlowControl.XON_XOFF_INLINE) XonXoffFilter() else null
                        start()
                    } catch (e: Exception) {
                        status("Set flow control failed: " + e.javaClass.getName() + " " + e.message)
                        flowControl = FlowControl.NONE
                        flowControlFilter = null
                        start()
                    }
                })
            builder.setNegativeButton(
                "Cancel",
                DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int -> dialog!!.dismiss() })
            builder.setNeutralButton(
                "Info",
                DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                    dialog!!.dismiss()
                    val builder2 = AlertDialog.Builder(getActivity())
                    builder2.setTitle("Flow Control")
                        .setMessage("If send is stopped by the external device, the 'Send' button changes to 'Blocked' icon.")
                    builder2.create().show()
                })
            builder.create().show()
        }

        fun showControlLines(show: Boolean): Boolean {
            showControlLines = show
            start()
            return showControlLines
        }

        fun start() {
            if (showControlLines) {
                try {
                    val lines = usbSerialPort!!.getSupportedControlLines()
                    rtsBtn!!.setVisibility(if (lines.contains(ControlLine.RTS)) View.VISIBLE else View.INVISIBLE)
                    ctsBtn!!.setVisibility(if (lines.contains(ControlLine.CTS)) View.VISIBLE else View.INVISIBLE)
                    dtrBtn!!.setVisibility(if (lines.contains(ControlLine.DTR)) View.VISIBLE else View.INVISIBLE)
                    dsrBtn!!.setVisibility(if (lines.contains(ControlLine.DSR)) View.VISIBLE else View.INVISIBLE)
                    cdBtn!!.setVisibility(if (lines.contains(ControlLine.CD)) View.VISIBLE else View.INVISIBLE)
                    riBtn!!.setVisibility(if (lines.contains(ControlLine.RI)) View.VISIBLE else View.INVISIBLE)
                } catch (e: IOException) {
                    showControlLines = false
                    status("getSupportedControlLines() failed: " + e.message)
                }
            }
            frame!!.setVisibility(if (showControlLines) View.VISIBLE else View.GONE)
            if (flowControl == FlowControl.NONE) {
                sendAllowed = true
                updateSendBtn(SendButtonState.Idle)
            }

            mainLooper.removeCallbacks(runnable)
            if (showControlLines || flowControl != FlowControl.NONE) {
                run()
            }
        }

        fun stop() {
            mainLooper.removeCallbacks(runnable)
            sendAllowed = true
            updateSendBtn(SendButtonState.Idle)
            rtsBtn!!.setChecked(false)
            ctsBtn!!.setChecked(false)
            dtrBtn!!.setChecked(false)
            dsrBtn!!.setChecked(false)
            cdBtn!!.setChecked(false)
            riBtn!!.setChecked(false)
        }

        private fun run() {
            if (connected != Connected.True) return
            try {
                if (showControlLines) {
                    val lines = usbSerialPort!!.getControlLines()
                    if (rtsBtn!!.isChecked() != lines.contains(ControlLine.RTS)) rtsBtn!!.setChecked(
                        !rtsBtn!!.isChecked()
                    )
                    if (ctsBtn!!.isChecked() != lines.contains(ControlLine.CTS)) ctsBtn!!.setChecked(
                        !ctsBtn!!.isChecked()
                    )
                    if (dtrBtn!!.isChecked() != lines.contains(ControlLine.DTR)) dtrBtn!!.setChecked(
                        !dtrBtn!!.isChecked()
                    )
                    if (dsrBtn!!.isChecked() != lines.contains(ControlLine.DSR)) dsrBtn!!.setChecked(
                        !dsrBtn!!.isChecked()
                    )
                    if (cdBtn!!.isChecked() != lines.contains(ControlLine.CD)) cdBtn!!.setChecked(!cdBtn!!.isChecked())
                    if (riBtn!!.isChecked() != lines.contains(ControlLine.RI)) riBtn!!.setChecked(!riBtn!!.isChecked())
                }
                if (flowControl != FlowControl.NONE) {
                    when (usbSerialPort!!.getFlowControl()) {
                        FlowControl.DTR_DSR -> sendAllowed = usbSerialPort!!.getDSR()
                        FlowControl.RTS_CTS -> sendAllowed = usbSerialPort!!.getCTS()
                        FlowControl.XON_XOFF -> sendAllowed = usbSerialPort!!.getXON()
                        FlowControl.XON_XOFF_INLINE -> sendAllowed =
                            flowControlFilter != null && flowControlFilter!!.getXON()

                        else -> sendAllowed = true
                    }
                    updateSendBtn(if (sendAllowed) SendButtonState.Idle else SendButtonState.Disabled)
                }
                mainLooper.postDelayed(runnable, refreshInterval.toLong())
            } catch (e: IOException) {
                status("getControlLines() failed: " + e.message + " -> stopped control line refresh")
            }
        }

        private fun toggle(v: View?) {
            val btn = v as ToggleButton
            if (connected != Connected.True) {
                btn.setChecked(!btn.isChecked())
                Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show()
                return
            }
            var ctrl = ""
            try {
                if (btn == rtsBtn) {
                    ctrl = "RTS"
                    usbSerialPort!!.setRTS(btn.isChecked())
                }
                if (btn == dtrBtn) {
                    ctrl = "DTR"
                    usbSerialPort!!.setDTR(btn.isChecked())
                }
            } catch (e: IOException) {
                status("set" + ctrl + " failed: " + e.message)
            }
        }
    }
}
