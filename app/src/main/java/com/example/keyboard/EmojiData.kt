package com.example.keyboard

import android.content.Context
import org.json.JSONArray

/** Built-in emoji (no skin tones or joined sequences, so they work on older phones). */
object EmojiData {
    class Category(val name: String, val emojis: List<String>)

    // Single characters that need the "emoji style" marker to draw in color.
    private val extraText = setOf(
        0x1F590, 0x1F5A5, 0x1F5A8, 0x1F5B1, 0x1F56F, 0x1F5DD, 0x1F3D4, 0x1F3D5, 0x1F3D6,
        0x1F3DD, 0x1F3DC, 0x1F6F3, 0x1F5FA, 0x1F6E0, 0x1F396, 0x1F336, 0x1F321, 0x1F3CE,
        0x1F3CD, 0x1F441,
    )

    fun present(e: String): String {
        if (e.isEmpty() || e.contains('\uFE0F')) return e
        val cp = e.codePointAt(0)
        val single = e.length == Character.charCount(cp)
        return if (single && (cp in 0x2100..0x2BFF || cp in extraText)) e + "\uFE0F" else e
    }

    private fun list(s: String): List<String> =
        s.trim().split(" ").filter { it.isNotEmpty() }.map { present(it) }

    val categories: List<Category> = listOf(
        Category(
            "Smileys",
            list(
                "😀 😃 😄 😁 😆 😅 😂 🤣 😊 😇 🙂 🙃 😉 😌 😍 🥰 😘 😗 😋 😛 😜 🤪 😎 🤩 🥳 😏 😒 😞 😔 😟 😕 🙁 " +
                    "😣 😖 😫 😩 🥺 😢 😭 😤 😠 😡 🤬 🤯 😳 🥵 🥶 😱 😨 😰 😥 😓 🤗 🤔 🤭 🤫 😶 😐 😑 😬 🙄 😯 😲 " +
                    "🥱 😴 🤤 😪 😵 🤐 🥴 🤢 🤮 🤧 😷 🤒 🤕 🤑 🤠 😈 👿 🤡 💩 👻 💀 👽 🤖",
            ),
        ),
        Category(
            "People",
            list(
                "👋 🤚 ✋ 🖖 👌 🤏 ✌ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝ 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 👐 🤲 🤝 🙏 ✍ 💅 🤳 💪 " +
                    "🧠 👀 👁 👅 👄 👶 👦 👧 👨 👩 🧑 👴 👵 🙋 🙆 🙅 🤦 🤷 💁 🙇",
            ),
        ),
        Category(
            "Animals & nature",
            list(
                "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🙈 🙉 🙊 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🦇 🐺 🐗 🐴 🦄 🐝 🐛 " +
                    "🦋 🐌 🐞 🐜 🐢 🐍 🦎 🐙 🦑 🦐 🦀 🐡 🐠 🐟 🐬 🐳 🐋 🦈 🐊 🐅 🐆 🦓 🦍 🐘 🦒 🐪 🌵 🌴 🌲 🌳 🌱 " +
                    "🌿 🍀 🌸 🌹 🌻 🌼 🌷 🍁 🍂 🍃",
            ),
        ),
        Category(
            "Food & drink",
            list(
                "🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥕 🌽 🌶 🥔 🍠 🥐 🍞 🥖 🧀 🥚 🍳 🥞 " +
                    "🥓 🥩 🍗 🍖 🌭 🍔 🍟 🍕 🥪 🌮 🌯 🥗 🍝 🍜 🍲 🍛 🍣 🍱 🥟 🍤 🍙 🍚 🍘 🍥 🍢 🍡 🍧 🍨 🍦 🍰 🎂 " +
                    "🍮 🍭 🍬 🍫 🍿 🍩 🍪 ☕ 🍵 🥤 🍺 🍻 🥂 🍷 🍹",
            ),
        ),
        Category(
            "Activities",
            list(
                "⚽ 🏀 🏈 ⚾ 🎾 🏐 🏉 🎱 🏓 🏸 🥅 🏒 🏏 ⛳ 🏹 🎣 🥊 🥋 🎽 ⛸ 🎿 🏂 🏆 🥇 🥈 🥉 🏅 🎖 🎮 🎯 🎲 🎰 " +
                    "🎳 🎭 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🎷 🎺 🎸 🎻",
            ),
        ),
        Category(
            "Travel & places",
            list(
                "🚗 🚕 🚙 🚌 🚎 🏎 🚓 🚑 🚒 🚐 🚚 🚛 🚜 🏍 🛵 🚲 🛴 🚨 🚔 🚍 🚘 ✈ 🛫 🛬 🚀 🛸 🚁 ⛵ 🚤 🛳 ⚓ 🚂 " +
                    "🚆 🚇 🚊 🗺 🗽 🗼 🏰 🏯 🏟 🎡 🎢 🎠 ⛲ 🏖 🏝 🏜 🌋 🏔 ⛰ 🏕 🏠 🏡 🏢 🏥 🏦 🏨 🏪 🏫 🌅 🌄 🌇 🌃 🌉 🌌",
            ),
        ),
        Category(
            "Objects",
            list(
                "⌚ 📱 💻 ⌨ 🖥 🖨 🖱 💽 💾 💿 📷 📹 🎥 📞 ☎ 📺 📻 ⏰ 🔋 🔌 💡 🔦 🕯 💸 💵 💴 💶 💷 💰 💳 💎 🔧 " +
                    "🔨 ⚒ 🛠 ⛏ 🔩 ⚙ 💣 🔪 🛡 🔮 📿 💈 🔭 🔬 💊 💉 🌡 🧹 🧺 🔑 🗝 🚪 🛏 🚽 🚿 🛁 📦 📫 ✉ 📝 📁 " +
                    "📅 📌 📎 ✂ 🔒 🔓 📚 📖",
            ),
        ),
        Category(
            "Symbols",
            list(
                "❤ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣ 💕 💞 💓 💗 💖 💘 💝 ✨ ⭐ 🌟 💫 ⚡ 🔥 💥 ☀ 🌙 ☁ ❄ 🌈 ✅ ❌ " +
                    "❓ ❗ ➕ ➖ 💯 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🔔 🎵 🎶 ♻ ⚠ 🚫 🔞 ➡ ⬅ ⬆ ⬇",
            ),
        ),
    )
}

/** Emoji you used most recently (stored on the device only). */
class EmojiRecents(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("emoji_recents", Context.MODE_PRIVATE)

    fun load(): List<String> = prefs.getString(KEY_LIST, "").orEmpty().split(" ").filter { it.isNotEmpty() }

    fun add(emoji: String) {
        val list = load().toMutableList()
        list.remove(emoji)
        list.add(0, emoji)
        while (list.size > MAX) list.removeAt(list.size - 1)
        prefs.edit().putString(KEY_LIST, list.joinToString(" ")).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun exportData(): JSONArray {
        val arr = JSONArray()
        for (e in load()) arr.put(e)
        return arr
    }

    fun importData(arr: JSONArray) {
        val list = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val e = arr.optString(i, "")
            if (e.isNotEmpty() && !e.contains(' ')) list.add(e)
        }
        prefs.edit().putString(KEY_LIST, list.take(MAX).joinToString(" ")).apply()
    }

    private companion object {
        const val KEY_LIST = "list"
        const val MAX = 32
    }
}
