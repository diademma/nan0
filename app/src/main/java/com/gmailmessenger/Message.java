package com.gmailmessenger;

public class Message {
    public final String text;
    public final boolean isSent;   // true = я отправил, false = получил
    public final String threadId;  // Gmail thread ID (для удаления прочитанных)

    public Message(String text, boolean isSent, String threadId) {
        this.text = text;
        this.isSent = isSent;
        this.threadId = threadId;
    }

    public Message(String text, boolean isSent) {
        this(text, isSent, null);
    }
}
