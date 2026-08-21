/*
 * Copyright (c) 2022, Psiphon Inc.
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.psiphon3;


import android.content.Context;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.paging.PagedListAdapter;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.psiphon3.log.LogEntry;
import com.psiphon3.log.MyLog;
import com.psiphon3.psiphonlibrary.Utils;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Date;

public class LogsListAdapter extends PagedListAdapter<LogEntry, LogsListAdapter.LogEntryViewHolder> {

    private Context context;

    public LogsListAdapter(@NonNull DiffUtil.ItemCallback<LogEntry> diffCallback) {
        super(diffCallback);
    }

    @NonNull
    @Override
    public LogEntryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // onAttachedToRecyclerView is the usual source of this, but it is not a
        // guarantee and the context is handed straight to MyLog below.
        if (context == null) {
            context = parent.getContext();
        }
        View itemView = LayoutInflater.from(parent.getContext()).inflate(R.layout.message_row, parent, false);
        return new LogEntryViewHolder(itemView);
    }

    @Override
    public void onBindViewHolder(@NonNull LogEntryViewHolder holder, int position) {
        LogEntry item = getItem(position);
        if (item == null) {
            // Paging placeholder. Without clearing, the recycled row keeps
            // showing the entry it displayed before it was rebound.
            holder.clear();
            return;
        }
        Date timestamp = new Date(item.getTimestamp());
        String message;
        if (item.isDiagnostic()) {
            try {
                JSONObject jsonObj = new JSONObject(item.getLogJson());
                String msg = jsonObj.getString("msg");
                JSONObject data = jsonObj.optJSONObject("data");
                message = data == null ? msg : msg + ":" + data.toString();
            } catch (JSONException ignored) {
                // A malformed diagnostic entry used to leave the holder entirely
                // unbound, so the row kept the text of whichever entry it was
                // recycled from: the log then displayed a line that was never
                // logged, next to the wrong timestamp. Show the raw payload
                // instead, which is at least true.
                message = item.getLogJson();
            }
        } else {
            message = MyLog.getStatusLogMessageForDisplay(item.getLogJson(), context);
        }
        holder.bind(timestamp, message);
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        context = recyclerView.getContext();
    }

    static class LogEntryViewHolder extends RecyclerView.ViewHolder {
        private final TextView timestampView;
        private final TextView messageView;
        // What a long-press puts on the clipboard, or null when the row is blank.
        @Nullable
        private String copyPayload;

        LogEntryViewHolder(View itemView) {
            super(itemView);
            timestampView = itemView.findViewById(R.id.MessageRow_Timestamp);
            messageView = itemView.findViewById(R.id.MessageRow_Text);
            // A log line is the first thing anyone is asked for when reporting a
            // problem, and there was no way to get one out of the app: the rows
            // are not selectable and there is no share action.
            itemView.setOnLongClickListener(v -> {
                if (TextUtils.isEmpty(copyPayload)) {
                    return false;
                }
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                SkUi.copyToClipboard(v, R.string.app_name, copyPayload);
                return true;
            });
        }

        void bind(@Nullable Date timestamp, @Nullable String msg) {
            String timestampText = timestamp == null ? "" : Utils.getLocalTimeString(timestamp);
            String messageText = msg == null ? "" : msg;
            timestampView.setText(timestampText);
            messageView.setText(messageText);
            copyPayload = TextUtils.isEmpty(messageText)
                    ? null
                    : (timestampText + " " + messageText).trim();
        }

        void clear() {
            timestampView.setText("");
            messageView.setText("");
            copyPayload = null;
        }
    }

    public static class LogEntryComparator extends DiffUtil.ItemCallback<LogEntry> {
        @Override
        public boolean areItemsTheSame(@NonNull LogEntry oldItem,
                                       @NonNull LogEntry newItem) {
            return oldItem.getId() == newItem.getId();
        }

        @Override
        public boolean areContentsTheSame(@NonNull LogEntry oldItem,
                                          @NonNull LogEntry newItem) {
            return oldItem.getLogJson().equals(newItem.getLogJson()) &&
                    (oldItem.getTimestamp() == newItem.getTimestamp());
        }
    }
}
