package dev.synople.glassassistant.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.synople.glassassistant.R
import dev.synople.glassassistant.utils.GlassGesture
import dev.synople.glassassistant.utils.GlassGestureDetector
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class MenuFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var menuAdapter: MenuAdapter
    private var currentSelection = 0

    private val menuItems = listOf(
        MenuItem("Camera Assistant", MenuAction.CAMERA),
        MenuItem("Install APK from QR", MenuAction.QR_APK_INSTALL),
        MenuItem("API Settings", MenuAction.API_SETTINGS),
        MenuItem("Provider Settings", MenuAction.PROVIDER_SETTINGS),
        MenuItem("About", MenuAction.ABOUT),
        MenuItem("Exit", MenuAction.EXIT)
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_menu, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.menuRecyclerView)
        setupRecyclerView()
    }

    private fun setupRecyclerView() {
        menuAdapter = MenuAdapter(menuItems) { position ->
            handleMenuSelection(menuItems[position])
        }

        recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = menuAdapter
        }

        // Highlight first item by default
        menuAdapter.setSelectedPosition(currentSelection)
    }

    private fun handleMenuSelection(item: MenuItem) {
        when (item.action) {
            MenuAction.CAMERA -> {
                findNavController().navigate(R.id.action_menuFragment_to_cameraFragment)
            }
            MenuAction.QR_APK_INSTALL -> {
                findNavController().navigate(R.id.action_menuFragment_to_qrApkInstallerFragment)
            }
            MenuAction.API_SETTINGS -> {
                findNavController().navigate(R.id.action_menuFragment_to_apiKeyFragment)
            }
            MenuAction.PROVIDER_SETTINGS -> {
                findNavController().navigate(R.id.action_menuFragment_to_providerSettingsFragment)
            }
            MenuAction.ABOUT -> {
                // TODO: Show about dialog
            }
            MenuAction.EXIT -> {
                requireActivity().finish()
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onGlassGesture(glassGesture: GlassGesture) {
        when (glassGesture.gesture) {
            GlassGestureDetector.Gesture.TAP -> {
                handleMenuSelection(menuItems[currentSelection])
            }
            GlassGestureDetector.Gesture.SWIPE_FORWARD -> {
                if (currentSelection > 0) {
                    currentSelection--
                    menuAdapter.setSelectedPosition(currentSelection)
                    recyclerView.smoothScrollToPosition(currentSelection)
                }
            }
            GlassGestureDetector.Gesture.SWIPE_BACKWARD -> {
                if (currentSelection < menuItems.size - 1) {
                    currentSelection++
                    menuAdapter.setSelectedPosition(currentSelection)
                    recyclerView.smoothScrollToPosition(currentSelection)
                }
            }
            GlassGestureDetector.Gesture.SWIPE_DOWN -> {
                requireActivity().finish()
            }
            else -> {}
        }
    }

    override fun onStart() {
        super.onStart()
        EventBus.getDefault().register(this)
    }

    override fun onStop() {
        EventBus.getDefault().unregister(this)
        super.onStop()
    }

    data class MenuItem(val title: String, val action: MenuAction)

    enum class MenuAction {
        CAMERA,
        QR_APK_INSTALL,
        API_SETTINGS,
        PROVIDER_SETTINGS,
        ABOUT,
        EXIT
    }

    inner class MenuAdapter(
        private val items: List<MenuItem>,
        private val onItemClick: (Int) -> Unit
    ) : RecyclerView.Adapter<MenuAdapter.ViewHolder>() {

        private var selectedPosition = 0

        fun setSelectedPosition(position: Int) {
            val oldPosition = selectedPosition
            selectedPosition = position
            notifyItemChanged(oldPosition)
            notifyItemChanged(selectedPosition)
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textView: TextView = view.findViewById(android.R.id.text1)

            init {
                view.setOnClickListener {
                    onItemClick(adapterPosition)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_1, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.textView.text = items[position].title

            // Highlight selected item
            if (position == selectedPosition) {
                holder.itemView.setBackgroundColor(0x33FFFFFF)
            } else {
                holder.itemView.setBackgroundColor(0x00000000)
            }
        }

        override fun getItemCount() = items.size
    }
}