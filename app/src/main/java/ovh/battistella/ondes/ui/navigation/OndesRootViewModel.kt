package ovh.battistella.ondes.ui.navigation

import androidx.lifecycle.ViewModel
import ovh.battistella.ondes.common.SnackbarController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class OndesRootViewModel @Inject constructor(
    val snackbar: SnackbarController,
) : ViewModel()
