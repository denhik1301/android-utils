package me.aap.utils.ui.activity;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources.Theme;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.Toast;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import java.util.Collection;
import java.util.LinkedList;

import me.aap.utils.BuildConfig;
import me.aap.utils.app.App;
import me.aap.utils.event.EventBroadcaster;
import me.aap.utils.function.Function;
import me.aap.utils.function.Supplier;
import me.aap.utils.ui.fragment.ActivityFragment;
import me.aap.utils.ui.menu.OverlayMenu;
import me.aap.utils.ui.menu.OverlayMenuView;
import me.aap.utils.ui.view.NavBarView;
import me.aap.utils.ui.view.ToolBarView;

import static android.view.View.SYSTEM_UI_FLAG_FULLSCREEN;
import static android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
import static android.view.View.SYSTEM_UI_FLAG_IMMERSIVE;
import static android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
import static android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
import static android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
import static android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
import static android.view.View.SYSTEM_UI_FLAG_LOW_PROFILE;
import static android.view.View.SYSTEM_UI_FLAG_VISIBLE;
import static me.aap.utils.collection.CollectionUtils.forEach;
import static me.aap.utils.ui.UiUtils.ID_NULL;
import static me.aap.utils.ui.activity.ActivityListener.ACTIVITY_DESTROY;
import static me.aap.utils.ui.activity.ActivityListener.ACTIVITY_FINISH;
import static me.aap.utils.ui.activity.ActivityListener.FRAGMENT_CHANGED;

/**
 * @author Andrey Pavlenko
 */
public abstract class ActivityDelegate extends Fragment implements EventBroadcaster<ActivityListener> {
	private static Function<Context, ActivityDelegate> contextToDelegate;
	private static final int FULLSCREEN_FLAGS = SYSTEM_UI_FLAG_LAYOUT_STABLE |
			SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
			SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
			SYSTEM_UI_FLAG_LOW_PROFILE |
			SYSTEM_UI_FLAG_FULLSCREEN |
			SYSTEM_UI_FLAG_HIDE_NAVIGATION |
			SYSTEM_UI_FLAG_IMMERSIVE |
			SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
	private final Collection<ListenerRef<ActivityListener>> listeners = new LinkedList<>();
	private AppActivity activity;
	private OverlayMenu activeMenu;
	private boolean recreate;
	private boolean fullScreen;
	private boolean backPressed;
	private int activeFragmentId = ID_NULL;
	private int activeNavItemId = ID_NULL;

	public ActivityDelegate() {
		this.recreate = true;
	}

	protected abstract ActivityFragment createFragment(int id);

	@IdRes
	protected abstract int getFrameContainerId();

	@StringRes
	protected abstract int getExitMsg();

	@SuppressWarnings("unchecked")
	public static <D extends ActivityDelegate> D create(Supplier<D> constructor, AppActivity activity) {
		FragmentManager fm = activity.getSupportFragmentManager();
		ActivityDelegate delegate = (ActivityDelegate) fm.findFragmentByTag("ActivityDelegate");

		if ((delegate == null) || delegate.recreate) {
			delegate = constructor.get();
			delegate.recreate = false;
			delegate.activity = activity;
			FragmentTransaction tr = fm.beginTransaction();
			forEach(fm.getFragments(), tr::remove);
			tr.add(delegate, "ActivityDelegate");
			tr.commit();
		} else {
			delegate.activity = activity;
		}

		return (D) delegate;
	}

	public static void setContextToDelegate(Function<Context, ActivityDelegate> contextToDelegate) {
		ActivityDelegate.contextToDelegate = contextToDelegate;
	}

	public static ActivityDelegate get(Context ctx) {
		if (ctx instanceof AppActivity) {
			return ((AppActivity) ctx).getActivityDelegate();
		} else if (ctx instanceof ContextWrapper) {
			do {
				ctx = ((ContextWrapper) ctx).getBaseContext();
				if (ctx instanceof AppActivity) return ((AppActivity) ctx).getActivityDelegate();
			} while (ctx instanceof ContextWrapper);
		}

		if (contextToDelegate != null) {
			return contextToDelegate.apply(ctx);
		} else {
			throw new IllegalArgumentException("Unsupported context: " + ctx);
		}
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setRetainInstance(true);
	}

	protected void onActivityCreate(Bundle savedInstanceState) {
		setTheme();
	}

	protected void onActivityStart() {
	}

	protected void onActivityResume() {
		setSystemUiVisibility();
	}

	protected void onActivityPause() {
	}

	@SuppressWarnings("unused")
	protected void onActivitySaveInstanceState(@NonNull Bundle outState) {
	}

	protected void onActivityStop() {
	}

	protected void onActivityDestroy() {
		fireBroadcastEvent(ACTIVITY_DESTROY);

		if (BuildConfig.DEBUG) {
			removeBroadcastListeners(l -> {
				if (l instanceof View) {
					throw new IllegalStateException("View has not removed from activity listeners: " + l);
				}
				return false;
			});
		}
	}

	protected void onActivityFinish() {
	}

	public void finish() {
		fireBroadcastEvent(ACTIVITY_FINISH);
		getAppActivity().finish();
	}

	public AppActivity getAppActivity() {
		return activity;
	}

	public Theme getTheme() {
		return getAppActivity().getTheme();
	}

	@Override
	public Context getContext() {
		return getAppActivity().getContext();
	}

	public Window getWindow() {
		return getAppActivity().getWindow();
	}

	public View getCurrentFocus() {
		return getAppActivity().getCurrentFocus();
	}

	public <T extends View> T findViewById(@IdRes int id) {
		return getAppActivity().findViewById(id);
	}

	@NonNull
	public FragmentManager getSupportFragmentManager() {
		return getAppActivity().getSupportFragmentManager();
	}

	public NavBarView getNavBar() {
		return null;
	}

	public ToolBarView getToolBar() {
		return null;
	}

	public int getActiveFragmentId() {
		return activeFragmentId;
	}

	@Nullable
	public ActivityFragment getActiveFragment() {
		int id = getActiveFragmentId();
		for (Fragment f : getSupportFragmentManager().getFragments()) {
			if (f instanceof ActivityFragment) {
				ActivityFragment af = (ActivityFragment) f;
				if (af.getFragmentId() == id) return af;
			}
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	public <F extends ActivityFragment> F showFragment(@IdRes int id) {
		int activeId = getActiveFragmentId();
		if (id == activeId) return (F) getActiveFragment();

		FragmentManager fm = getSupportFragmentManager();
		FragmentTransaction tr = fm.beginTransaction();
		ActivityFragment switchingFrom = null;
		ActivityFragment switchingTo = null;

		for (Fragment f : fm.getFragments()) {
			if (!(f instanceof ActivityFragment)) continue;

			ActivityFragment af = (ActivityFragment) f;
			int afid = af.getFragmentId();

			if (afid == id) {
				tr.show(f);
				switchingTo = af;
			} else if (afid == activeId) {
				switchingFrom = af;
				tr.hide(f);
			} else {
				tr.hide(f);
			}
		}

		if (switchingTo == null) {
			switchingTo = createFragment(id);
			tr.add(getFrameContainerId(), switchingTo);
			tr.show(switchingTo);
		}

		activeFragmentId = switchingTo.getFragmentId();
		if (switchingFrom != null) switchingFrom.switchingTo(switchingTo);
		switchingTo.switchingFrom(switchingFrom);
		tr.commitAllowingStateLoss();
		postBroadcastEvent(FRAGMENT_CHANGED);
		return (F) switchingTo;
	}

	public int getActiveNavItemId() {
		return activeNavItemId;
	}

	public void setActiveNavItemId(int activeNavItemId) {
		this.activeNavItemId = activeNavItemId;
	}

	public boolean isRootPage() {
		ActivityFragment f = getActiveFragment();
		return (f != null) && f.isRootPage() && (getActiveNavItemId() == f.getFragmentId());
	}

	public void onBackPressed() {
		if (backPressed) {
			finish();
			return;
		}

		if (activeMenu != null) {
			if (activeMenu.back()) return;
			else if (hideActiveMenu()) return;
		}

		ToolBarView tb = getToolBar();
		if ((tb != null) && tb.onBackPressed()) return;

		ActivityFragment f = getActiveFragment();

		if (f != null) {
			if (f.onBackPressed()) return;

			int navId = getActiveNavItemId();

			if ((f.getFragmentId() != navId) && (navId != ID_NULL)) {
				showFragment(navId);
				return;
			}
		}

		backPressed = true;
		Toast.makeText(getContext(), getExitMsg(), Toast.LENGTH_SHORT).show();
		App.get().getHandler().postDelayed(() -> backPressed = false, 2000);
	}

	@Override
	public Collection<ListenerRef<ActivityListener>> getBroadcastEventListeners() {
		return listeners;
	}

	public void fireBroadcastEvent(long event) {
		fireBroadcastEvent(l -> l.onActivityEvent(this, event), event);
	}

	public void postBroadcastEvent(long event) {
		postBroadcastEvent(l -> l.onActivityEvent(this, event), event);
	}

	public OverlayMenu createMenu(View anchor) {
		return new OverlayMenuView(getAppActivity().getContext(), null);
	}

	public void setActiveMenu(@Nullable OverlayMenu menu) {
		hideActiveMenu();
		this.activeMenu = menu;
	}

	public boolean hideActiveMenu() {
		if (activeMenu == null) return false;
		activeMenu.hide();
		activeMenu = null;
		return true;
	}

	public boolean interceptTouchEvent(MotionEvent e, Function<MotionEvent, Boolean> view) {
		if ((e.getAction() != MotionEvent.ACTION_DOWN) || (activeMenu == null)) return view.apply(e);
		activeMenu.hide();
		activeMenu = null;
		return true;
	}

	public void setFullScreen(boolean fullScreen) {
		this.fullScreen = fullScreen;
		View decor = getAppActivity().getWindow().getDecorView();
		decor.setSystemUiVisibility(fullScreen ? FULLSCREEN_FLAGS : SYSTEM_UI_FLAG_VISIBLE);
	}

	public boolean isFullScreen() {
		return fullScreen;
	}

	protected void setTheme() {
	}

	protected void setSystemUiVisibility() {
		setFullScreen(isFullScreen());
	}
}
