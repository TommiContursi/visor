package com.macedonga.visor.core.assistant

/** A conversation the glasses announced, by the notification it came from. */
data class Conversation(val key: String, val sender: String, val app: String)

/** The phone's side of replying by voice: the app that posted the message sends the answer. */
interface Messaging {
    /** The conversation the glasses announced last, if it can still be answered. */
    fun lastAnnounced(): Conversation?

    /** Send [text] to [conversation]; null when it went, else why it did not. */
    fun reply(conversation: Conversation, text: String): String?
}
