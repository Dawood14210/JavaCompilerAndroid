package dev.javapad.editor

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

const val DEFAULT_JAVA_SOURCE = """public class Main {
    public static void main(String[] args) {
        System.out.println("Hello, JavaPad!");
    }
}
"""

@HiltViewModel
class EditorViewModel @Inject constructor() : ViewModel() {
    private val mutableSource = MutableStateFlow(DEFAULT_JAVA_SOURCE)
    val source: StateFlow<String> = mutableSource.asStateFlow()

    fun onSourceChanged(source: String) {
        mutableSource.value = source
    }
}
