package com.homelab.dashboard

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

class NetworkManagementActivity : AppCompatActivity() {

    private lateinit var recyclerNetworks: RecyclerView
    private lateinit var fabAddNetwork: FloatingActionButton
    private lateinit var tvCurrentWifi: TextView
    
    private lateinit var prefsManager: PreferencesManager
    private val networks = mutableListOf<Network>()
    private lateinit var networkAdapter: NetworkAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network_management)
        
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Network Management"
        
        prefsManager = PreferencesManager(this)
        
        initViews()
        setupUI()
        loadNetworks()
        updateCurrentWifi()
    }
    
    private fun initViews() {
        recyclerNetworks = findViewById(R.id.recyclerNetworks)
        fabAddNetwork = findViewById(R.id.fabAddNetwork)
        tvCurrentWifi = findViewById(R.id.tvCurrentWifi)
    }
    
    private fun setupUI() {
        networkAdapter = NetworkAdapter(networks) { network, action ->
            when (action) {
                NetworkAdapter.Action.EDIT -> editNetwork(network)
                NetworkAdapter.Action.DELETE -> deleteNetwork(network)
            }
        }
        
        recyclerNetworks.apply {
            layoutManager = LinearLayoutManager(this@NetworkManagementActivity)
            adapter = networkAdapter
        }
        
        fabAddNetwork.setOnClickListener {
            showAddNetworkDialog()
        }
    }
    
    private fun loadNetworks() {
        networks.clear()
        networks.addAll(prefsManager.getNetworks())
        
        // Add default local network if empty
        if (networks.isEmpty()) {
            networks.add(Network(
                name = "Home Network",
                wifiSsid = null,
                isLocal = true
            ))
        }
        
        networkAdapter.notifyDataSetChanged()
    }
    
    @Suppress("DEPRECATION")
    private fun updateCurrentWifi() {
        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val wifiInfo = wifiManager.connectionInfo
                val ssid = wifiInfo.ssid.replace("\"", "")
                tvCurrentWifi.text = "Connected to: $ssid"
            } else if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) {
                tvCurrentWifi.text = "Connected via Mobile Data"
            } else {
                tvCurrentWifi.text = "Not connected"
            }
        } catch (e: Exception) {
            tvCurrentWifi.text = "WiFi info unavailable"
        }
    }
    
    private fun showAddNetworkDialog(existingNetwork: Network? = null) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_network, null)
        val etName = dialogView.findViewById<TextInputEditText>(R.id.etNetworkName)
        val etWifiSsid = dialogView.findViewById<TextInputEditText>(R.id.etWifiSsid)
        val switchLocal = dialogView.findViewById<SwitchMaterial>(R.id.switchIsLocal)
        
        existingNetwork?.let {
            etName.setText(it.name)
            etWifiSsid.setText(it.getDisplaySsid())
            switchLocal.isChecked = it.isLocal
        }
        
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()
        
        dialogView.findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            val name = etName.text.toString().trim()
            val wifiSsid = etWifiSsid.text.toString().trim().ifEmpty { null }
            val isLocal = switchLocal.isChecked
            
            if (name.isEmpty()) {
                Toast.makeText(this, "Please enter a name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            if (existingNetwork != null) {
                val position = networks.indexOf(existingNetwork)
                if (position != -1) {
                    networks[position] = Network(
                        id = existingNetwork.id,
                        name = name,
                        wifiSsid = wifiSsid,
                        isLocal = isLocal
                    )
                    networkAdapter.notifyItemChanged(position)
                }
            } else {
                networks.add(Network(name = name, wifiSsid = wifiSsid, isLocal = isLocal))
                networkAdapter.notifyItemInserted(networks.size - 1)
            }
            
            prefsManager.saveNetworks(networks)
            dialog.dismiss()
            Toast.makeText(this, "Network saved", Toast.LENGTH_SHORT).show()
        }
        
        dialogView.findViewById<MaterialButton>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }
        
        dialog.show()
    }
    
    private fun editNetwork(network: Network) {
        showAddNetworkDialog(network)
    }
    
    private fun deleteNetwork(network: Network) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Network")
            .setMessage("Are you sure you want to delete ${network.name}?")
            .setPositiveButton("Delete") { _, _ ->
                val position = networks.indexOf(network)
                if (position != -1) {
                    networks.removeAt(position)
                    networkAdapter.notifyItemRemoved(position)
                    prefsManager.saveNetworks(networks)
                    Toast.makeText(this, "Network deleted", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

class NetworkAdapter(
    private val networks: List<Network>,
    private val onNetworkAction: (Network, Action) -> Unit
) : RecyclerView.Adapter<NetworkAdapter.NetworkViewHolder>() {

    enum class Action {
        EDIT, DELETE
    }

    inner class NetworkViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val networkCard: CardView = itemView.findViewById(R.id.networkCard)
        val tvNetworkName: TextView = itemView.findViewById(R.id.tvNetworkName)
        val tvWifiSsid: TextView = itemView.findViewById(R.id.tvWifiSsid)
        val tvNetworkType: TextView = itemView.findViewById(R.id.tvNetworkType)
        val btnMore: ImageButton = itemView.findViewById(R.id.btnMore)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NetworkViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_network, parent, false)
        return NetworkViewHolder(view)
    }

    override fun onBindViewHolder(holder: NetworkViewHolder, position: Int) {
        val network = networks[position]
        
        holder.tvNetworkName.text = network.name
        holder.tvWifiSsid.text = if (network.hasSsid()) "WiFi: ${network.getDisplaySsid()}" else "Any network"
        holder.tvNetworkType.text = if (network.isLocal) "Local Network (No VPN)" else "Remote Network (VPN Required)"
        
        holder.btnMore.setOnClickListener {
            showOptionsMenu(it, network)
        }
    }

    override fun getItemCount(): Int = networks.size

    private fun showOptionsMenu(view: View, network: Network) {
        val context = view.context
        val options = arrayOf("Edit", "Delete")
        
        MaterialAlertDialogBuilder(context)
            .setTitle(network.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> onNetworkAction(network, Action.EDIT)
                    1 -> onNetworkAction(network, Action.DELETE)
                }
            }
            .show()
    }
}
