package org.telegram.messenger;

import android.app.Activity;
import android.view.ViewGroup;

import org.telegram.messenger.novagram.update.NovaUpdateLayout;
import org.telegram.messenger.regular.BuildConfig;
import org.telegram.ui.IUpdateLayout;

public class ApplicationLoaderImpl extends ApplicationLoader {
    @Override
    protected String onGetApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }

    /**
     * The hook upstream leaves for the update bar at the bottom of the chat
     * list. It returns null in the open source, which is why this build had no
     * visible update button at all: the checker worked, and nothing on screen
     * ever said so.
     *
     * <p>A new instance every time on purpose: MainTabsActivity is rebuilt on
     * every account switch and adds the returned layout to a fresh container,
     * so a shared one would already have a parent.</p>
     */
    @Override
    public IUpdateLayout takeUpdateLayout(Activity activity, ViewGroup sideMenuContainer) {
        return new NovaUpdateLayout(activity, sideMenuContainer);
    }
}
