package dev.dukendev.watchface.binaryradar.editor

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.dukendev.watchface.binaryradar.data.watchface.ColorStyleIdAndResourceIds
import dev.dukendev.watchface.binaryradar.databinding.ActivityWatchFaceConfigBinding
import dev.dukendev.watchface.binaryradar.utils.LEFT_COMPLICATION_ID
import dev.dukendev.watchface.binaryradar.utils.RIGHT_COMPLICATION_ID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Allows user to edit certain parts of the watch face (color style, ticks displayed, minute arm
 * length) by using the [WatchFaceConfigStateHolder]. (All widgets are disabled until data is
 * loaded.)
 */
class WatchFaceConfigActivity : ComponentActivity() {
    private val stateHolder: WatchFaceConfigStateHolder by lazy {
        WatchFaceConfigStateHolder(
            lifecycleScope,
            this@WatchFaceConfigActivity
        )
    }

    private lateinit var binding: ActivityWatchFaceConfigBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate()")

        binding = ActivityWatchFaceConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Disable widgets until data loads and values are set.
        binding.colorStylePickerButton.isEnabled = false


        lifecycleScope.launch(Dispatchers.Main.immediate) {
            stateHolder.uiState
                .collect { uiState: WatchFaceConfigStateHolder.EditWatchFaceUiState ->
                    when (uiState) {
                        is WatchFaceConfigStateHolder.EditWatchFaceUiState.Loading -> {
                            Log.d(TAG, "StateFlow Loading: ${uiState.message}")
                        }

                        is WatchFaceConfigStateHolder.EditWatchFaceUiState.Success -> {
                            Log.d(TAG, "StateFlow Success.")
                            updateWatchFaceEditorPreview(uiState.userStylesAndPreview)
                        }

                        is WatchFaceConfigStateHolder.EditWatchFaceUiState.Error -> {
                            Log.e(TAG, "Flow error: ${uiState.exception}")
                        }
                    }
                }
        }
    }

    private fun updateWatchFaceEditorPreview(
        userStylesAndPreview: WatchFaceConfigStateHolder.UserStylesAndPreview
    ) {
        Log.d(TAG, "updateWatchFacePreview: $userStylesAndPreview")

        val colorStyleId: String = userStylesAndPreview.colorStyleId
        Log.d(TAG, "\tselected color style: $colorStyleId")

        binding.preview.watchFaceBackground.setImageBitmap(userStylesAndPreview.previewImage)

        enabledWidgets()
    }

    private fun enabledWidgets() {
        binding.colorStylePickerButton.isEnabled = true
    }

    fun onClickColorStylePickerButton(view: View) {
        Log.d(TAG, "onClickColorStylePickerButton() $view")

        // Selects a random color style from list.
        val colorStyleIdAndResourceIdsList = enumValues<ColorStyleIdAndResourceIds>()
        val newColorStyle: ColorStyleIdAndResourceIds = colorStyleIdAndResourceIdsList.random()
        Toast.makeText(this, "Coming soon!", Toast.LENGTH_SHORT).show()
        stateHolder.setColorStyle(newColorStyle.id)
    }

    fun onClickLeftComplicationButton(view: View) {
        Log.d(TAG, "onClickLeftComplicationButton() $view")
        stateHolder.setComplication(LEFT_COMPLICATION_ID)
    }

    fun onClickRightComplicationButton(view: View) {
        Log.d(TAG, "onClickRightComplicationButton() $view")
        stateHolder.setComplication(RIGHT_COMPLICATION_ID)
    }


    companion object {
        const val TAG = "WatchFaceConfigActivity"
    }
}
