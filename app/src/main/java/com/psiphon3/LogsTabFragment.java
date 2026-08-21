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

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import io.reactivex.disposables.CompositeDisposable;

public class LogsTabFragment extends Fragment {
    private LogsListAdapter pagingAdapter;
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private MainActivityViewModel viewModel;
    private int lastItemCount;

    @Nullable
    private View emptyState;
    @Nullable
    private View copyHint;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.logs_tab_layout, container, false);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putInt("lastItemCount", lastItemCount);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (savedInstanceState!= null ) {
            lastItemCount = savedInstanceState.getInt("lastItemCount", 0);
        }

        viewModel = new ViewModelProvider(requireActivity(),
                new ViewModelProvider.AndroidViewModelFactory(requireActivity().getApplication()))
                .get(MainActivityViewModel.class);

        emptyState = view.findViewById(R.id.logsEmptyState);
        copyHint = view.findViewById(R.id.logsCopyHint);

        RecyclerView recyclerView = view.findViewById(R.id.recyclerView);
        LinearLayoutManager layoutManager = new LinearLayoutManager(requireContext());
        layoutManager.setReverseLayout(true);
        layoutManager.setStackFromEnd(true);
        recyclerView.setHasFixedSize(true);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setItemAnimator(null);


        pagingAdapter = new LogsListAdapter(new LogsListAdapter.LogEntryComparator());
        recyclerView.setAdapter(pagingAdapter);

        pagingAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                super.onChanged();
                updateEmptyState();
            }

            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) {
                super.onItemRangeInserted(positionStart, itemCount);
                int currentItemCount = pagingAdapter.getItemCount();
                if (currentItemCount != lastItemCount) {
                    recyclerView.scrollToPosition(0);
                }
                lastItemCount = currentItemCount;
                updateEmptyState();
            }

            @Override
            public void onItemRangeRemoved(int positionStart, int itemCount) {
                super.onItemRangeRemoved(positionStart, itemCount);
                lastItemCount = pagingAdapter.getItemCount();
                updateEmptyState();
            }
        });

        updateEmptyState();
    }

    /**
     * Swap the list for an explanation when there is nothing to show.
     *
     * <p>A fresh install, or the moment right after Clear logs, used to render a
     * completely blank tab: indistinguishable from a screen that failed to load.
     */
    private void updateEmptyState() {
        if (emptyState == null || pagingAdapter == null) {
            return;
        }
        boolean isEmpty = pagingAdapter.getItemCount() == 0;
        emptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        if (copyHint != null) {
            copyHint.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        compositeDisposable.add(viewModel.logsPagedListFlowable()
                .doOnNext(logEntries -> pagingAdapter.submitList(logEntries, this::updateEmptyState))
                .subscribe());
    }

    @Override
    public void onDestroyView() {
        emptyState = null;
        copyHint = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        compositeDisposable.dispose();
    }

    @Override
    public void onPause() {
        super.onPause();
        compositeDisposable.clear();
    }
}
