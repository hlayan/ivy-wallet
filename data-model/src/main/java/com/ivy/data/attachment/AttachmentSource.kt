package com.ivy.data.attachment

enum class AttachmentSource(val code: Int) {
    Local(1), Remote(2);

    companion object {
        fun fromCode(code: Int) = values().first { code == it.code }
    }
}