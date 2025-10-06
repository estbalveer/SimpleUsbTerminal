package de.kai_morich.simple_usb_terminal

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.ListFragment
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import de.kai_morich.simple_usb_terminal.CustomProber.customProber
import java.util.Arrays
import java.util.Locale

class DevicesFragment : ListFragment() {
    internal class ListItem(var device: UsbDevice, var port: Int, var driver: UsbSerialDriver?)

    private val listItems = ArrayList<ListItem>()
    private var listAdapter: ArrayAdapter<ListItem?>? = null
    private var baudRate = 19200

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        listAdapter = object : ArrayAdapter<ListItem?>(requireActivity(), 0, listItems) {
            override fun getView(position: Int, view: View?, parent: ViewGroup): View {
                var view = view
                val item = listItems.get(position)
                if (view == null) view = requireActivity().getLayoutInflater()
                    .inflate(R.layout.device_list_item, parent, false)
                val text1 = view.findViewById<TextView>(R.id.text1)
                val text2 = view.findViewById<TextView>(R.id.text2)
                val driver = item.driver as UsbSerialDriver
                if (driver == null) text1.setText("<no driver>")
                else if (item.driver!!.getPorts().size == 1) text1.setText(
                    driver.javaClass.getSimpleName().replace("SerialDriver", "")
                )
                else text1.setText(
                    driver.javaClass.getSimpleName()
                        .replace("SerialDriver", "") + ", Port " + item.port
                )
                text2.setText(
                    String.format(
                        Locale.US,
                        "Vendor %04X, Product %04X",
                        item.device.getVendorId(),
                        item.device.getProductId()
                    )
                )
                return view
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        setListAdapter(null)
        val header =
            requireActivity().getLayoutInflater().inflate(R.layout.device_list_header, null, false)
        getListView().addHeaderView(header, null, false)
        setEmptyText("<no USB devices found>")
        (getListView().getEmptyView() as TextView).setTextSize(18f)
        setListAdapter(listAdapter)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_devices, menu)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.getItemId()
        if (id == R.id.refresh) {
            refresh()
            return true
        } else if (id == R.id.baud_rate) {
            val baudRates = getResources().getStringArray(R.array.baud_rates)
            val pos = Arrays.asList<String?>(*baudRates).indexOf(baudRate.toString())
            val builder = AlertDialog.Builder(getActivity())
            builder.setTitle("Baud rate")
            builder.setSingleChoiceItems(baudRates, pos, object : DialogInterface.OnClickListener {
                override fun onClick(dialog: DialogInterface, item: Int) {
                    baudRate = baudRates[item]!!.toInt()
                    dialog.dismiss()
                }
            })
            builder.create().show()
            return true
        } else {
            return super.onOptionsItemSelected(item)
        }
    }

    fun refresh() {
        val usbManager = requireActivity().getSystemService(Context.USB_SERVICE) as UsbManager
        val usbDefaultProber = UsbSerialProber.getDefaultProber()
        val usbCustomProber = customProber
        listItems.clear()
        for (device in usbManager.getDeviceList().values) {
            var driver = usbDefaultProber.probeDevice(device)
            if (driver == null) {
                driver = usbCustomProber.probeDevice(device)
            }
            if (driver != null) {
                for (port in driver.getPorts().indices) listItems.add(
                    ListItem(
                        device,
                        port,
                        driver
                    )
                )
            } else {
                listItems.add(ListItem(device, 0, null))
            }
        }
        listAdapter!!.notifyDataSetChanged()
    }

    override fun onListItemClick(l: ListView, v: View, position: Int, id: Long) {
        val item = listItems.get(position - 1)
        if (item.driver == null) {
            Toast.makeText(getActivity(), "no driver", Toast.LENGTH_SHORT).show()
        } else {
            val args = Bundle()
            args.putInt("device", item.device.getDeviceId())
            args.putInt("port", item.port)
            args.putInt("baud", baudRate)
            val fragment: Fragment = TerminalFragment()
            fragment.setArguments(args)
            getParentFragmentManager().beginTransaction()
                .replace(R.id.fragment, fragment, "terminal").addToBackStack(null).commit()
        }
    }
}
