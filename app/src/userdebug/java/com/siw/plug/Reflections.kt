package com.siw.plug

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.siw.plug.logic.plugApplication
import com.siw.plug.logic.ui.BaseActivity

class Reflections : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_reflections)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        val r = findViewById<RecyclerView>(R.id.recyclerview)
        CoroutineScope(Dispatchers.Default).launch {
            plugApplication.reader.refresh()
            withContext(Dispatchers.Main) {
                r.adapter =
                    ReflectionAdapter(plugApplication.reader, null, { r.adapter = it }, false)
            }
        }
    }
}