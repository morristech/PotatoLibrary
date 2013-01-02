package com.eyeem.poll;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.AbsListView.OnScrollListener;
import android.widget.BaseAdapter;
import android.widget.ListView;

import com.emilsjolander.components.StickyListHeaders.StickyListHeadersAdapter;
import com.emilsjolander.components.StickyListHeaders.StickyListHeadersListView;
import com.eyeem.lib.ui.R;
import com.eyeem.poll.Poll;
import com.eyeem.storage.Storage.Subscription;
import com.handmark.pulltorefresh.library.PullToRefreshBase;
import com.handmark.pulltorefresh.library.PullToRefreshListView;

/**
 * ListView for {@link Poll}. Takes care of calling {@link Poll}'s functions,
 * provides all the goodies like pull-to-refresh and infinite scroll. All you
 * need to do is provide {@link Poll} & {@link PollAdapter} instances and call
 * {@link #onPause()} & {@link #onResume()} in Activity's lifecycle. Aditionally
 * you might want to provide no content/connection views using
 * {@link #setNoConnectionView(View)} & {@link #setNoContentView(View)}
 */
@SuppressWarnings("rawtypes")
public class PollListView extends PullToRefreshListView {

   Poll poll;
   BusyIndicator indicator;
   PollAdapter dataAdapter;
   BaseAdapter noContentAdapter;
   BaseAdapter noConnectionAdapter;
   BaseAdapter currentAdapter;
   View hackingEmptyView;
   Runnable customRefreshRunnable;

   /**
    * Problems text displayed in pull to refresh header
    * when there are connection problems
    */
   int problemsLabelId;

   /**
    * Progress text displayed in pull to refresh header
    * when refreshing.
    */
   int progressLabelId;

   public PollListView(Context context) {
      super(context);
      progressLabelId = R.string.default_progress_label;
      problemsLabelId = R.string.default_problems_label;
   }

   public PollListView(Context context, AttributeSet attrs) {
      super(context, attrs);
      loadAttributes(context, attrs);
   }

   private void loadAttributes(Context context, AttributeSet attrs) {
      TypedArray arr = context.obtainStyledAttributes(attrs, R.styleable.PollListView);
      progressLabelId = arr.getResourceId(R.styleable.PollListView_progress_text, R.string.default_progress_label);
      problemsLabelId = arr.getResourceId(R.styleable.PollListView_problems_text, R.string.default_problems_label);
      arr.recycle();
   }

   /**
    * Setter for the refresh indicator
    * @param indicator
    */
   public void setBusyIndicator(BusyIndicator indicator){
      this.indicator = indicator;
   }

   /**
    * Custom Runnable which will be executed on pull to refresh
    * @param refreshRunnable
    */
   public void setCustomRefreshRunnable(Runnable refreshRunnable){
      this.customRefreshRunnable = refreshRunnable;
   }
   
   /**
    * Setter for {@link Poll}
    * @param poll
    */
   public void setPoll(Poll poll) {
      this.poll = poll;
      setOnRefreshListener(refreshListener);
      getRefreshableView().setOnScrollListener(scrollListener);
   }

   /**
    * Call in Activity's or Fragment's onPause
    */
   public void onPause() {
      if (poll != null) {
         poll.list.unsubscribe(subscription);
         if (poll.okToSave()) {
            poll.list.save();
         }
      }
   }

   /**
    * Call in Activity's or Fragment's onResume
    */
   public void onResume() {
      if (poll != null) {
         
         //FIXME: Hotfix to avoid empty lists if objects are deleted by cache and poll is already exhausted 
         poll.exhausted = false;
         
         poll.list.subscribe(subscription);
         if (!poll.list.ensureConsistence() || poll.list.isEmpty()) {
            poll.resetLastTimeUpdated();
            poll.list.load();
         }
         poll.updateIfNecessary(updateListener);
      }
      if (dataAdapter != null && pickAdapter() == dataAdapter) {
         if (currentAdapter != dataAdapter)
            setAdapter(dataAdapter);
         dataAdapter.notifyDataSetChanged();
      }
   }

   /**
    * Set view that will be displayed when there is no content
    * and no connection
    * @param view
    */
   public void setNoConnectionView(View view) {
      noConnectionAdapter = new EmptyViewAdapter(view);
   }

   /**
    * Set view that will be displayed when there is no content
    * @param view
    */
   public void setNoContentView(View view) {
      hackingEmptyView = view;
      noContentAdapter = new EmptyViewAdapter(view);
   }

   /**
    * Setter for {@link PollAdapter}
    * @param adapter
    */
   public void setDataAdapter(PollAdapter adapter) {
      dataAdapter = adapter;
      reloadAdapters();
   }

   private BaseAdapter pickAdapter() {
      if (poll == null) {
         return noContentAdapter;
      }

      if (poll.getState() == Poll.STATE_NO_CONNECTION) {
         return noConnectionAdapter;
      } else if (poll.getState() == Poll.STATE_NO_CONTENT) {
         return noContentAdapter;
      }
      return (BaseAdapter) dataAdapter;
   }

   private void messageWithDelay(String message) {
      PollListView.this.setRefreshingLabel(message);
      postDelayed(new Runnable() {
         @Override
         public void run() {
            PollListView.this.onRefreshComplete();
         }
      }, 2000);
   }

   private OnRefreshListener<ListView> refreshListener = new OnRefreshListener<ListView> () {

      @Override
      public void onRefresh(PullToRefreshBase<ListView> refreshView) {
         if (poll != null) {
            poll.update(updateListener);
            if (customRefreshRunnable != null)
               customRefreshRunnable.run();
         }
      }

   };

   /**
    * Basically sets adapter in busy mode whenever scroll is in
    * FLING mode. This allows to avoid expensive image loading tasks.
    * Also performs calls on Views to refresh images.
    *
    * This allso issues poll calls for older content, aka infinite scroll.
    */
   private OnScrollListener scrollListener = new OnScrollListener() {

      boolean wasFlinging = false;

      @Override
      public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
         if (totalItemCount > 0 && firstVisibleItem > 0 && (totalItemCount - (firstVisibleItem + visibleItemCount)) <= 5) {
            if (poll != null && !poll.exhausted) {
               poll.fetchMore(fetchListener);
            }
         }
      }

      @Override
      public void onScrollStateChanged(AbsListView view, int scrollState) {
         if (currentAdapter != dataAdapter)
            return;

         if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_FLING) {
            dataAdapter.setBusy(true);
            wasFlinging = true;
         } else if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE && wasFlinging) {
            dataAdapter.setBusy(false);
            wasFlinging = false;
            dataAdapter.refreshViews(getRefreshableView());
         } else {
            dataAdapter.setBusy(false);
         }
      }
   };

   private abstract class PollListener implements Poll.Listener {
      @Override
      public String getCurrentId() {
         try {
            // int i = getRefreshableView().getFirstVisiblePosition();
            int i = Math.min(poll.getStorage().size()-1, getRefreshableView().getLastVisiblePosition() - getRefreshableView().getHeaderViewsCount());
            return dataAdapter.idForPosition(i);
         } catch (Throwable t) {
            return null;
         }
      }

      @Override
      public void onTrim(final String currentId) {
         post(new Runnable() {
            @Override
            public void run() {
               int index =  -1;
               index = dataAdapter.positionForId(currentId);
               // index += getRefreshableView().getHeaderViewsCount();
               if (index >= 0)
                  getRefreshableView().setSelection(index);
            }
         });
      }
   };

   private Poll.Listener fetchListener = new PollListener () {

      @Override
      public void onStart() {
         if(indicator != null)
            indicator.setBusyIndicator(true);
      }

      @Override
      public void onError(Throwable error) {
         if(indicator != null)
            indicator.setBusyIndicator(false);
      }

      @Override
      public void onSuccess(int newCount) {
         if (indicator != null)
            indicator.setBusyIndicator(false);
      }

      @Override
      public void onAlreadyPolling() {
         if (indicator != null)
            indicator.setBusyIndicator(true);
      }

      @Override
      public void onExhausted() {
         if (indicator != null)
            indicator.setBusyIndicator(false);
      }

      @Override
      public void onStateChanged(int state) {}
   };

   private Poll.Listener updateListener = new PollListener () {

      @Override
      public void onError(Throwable error) {
         messageWithDelay(getContext().getString(problemsLabelId));
         if (indicator != null)
            indicator.setBusyIndicator(false);
      }

      @Override
      public void onSuccess(int newCount) {
         messageWithDelay(poll.getSuccessMessage(getContext(), newCount));
         if (indicator != null)
            indicator.setBusyIndicator(false);
      }

      @Override
      public void onAlreadyPolling() {
         // NO-OP ?
      }

      @Override
      public void onExhausted() {}

      @Override
      public void onStart() {
         PollListView.this.setRefreshingLabel(getContext().getString(progressLabelId));
         if (indicator != null && poll.getState() == Poll.STATE_UNKNOWN)
            indicator.setBusyIndicator(true);
      }

      @Override
      public void onStateChanged(int state) {
         reloadAdapters();
      }
   };

   private void reloadAdapters() {
      BaseAdapter newAdapter = pickAdapter();
      if (newAdapter == null)
         return;
      if (currentAdapter != newAdapter) {
         if (getRefreshableView() instanceof StickyListHeadersListView) {
            ((StickyListHeadersListView)getRefreshableView()).setAreHeadersSticky(newAdapter instanceof StickyListHeadersAdapter);
         }
         getRefreshableView().setAdapter(currentAdapter = newAdapter);
      } 
      if (newAdapter == noContentAdapter) {
         WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
         Display display = wm.getDefaultDisplay();
         hackingEmptyView.setLayoutParams(new AbsListView.LayoutParams(LayoutParams.MATCH_PARENT, display.getHeight()));
      }
      newAdapter.notifyDataSetChanged();
   }

   Subscription subscription = new Subscription() {
      @Override
      public void onUpdate() {
         post(new Runnable() {
            @Override
            public void run() {
               reloadAdapters();
            }
         });
      }
   };

   /**
    * Update/refresh content
    */
   public void update() {
      poll.update(updateListener);
   }

   public interface PollAdapter extends android.widget.ListAdapter, android.widget.SpinnerAdapter {
      /**
       * Sets adapter in busy state during scroll FLING. Usually this tells
       * adapter there is a fast ongoing scroll and it's better not to allocate
       * any big objects (e.g. bitmaps) to avoid flickering. Aka drawing + memory
       * allocation sucks big time on Android.
       * @param value
       */
      public void setBusy(boolean value);

      public void notifyDataSetChanged();

      /**
       * This is called after list has returned from FLING mode. This gives
       * opportunity to implementing classes to go through ListView hierarchy
       * and refresh/invalidate views.
       * @param lv
       */
      public void refreshViews(ListView lv);

      /**
       * Returns id for the given scroll position
       * @param position
       * @return
       */
      public String idForPosition(int position);

      /**
       * Returns position of the given id.
       * @param id
       * @return
       */
      public int positionForId(String id);
   }

   /**
    * Indicates list view being busy. E.g. spinner when content
    * is being loaded in the background thread.
    */
   public interface BusyIndicator {
      public void setBusyIndicator(boolean busy_flag);
   }
}