package io.gresse.hugo.anecdote;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.support.design.widget.AppBarLayout;
import android.support.design.widget.CoordinatorLayout;
import android.support.design.widget.NavigationView;
import android.support.design.widget.Snackbar;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.view.GravityCompat;
import android.support.v4.view.ViewCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.AppCompatSpinner;
import android.support.v7.widget.PopupMenu;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.transition.Fade;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.Toast;

import com.google.gson.Gson;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

import java.util.ArrayList;
import java.util.List;

import butterknife.Bind;
import butterknife.ButterKnife;
import butterknife.OnItemSelected;
import io.gresse.hugo.anecdote.about.AboutFragment;
import io.gresse.hugo.anecdote.anecdote.ToolbarSpinnerAdapter;
import io.gresse.hugo.anecdote.anecdote.UpdateAnecdoteFragmentEvent;
import io.gresse.hugo.anecdote.anecdote.fragment.WebsiteDialogFragment;
import io.gresse.hugo.anecdote.anecdote.fullscreen.ChangeFullscreenEvent;
import io.gresse.hugo.anecdote.anecdote.fullscreen.FullscreenEvent;
import io.gresse.hugo.anecdote.anecdote.fullscreen.FullscreenFragment;
import io.gresse.hugo.anecdote.anecdote.fullscreen.FullscreenImageFragment;
import io.gresse.hugo.anecdote.anecdote.fullscreen.FullscreenVideoFragment;
import io.gresse.hugo.anecdote.anecdote.list.AnecdoteFragment;
import io.gresse.hugo.anecdote.anecdote.service.AnecdoteService;
import io.gresse.hugo.anecdote.api.WebsiteApiService;
import io.gresse.hugo.anecdote.api.chooser.WebsiteChooserFragment;
import io.gresse.hugo.anecdote.api.event.LoadRemoteWebsiteEvent;
import io.gresse.hugo.anecdote.api.event.OnRemoteWebsiteResponseEvent;
import io.gresse.hugo.anecdote.api.model.Website;
import io.gresse.hugo.anecdote.api.model.WebsitePage;
import io.gresse.hugo.anecdote.event.ChangeTitleEvent;
import io.gresse.hugo.anecdote.event.NetworkConnectivityChangeEvent;
import io.gresse.hugo.anecdote.event.RequestFailedEvent;
import io.gresse.hugo.anecdote.event.ResetAppEvent;
import io.gresse.hugo.anecdote.event.WebsitesChangeEvent;
import io.gresse.hugo.anecdote.setting.SettingsFragment;
import io.gresse.hugo.anecdote.storage.SpStorage;
import io.gresse.hugo.anecdote.tracking.EventTracker;
import io.gresse.hugo.anecdote.util.NetworkConnectivityListener;
import io.gresse.hugo.anecdote.view.ImageTransitionSet;

/**
 *
 */
public class MainActivity extends AppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener, NetworkConnectivityListener.ConnectivityListener {

    private static final String TAG = MainActivity.class.getSimpleName();

    @Bind(R.id.coordinatorLayout)
    public CoordinatorLayout mCoordinatorLayout;

    @Bind(R.id.app_bar_layout)
    public AppBarLayout mAppBarLayout;

    @Bind(R.id.toolbar)
    public Toolbar mToolbar;

    @Bind(R.id.drawer_layout)
    public DrawerLayout mDrawerLayout;

    @Bind(R.id.nav_view)
    public NavigationView mNavigationView;

    @Bind(R.id.toolbarSpinner)
    public Spinner mToolbarSpinner;

    @Bind(R.id.fragment_container)
    public View mFragmentContainer;

    protected ServiceProvider             mServiceProvider;
    protected NetworkConnectivityListener mNetworkConnectivityListener;
    protected List<Website>               mWebsites;
    protected Snackbar                    mSnackbar;
    protected ToolbarSpinnerAdapter       mToolbarSpinnerAdapter;
    protected int                         mToolbarScrollFlags;
    protected CoordinatorLayout.Behavior  mFragmentLayoutBehavior;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (EventTracker.isEventEnable()) {
            new EventTracker(this);
        }
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);

        setSupportActionBar(mToolbar);

        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, mDrawerLayout, mToolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close);
        mDrawerLayout.addDrawerListener(toggle);
        toggle.syncState();

        mNavigationView.setNavigationItemSelectedListener(this);

        // Process migration before getting anything else from shared preferences
        boolean openWebsiteChooserAddMode = SpStorage.migrate(this);

        mServiceProvider = new ServiceProvider(this);
        mWebsites = SpStorage.getWebsites(this);
        mServiceProvider.createAnecdoteService(mWebsites);
        mServiceProvider.register(EventBus.getDefault(), this);

        populateNavigationView(false);

        mNetworkConnectivityListener = new NetworkConnectivityListener();
        mNetworkConnectivityListener.startListening(this, this);

        AppBarLayout.LayoutParams params = (AppBarLayout.LayoutParams) mToolbar.getLayoutParams();
        mToolbarScrollFlags = params.getScrollFlags();

        if (openWebsiteChooserAddMode) {
            changeFragment(WebsiteChooserFragment.newInstance(WebsiteChooserFragment.BUNDLE_MODE_ADD), false, false);
        } else if (SpStorage.isFirstLaunch(this) || mWebsites.isEmpty()) {
            changeFragment(Fragment.instantiate(this, WebsiteChooserFragment.class.getName()), false, false);
        } else {
            changeAnecdoteFragment(mWebsites.get(0), mWebsites.get(0).pages.get(0));
        }

        mToolbarSpinnerAdapter = new ToolbarSpinnerAdapter(getApplicationContext(), "test", new ArrayList<String>());
        mToolbarSpinner.setAdapter(mToolbarSpinnerAdapter);
    }

    @Override
    public void onResume() {
        super.onResume();

        EventBus.getDefault().register(this);
        if (!getWebsiteApiService().isWebsitesDownloaded()) {
            EventBus.getDefault().post(new LoadRemoteWebsiteEvent());
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        EventBus.getDefault().unregister(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        EventTracker.onStart(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        EventTracker.onStop();
    }

    @Override
    protected void onDestroy() {
        mServiceProvider.unregister(EventBus.getDefault());
        mNetworkConnectivityListener.stopListening();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (mDrawerLayout.isDrawerOpen(GravityCompat.START)) {
            mDrawerLayout.closeDrawer(GravityCompat.START);
        } else {
            int fragmentCount = getSupportFragmentManager().getBackStackEntryCount();
            if (fragmentCount == 1) {
                finish();
                return;
            }
            super.onBackPressed();
        }
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_about:
                changeFragment(
                        Fragment.instantiate(this, AboutFragment.class.getName()),
                        true,
                        true);
                return true;
            case R.id.action_settings:
                changeFragment(
                        Fragment.instantiate(this, SettingsFragment.class.getName()),
                        true,
                        true);
                return true;
            case R.id.action_feedback:
                Intent emailIntent = new Intent(Intent.ACTION_SENDTO, Uri.fromParts(
                        "mailto","hugo.gresse@gmail.com", null));
                emailIntent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name) + " feedback");
                startActivity(Intent.createChooser(emailIntent, "Send email..."));
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public boolean onNavigationItemSelected(MenuItem item) {
        switch (item.getGroupId()) {
            case R.id.drawer_group_content:
                for (Website website : mWebsites) {
                    if (website.name.equals(item.getTitle())) {
                        changeAnecdoteFragment(website, website.pages.get(0));

                        // We redisplay the toolbar if it was scrollUp by removing the scrollFlags,
                        // wait a litle and reset the last scrollFlags on it (doing it right after was not working
                        final AppBarLayout.LayoutParams layoutParams = (AppBarLayout.LayoutParams) mToolbar.getLayoutParams();
                        final int scrollFlags = layoutParams.getScrollFlags();
                        layoutParams.setScrollFlags(0);

                        new Handler().postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                layoutParams.setScrollFlags(scrollFlags);
                                mToolbar.setLayoutParams(layoutParams);
                            }
                        }, 100);
                        break;
                    }
                }
                break;
            case R.id.drawer_group_action:
                changeFragment(
                        WebsiteChooserFragment.newInstance(WebsiteChooserFragment.BUNDLE_MODE_ADD),
                        true,
                        true);
                break;
            default:
                Toast.makeText(this, "NavigationGroup not managed", Toast.LENGTH_SHORT).show();
                break;
        }

        mDrawerLayout.closeDrawer(GravityCompat.START);
        return true;
    }

    @OnItemSelected(R.id.toolbarSpinner)
    public void onSpinnerSelected(AppCompatSpinner adapter, View v, int i, long lng) {
        if (mToolbarSpinnerAdapter.getWebsite() != null) {
            changeAnecdoteFragment(mToolbarSpinnerAdapter.getWebsite(), mToolbarSpinnerAdapter.getWebsite().pages.get(i));
        }
    }

    /***************************
     * inner methods
     ***************************/


    /**
     * Change tu current displayed fragment by a new one.
     *
     * @param frag            the new fragment to display
     * @param saveInBackstack if we want the fragment to be in backstack
     * @param animate         if we want a nice animation or not
     */
    private void changeFragment(Fragment frag, boolean saveInBackstack, boolean animate) {
        changeFragment(frag, saveInBackstack, animate, null, null);
    }

    /**
     */
    /**
     * Change tu current displayed fragment by a new one.
     *
     * @param frag            the new fragment to display
     * @param saveInBackstack if we want the fragment to be in backstack
     * @param animate         if we want a nice animation or not
     * @param sharedView      the shared view for the transition
     * @param sharedName      the shared name of the transition
     */
    private void changeFragment(Fragment frag,
                                boolean saveInBackstack,
                                boolean animate,
                                @Nullable View sharedView,
                                @Nullable String sharedName) {
        String log = "changeFragment: ";
        String backStateName = ((Object) frag).getClass().getName();

        if (frag instanceof AnecdoteFragment) {
            backStateName += frag.getArguments().getString(AnecdoteFragment.ARGS_WEBSITE_PAGE_SLUG);
        }

        try {
            FragmentManager manager = getSupportFragmentManager();
            boolean fragmentPopped = manager.popBackStackImmediate(backStateName, 0);

            if (!fragmentPopped && manager.findFragmentByTag(backStateName) == null) { //fragment not in back stack, create it.
                FragmentTransaction transaction = manager.beginTransaction();

                if (animate) {
                    log += " animate";
                    transaction.setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right);
                }
                if (sharedView != null && !TextUtils.isEmpty(sharedName)) {
                    ViewCompat.setTransitionName(sharedView, sharedName);
                    transaction.addSharedElement(sharedView, sharedName);
                }

                transaction.replace(R.id.fragment_container, frag, backStateName);

                if (saveInBackstack) {
                    log += " addToBackTack(" + backStateName + ")";
                    transaction.addToBackStack(backStateName);
                } else {
                    log += " NO addToBackTack(" + backStateName + ")";
                }

                transaction.commit();

                // If some snackbar is display, hide it
                if (mSnackbar != null && mSnackbar.isShownOrQueued()) {
                    mSnackbar.dismiss();
                }

            } else if (!fragmentPopped && manager.findFragmentByTag(backStateName) != null) {
                log += " fragment not popped but finded: " + backStateName;
            } else {
                log += " nothing to do : " + backStateName + " fragmentPopped: " + fragmentPopped;
                // custom effect if fragment is already instanciated
            }
            Log.d(TAG, log);
        } catch (IllegalStateException exception) {
            Log.w(TAG, "Unable to commit fragment, could be activity as been killed in background. " + exception.toString());
        }
    }

    /**
     * Change the current fragment to a new one displaying given website spec
     *
     * @param website the website specification to be displayed in the fragment
     */
    private void changeAnecdoteFragment(Website website, WebsitePage websitePage) {
        Fragment fragment = Fragment.instantiate(this, AnecdoteFragment.class.getName());
        Bundle bundle = new Bundle();
        bundle.putString(AnecdoteFragment.ARGS_WEBSITE_PARENT_SLUG, website.slug);
        bundle.putString(AnecdoteFragment.ARGS_WEBSITE_PAGE_SLUG, websitePage.slug);
        bundle.putString(AnecdoteFragment.ARGS_WEBSITE_NAME, website.name + " " + websitePage.name);
        fragment.setArguments(bundle);
        changeFragment(fragment, true, false);
    }

    private void resetAnecdoteServices() {
        mWebsites = SpStorage.getWebsites(this);

        mServiceProvider.unregister(EventBus.getDefault());
        mServiceProvider.createAnecdoteService(mWebsites);
        mServiceProvider.register(EventBus.getDefault(), this);

        if (!mWebsites.isEmpty()) {
            changeAnecdoteFragment(mWebsites.get(0), mWebsites.get(0).pages.get(0));
        } else {
            changeFragment(Fragment.instantiate(this, WebsiteChooserFragment.class.getName()), false, false);
        }
    }

    private void populateNavigationView(boolean addNewNotification) {
        // Setup NavigationView
        final Menu navigationViewMenu = mNavigationView.getMenu();
        navigationViewMenu.clear();

        for (final Website website : mWebsites) {
            final ImageButton imageButton = (ImageButton) navigationViewMenu
                    .add(R.id.drawer_group_content, Menu.NONE, Menu.NONE, website.name)
                    .setActionView(R.layout.navigationview_actionlayout)
                    .getActionView();

            imageButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    PopupMenu popup = new PopupMenu(MainActivity.this, imageButton);
                    //Inflating the Popup using xml file
                    popup.getMenuInflater()
                            .inflate(R.menu.website_popup, popup.getMenu());

                    //registering popup with OnMenuItemClickListener
                    popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                        public boolean onMenuItemClick(MenuItem item) {

                            switch (item.getItemId()) {
                                case R.id.action_edit:
                                    // Remove edit button for remote website
                                    if (!website.isEditable()) {
                                        Toast.makeText(
                                                MainActivity.this,
                                                R.string.action_website_noteditable_toast,
                                                Toast.LENGTH_SHORT)
                                                .show();
                                        return false;
                                    }

                                    openWebsiteDialog(website);
                                    break;
                                case R.id.action_delete:
                                    SpStorage.deleteWebsite(MainActivity.this, website);
                                    EventBus.getDefault().post(new WebsitesChangeEvent());
                                    EventTracker.trackWebsiteDelete(website.name);
                                    break;
                                case R.id.action_default:
                                    SpStorage.setDefaultWebsite(MainActivity.this, website);
                                    EventBus.getDefault().post(new WebsitesChangeEvent());
                                    EventTracker.trackWebsiteDefault(website.name);
                                    break;
                                default:
                                    Toast.makeText(
                                            MainActivity.this,
                                            R.string.not_implemented,
                                            Toast.LENGTH_SHORT)
                                            .show();
                                    break;
                            }
                            return true;
                        }
                    });

                    popup.show();
                }
            });
        }

        navigationViewMenu.add(R.id.drawer_group_action, Menu.NONE, Menu.NONE, R.string.action_website_add)
                .setIcon(R.drawable.ic_action_content_add);

        if (addNewNotification) {
            navigationViewMenu
                    .add(R.id.drawer_group_action, Menu.NONE, Menu.NONE, R.string.action_website_newwebsite)
                    .setIcon(R.drawable.ic_action_info_outline);
        }

        navigationViewMenu.setGroupCheckable(R.id.drawer_group_content, true, true);
        navigationViewMenu.getItem(0).setChecked(true);
    }

    /**
     * Open a dialog to edit given website or create a new one. One save/add, will fire {@link WebsitesChangeEvent}
     *
     * @param website website to edit
     */
    private void openWebsiteDialog(@Nullable Website website) {
        if (website == null) {
            EventTracker.trackWebsiteEdit("", false);
        } else {
            EventTracker.trackWebsiteEdit(website.name, false);
        }
        FragmentManager fm = getSupportFragmentManager();
        DialogFragment dialogFragment = WebsiteDialogFragment.newInstance(website);
        dialogFragment.show(fm, dialogFragment.getClass().getSimpleName());
    }

    /**
     * Get the anecdote Service corresponding to the given name
     *
     * @param websitePageSlug the website page slug to get the service from
     * @return an anecdote Service, if one is find
     */
    @Nullable
    public AnecdoteService getAnecdoteService(String websitePageSlug) {
        return mServiceProvider.getAnecdoteService(websitePageSlug);
    }

    public WebsiteApiService getWebsiteApiService() {
        return mServiceProvider.getWebsiteApiService();
    }

    /***************************
     * Event
     ***************************/

    @Subscribe
    public void onRequestFailed(final RequestFailedEvent event) {
        if (Configuration.DEBUG) {
            Log.d(TAG, "RequestFailed: " + event.toString());
        }

        //noinspection WrongConstant
        mSnackbar = Snackbar
                .make(mCoordinatorLayout, event.formatErrorMessage(this), Snackbar.LENGTH_INDEFINITE);
        mSnackbar
                .setAction(getString(R.string.action_retry), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        mSnackbar = null;
                        EventBus.getDefault().post(event.originalEvent);
                    }
                })
                .show();
    }

    @Subscribe
    public void changeTitle(ChangeTitleEvent event) {
        if (event.websiteSlug != null) {

            AnecdoteService anecdoteService = getAnecdoteService(event.websiteSlug);
            if (anecdoteService != null) {
                int selectedItem = mToolbarSpinnerAdapter.populate(anecdoteService.getWebsite(), event.websiteSlug);
                mToolbarSpinnerAdapter.notifyDataSetChanged();
                mToolbarSpinner.setSelection(selectedItem);
            }

            /**
             * When the current toolbar is not displaying an AnecdoteFragment and that we want to display an
             * AnecdoteFragment now, we need to set back the scrollflags.
             */
            if (mToolbarSpinner.getVisibility() != View.VISIBLE) {
                AppBarLayout.LayoutParams params = (AppBarLayout.LayoutParams) mToolbar.getLayoutParams();
                params.setScrollFlags(mToolbarScrollFlags);
            }

            mToolbar.setTitle("");
            mToolbarSpinner.setVisibility(View.VISIBLE);

            for (int i = 0; i < mWebsites.size(); i++) {
                if (mWebsites.get(i).slug.equals(event.websiteSlug)) {
                    //mToolbar.setTitle(mWebsites.get(i).name);
                    mNavigationView.getMenu().getItem(i).setChecked(true);
                    break;
                }
            }
        } else {
            if (getSupportActionBar() != null) {
                getSupportActionBar().setDisplayShowTitleEnabled(true);
            }
            mToolbarSpinner.setVisibility(View.GONE);
            mToolbar.setTitle(event.title);
            AppBarLayout.LayoutParams params = (AppBarLayout.LayoutParams) mToolbar.getLayoutParams();
            params.setScrollFlags(0);
        }
    }

    @Subscribe
    public void onWebsitesChangeEvent(WebsitesChangeEvent event) {
        if (event.fromWebsiteChooserOverride) {
            Log.d(TAG, "onWebsitesChangeEvent: removing all fragments");
            // if we come after a restoration or at the first start

            // 1. remove all already added fragment
            FragmentManager fm = getSupportFragmentManager();
            for (int i = 0; i < fm.getBackStackEntryCount(); ++i) {
                fm.popBackStack();
            }
            // 2. remove the WebsiteChooserFragment
            Fragment fragment = fm.findFragmentByTag(WebsiteChooserFragment.class.getName());
            if (fragment != null) {
                FragmentTransaction transaction = fm.beginTransaction();
                transaction.remove(fragment).commit();

            }
        }
        resetAnecdoteServices();
        populateNavigationView(false);
        EventBus.getDefault().post(new UpdateAnecdoteFragmentEvent());
    }

    @Subscribe
    public void onFullscreenEvent(FullscreenEvent event) {
        Fragment fragment;
        Bundle bundle = new Bundle();

        bundle.putString(FullscreenFragment.BUNDLE_ANECDOTE, new Gson().toJson(event.anecdote));
        bundle.putString(FullscreenFragment.BUNDLE_WEBSITENAME, event.websiteName);

        switch (event.type) {
            case FullscreenEvent.TYPE_IMAGE:
                fragment = Fragment.instantiate(this, FullscreenImageFragment.class.getName());

                // Note that we need the API version check here because the actual transition classes (e.g. Fade)
                // are not in the support library and are only available in API 21+. The methods we are calling on the Fragment
                // ARE available in the support library (though they don't do anything on API < 21)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    fragment.setSharedElementEnterTransition(new ImageTransitionSet());
                    fragment.setEnterTransition(new Fade());
                    event.currentFragment.setExitTransition(new Fade());
                    fragment.setSharedElementReturnTransition(new ImageTransitionSet());
                }

                bundle.putString(FullscreenImageFragment.BUNDLE_IMAGEURL, event.anecdote.media);
                fragment.setArguments(bundle);

                changeFragment(fragment, true, false, event.transitionView, event.transitionName);
                break;
            case FullscreenEvent.TYPE_VIDEO:
                fragment = Fragment.instantiate(this, FullscreenVideoFragment.class.getName());

                // Note that we need the API version check here because the actual transition classes (e.g. Fade)
                // are not in the support library and are only available in API 21+. The methods we are calling on the Fragment
                // ARE available in the support library (though they don't do anything on API < 21)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    fragment.setSharedElementEnterTransition(new ImageTransitionSet());
                    fragment.setEnterTransition(new Fade());
                    event.currentFragment.setExitTransition(new Fade());
                    fragment.setSharedElementReturnTransition(new ImageTransitionSet());
                }

                bundle.putString(FullscreenVideoFragment.BUNDLE_VIDEOURL, event.anecdote.media);
                fragment.setArguments(bundle);

                changeFragment(fragment, true, false, event.transitionView, event.transitionName);
                break;
            default:
                Log.d(TAG, "Not managed text type");
                break;
        }
    }

    @Subscribe
    public void changeFullscreenVisibilityEvent(ChangeFullscreenEvent event) {
        if (event.toFullscreen && getSupportActionBar() != null) {
            getSupportActionBar().hide();
            // mAppBarLayout.animate().translationY(-mAppBarLayout.getBottom()).setInterpolator(new AccelerateInterpolator()).start();
        } else if (getSupportActionBar() != null) {
            getSupportActionBar().show();
            // mAppBarLayout.animate().translationY(0).setInterpolator(new AccelerateInterpolator()).start();
        }

        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                mAppBarLayout.getParent().requestLayout();
            }
        }, 600);

        if (event.toFullscreen) {
            // Hide status bar
            this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

            // Remove the layout behavior on fragment container to prevent issue with the fullscreen
            CoordinatorLayout.LayoutParams fragmentContainerLayoutParams =
                    (CoordinatorLayout.LayoutParams) mFragmentContainer.getLayoutParams();
            mFragmentLayoutBehavior = fragmentContainerLayoutParams.getBehavior();
            fragmentContainerLayoutParams.setBehavior(null);
        } else {
            // Show status bar
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

            // Set back the layout behavior on fragment container
            ((CoordinatorLayout.LayoutParams) mFragmentContainer.getLayoutParams())
                    .setBehavior(new AppBarLayout.ScrollingViewBehavior());
        }
    }

    @Subscribe
    public void onRemoteWebsiteLoaded(OnRemoteWebsiteResponseEvent event) {
        if (!event.isSuccessful) return;
        if (mWebsites == null || mWebsites.isEmpty()) return;

        /****
         * Check remote website and local to update local configuration if needed
         */
        Website tempWebsite;
        List<Website> newWebsiteList = new ArrayList<>();
        boolean dataModified = false;
        for (Website website : mWebsites) {
            int index = event.websiteList.lastIndexOf(website);

            // This remote website has not been found locally, skip it
            if (index == -1) {
                continue;
            }
            tempWebsite = event.websiteList.get(index);
            if (!website.isUpToDate(tempWebsite)) {
                dataModified = true;
                newWebsiteList.add(tempWebsite);
            } else {
                newWebsiteList.add(website);
            }
        }

        boolean newNotification = false;
        int savedWebsiteNumber = SpStorage.getSavedRemoteWebsiteNumber(this);
        if (savedWebsiteNumber < event.websiteList.size()) {
            // Display new website notification
            newNotification = true;
            SpStorage.setSavedRemoteWebsiteNumber(this, event.websiteList.size());
        } else if (event.websiteList.size() < savedWebsiteNumber) {
            SpStorage.setSavedRemoteWebsiteNumber(this, event.websiteList.size());
        }

        if (dataModified) {
            Log.i(TAG, "Updating websites configuration");
            mWebsites = newWebsiteList;
            SpStorage.saveWebsites(this, mWebsites);
            resetAnecdoteServices();
            populateNavigationView(newNotification);
            EventBus.getDefault().post(new UpdateAnecdoteFragmentEvent());
        } else if (newNotification) {
            populateNavigationView(true);
        }
    }


    @Subscribe
    public void onWebsiteReset(ResetAppEvent event) {
        changeFragment(
                WebsiteChooserFragment.newInstance(WebsiteChooserFragment.BUNDLE_MODE_RESTORE),
                true,
                true);
    }


    /***************************
     * Implements NetworkConnectivityListener.ConnectivityListener
     ***************************/

    @Override
    public void onConnectivityChange(NetworkConnectivityListener.State state) {
        Log.d(TAG, "onConnectivityChange: " + state);
        EventBus.getDefault().post(new NetworkConnectivityChangeEvent(state));
        if (state == NetworkConnectivityListener.State.CONNECTED && mSnackbar != null && mSnackbar.isShownOrQueued()) {
            mSnackbar.dismiss();
        }
    }
}