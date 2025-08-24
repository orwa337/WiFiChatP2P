package com.example.wifichatp2p

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView

/**
 * Custom ArrayAdapter for displaying chat messages in ListView
 * Each message is displayed as a simple text item with "you:" or "them:" prefix
 */
class MessageAdapter(context: Context, messages: ArrayList<String>) :
    ArrayAdapter<String>(context, R.layout.message_item, messages) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.message_item, parent, false)
        
        val messageTextView = view.findViewById<TextView>(R.id.textViewMessageItem)
        val message = getItem(position)
        
        messageTextView.text = message
        
        return view
    }
}
