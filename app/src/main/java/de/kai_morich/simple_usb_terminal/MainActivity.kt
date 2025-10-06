package de.kai_morich.simple_usb_terminal

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.FragmentManager

class MainActivity : AppCompatActivity(), FragmentManager.OnBackStackChangedListener {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val toolbar = findViewById<Toolbar?>(R.id.toolbar)
        setSupportActionBar(toolbar)
        getSupportFragmentManager().addOnBackStackChangedListener(this)
        if (savedInstanceState == null) getSupportFragmentManager().beginTransaction()
            .add(R.id.fragment, DevicesFragment(), "devices").commit()
        else onBackStackChanged()
    }

    override fun onBackStackChanged() {
        getSupportActionBar()!!.setDisplayHomeAsUpEnabled(getSupportFragmentManager().getBackStackEntryCount() > 0)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    override fun onNewIntent(intent: Intent) {
        if ("android.hardware.usb.action.USB_DEVICE_ATTACHED" == intent.getAction()) {
            val terminal =
                getSupportFragmentManager().findFragmentByTag("terminal") as TerminalFragment?
            if (terminal != null) terminal.status("USB device detected")
        }
        super.onNewIntent(intent)
    }
}
