package com.remino.keyboard

import android.content.Context
import android.content.res.XmlResourceParser
import androidx.annotation.XmlRes
import org.xmlpull.v1.XmlPullParser

/**
 * Reads res/xml/qwerty.xml and res/xml/symbols.xml.
 *
 * Attributes are custom (res-auto) so the key maps stay declarative and the
 * canvas renderer never needs a per-key View tree.
 */
object XmlLayoutLoader {

    private const val NS = "http://schemas.android.com/apk/res-auto"

    data class Row(val keys: List<Key>, val numberRowOnly: Boolean = false)

    fun load(context: Context, @XmlRes xmlRes: Int): List<Row> {
        val parser: XmlResourceParser = context.resources.getXml(xmlRes)
        val rows = ArrayList<Row>()
        var current: ArrayList<Key>? = null
        var currentNumberRow = false
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "Row" -> {
                            current = ArrayList()
                            currentNumberRow = parser.getAttributeBooleanValue(NS, "numberRow", false)
                        }
                        "Key" -> {
                            val key = parseKey(context, parser)
                            if (key != null) current?.add(key)
                        }
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "Row") {
                        val keys = current
                        if (keys != null && keys.isNotEmpty()) {
                            rows.add(Row(keys, currentNumberRow))
                        }
                        current = null
                        currentNumberRow = false
                    }
                }
                event = parser.next()
            }
        } finally {
            parser.close()
        }
        return rows
    }

    private fun attr(context: Context, parser: XmlResourceParser, name: String): String? {
        val res = parser.getAttributeResourceValue(NS, name, 0)
        return if (res != 0) context.getString(res) else parser.getAttributeValue(NS, name)
    }

    private fun parseKey(context: Context, parser: XmlResourceParser): Key? {
        val type = when (parser.getAttributeValue(NS, "keyType")) {
            "spacer" -> KeyType.SPACER
            "shift" -> KeyType.SHIFT
            "delete" -> KeyType.BACKSPACE
            "symbols" -> KeyType.SYMBOLS
            "letters" -> KeyType.LETTERS
            "globe" -> KeyType.GLOBE
            "comma" -> KeyType.COMMA
            "period" -> KeyType.PERIOD
            "emoji" -> KeyType.EMOJI
            "space" -> KeyType.SPACE
            "enter" -> KeyType.ENTER
            else -> KeyType.CHAR
        }
        val label = attr(context, parser, "keyLabel").orEmpty()
        val code = attr(context, parser, "keyCode") ?: label
        val weight = parser.getAttributeFloatValue(NS, "keyWeight", 1f)
        if (weight <= 0f) return null
        val longPress = attr(context, parser, "keyLongPress")
            ?.map { it.toString() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        val tinted = parser.getAttributeBooleanValue(NS, "keyTinted", false)
        return Key(
            label = label,
            type = type,
            weight = weight,
            tinted = tinted,
            code = code,
            longPress = longPress,
        )
    }
}
