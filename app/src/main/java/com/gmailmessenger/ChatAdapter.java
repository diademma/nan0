package com.gmailmessenger;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.MessageViewHolder> {

    private static final int TYPE_SENT     = 1;
    private static final int TYPE_RECEIVED = 2;

    // Telegram-like colours
    private static final int COLOR_SENT     = 0xFF2B5278; // blue (my messages)
    private static final int COLOR_RECEIVED = 0xFF1E2B3C; // dark (incoming)
    private static final int COLOR_TEXT     = 0xFFECEDF0;

    private final List<Message> messages;

    public ChatAdapter(List<Message> messages) {
        this.messages = messages;
    }

    @Override
    public int getItemViewType(int position) {
        return messages.get(position).isSent ? TYPE_SENT : TYPE_RECEIVED;
    }

    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_message, parent, false);
        return new MessageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        Message msg = messages.get(position);
        holder.tvMessage.setText(msg.text);

        boolean sent = msg.isSent;
        int bgColor  = sent ? COLOR_SENT : COLOR_RECEIVED;

        // Rounded bubble drawable
        GradientDrawable bubble = new GradientDrawable();
        bubble.setColor(bgColor);
        bubble.setCornerRadii(sent
                ? new float[]{18, 18, 4, 4, 18, 18, 18, 18}   // top-right flat
                : new float[]{4, 4, 18, 18, 18, 18, 18, 18});  // top-left flat
        holder.tvMessage.setBackground(bubble);
        holder.tvMessage.setTextColor(COLOR_TEXT);

        // Align right for sent, left for received
        FrameLayout.LayoutParams params =
                (FrameLayout.LayoutParams) holder.bubbleContainer.getLayoutParams();
        params.gravity = sent ? Gravity.END : Gravity.START;
        holder.bubbleContainer.setLayoutParams(params);
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    static class MessageViewHolder extends RecyclerView.ViewHolder {
        LinearLayout bubbleContainer;
        TextView tvMessage;

        MessageViewHolder(View view) {
            super(view);
            bubbleContainer = view.findViewById(R.id.bubbleContainer);
            tvMessage       = view.findViewById(R.id.tvMessage);
        }
    }
}
