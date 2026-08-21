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

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.ViewFlipper;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.psiphon3.psiphonlibrary.DataTransferStats;
import com.psiphon3.psiphonlibrary.EmbeddedValues;
import com.psiphon3.psiphonlibrary.LocalizedActivities;
import com.psiphon3.psiphonlibrary.TunnelServiceInteractor;
import com.psiphon3.psiphonlibrary.Utils;

import net.grandcentrix.tray.AppPreferences;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

import io.reactivex.Flowable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;

public class HomeTabFragment extends Fragment {
    // Emblem opacity used to signal "not connected" (~30%).
    private static final int EMBLEM_ALPHA_DIM = 77;
    private static final int EMBLEM_ALPHA_FULL = 255;

    // Opacity of the radial glow per state. The glow used to be drawn at full
    // strength always, which read as "active" underneath a dimmed emblem.
    private static final float GLOW_ALPHA_OFF = 0.16f;
    private static final float GLOW_ALPHA_CONNECTING = 0.6f;
    private static final float GLOW_ALPHA_ON = 1f;

    private MainActivityViewModel viewModel;
    private ViewFlipper sponsorViewFlipper;
    private ScrollView statusLayout;
    private ImageButton statusViewImage;
    private View statusGlow;
    private View mainView;
    private SponsorHomePage sponsorHomePage;
    private boolean isWebViewLoaded = false;
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private TextView lastLogEntryTv;
    private ObjectAnimator pulseAnimator;

    // Connection status views
    private TextView connectionStatusLabel;
    private TextView connectionStatusHint;

    // Live session card
    private View sessionCard;
    private TextView sessionDurationText;
    private TextView sessionSentText;
    private TextView sessionReceivedText;

    // LAN proxy info views
    private LinearLayout lanProxyInfoSection;
    private TextView lanProxyHttpText;
    private TextView lanProxySocksText;
    private TextView lanProxyUsernameText;
    private TextView lanProxyPasswordText;

    // Raw (unlocalised) values behind the LAN proxy rows. The rows render a
    // translated label around the value, so copying the rendered text would put
    // "HTTP proxy: 10.0.0.4:8080" on the clipboard instead of an address that
    // can be pasted into another app's proxy field.
    @Nullable
    private String lanProxyHttpValue;
    @Nullable
    private String lanProxySocksValue;
    @Nullable
    private String lanProxyUsernameValue;
    @Nullable
    private String lanProxyPasswordValue;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.home_tab_layout, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mainView = view;

        ((TextView) view.findViewById(R.id.versionline))
                .setText(requireContext().getString(R.string.client_version, EmbeddedValues.CLIENT_VERSION));

        sponsorViewFlipper = view.findViewById(R.id.sponsorViewFlipper);
        sponsorViewFlipper.setInAnimation(AnimationUtils.loadAnimation(requireContext(), android.R.anim.slide_in_left));
        sponsorViewFlipper.setOutAnimation(AnimationUtils.loadAnimation(requireContext(), android.R.anim.slide_out_right));

        statusLayout = view.findViewById(R.id.statusLayout);
        statusGlow = view.findViewById(R.id.statusGlow);
        statusViewImage = view.findViewById(R.id.statusViewImage);
        // Use Lion & Sun emblem for all states; connection state shown via alpha/animation
        statusViewImage.setImageResource(R.drawable.lion_and_sun);
        statusViewImage.setImageAlpha(EMBLEM_ALPHA_DIM); // Start dimmed (disconnected)
        // The emblem is the largest element on the screen, so make it behave like the
        // control users expect it to be: delegate taps to the Connect/Stop button that
        // already owns the tunnel start/stop logic and its validation.
        statusViewImage.setOnClickListener(v -> {
            View toggleButton = requireActivity().findViewById(R.id.toggleButton);
            if (toggleButton != null && toggleButton.isEnabled()) {
                toggleButton.performClick();
            }
        });

        connectionStatusLabel = view.findViewById(R.id.connectionStatusLabel);
        connectionStatusHint = view.findViewById(R.id.connectionStatusHint);

        sessionCard = view.findViewById(R.id.homeSessionCard);
        sessionDurationText = view.findViewById(R.id.homeSessionDuration);
        sessionSentText = view.findViewById(R.id.homeSessionSent);
        sessionReceivedText = view.findViewById(R.id.homeSessionReceived);

        lastLogEntryTv = view.findViewById(R.id.lastlogline);

        // LAN proxy info views
        lanProxyInfoSection = view.findViewById(R.id.lanProxyInfoSection);
        lanProxyHttpText = view.findViewById(R.id.lanProxyHttpText);
        lanProxySocksText = view.findViewById(R.id.lanProxySocksText);
        lanProxyUsernameText = view.findViewById(R.id.lanProxyUsernameText);
        lanProxyPasswordText = view.findViewById(R.id.lanProxyPasswordText);

        // Sharing a proxy means telling somebody else an address and a password.
        // Tapping the row is a lot better than reading digits off a screen.
        lanProxyHttpText.setOnClickListener(v ->
                SkUi.copyToClipboard(v, R.string.app_name, lanProxyHttpValue));
        lanProxySocksText.setOnClickListener(v ->
                SkUi.copyToClipboard(v, R.string.app_name, lanProxySocksValue));
        lanProxyUsernameText.setOnClickListener(v ->
                SkUi.copyToClipboard(v, R.string.app_name, lanProxyUsernameValue));
        lanProxyPasswordText.setOnClickListener(v ->
                SkUi.copyToClipboard(v, R.string.app_name, lanProxyPasswordValue));

        // Start in the disconnected presentation until the first tunnel state arrives.
        setStatusText(R.string.sk_status_not_connected,
                R.string.sk_status_hint_not_connected,
                R.color.sk_status_off);
        setGlowAlpha(GLOW_ALPHA_OFF);
        setEmblemAction(R.string.sk_emblem_action_connect);
        hideSessionCard();

        viewModel = new ViewModelProvider(requireActivity(),
                new ViewModelProvider.AndroidViewModelFactory(requireActivity().getApplication()))
                .get(MainActivityViewModel.class);
    }

    @Override
    public void onPause() {
        super.onPause();
        compositeDisposable.clear();
        // Nothing on this tab is visible any more. The animator used to keep
        // running in the background until the fragment was destroyed.
        stopPulseAnimation();
    }

    @Override
    public void onResume() {
        super.onResume();
        final TunnelServiceInteractor tunnelServiceInteractor =
                ((LocalizedActivities.AppCompatActivity) requireActivity())
                        .getTunnelServiceInteractor();

        // Observe last log entry to display.
        compositeDisposable.add(viewModel.lastLogEntryFlowable()
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(this::setLastLogEntry)
                .subscribe());

        // Observes tunnel state changes and updates the status UI,
        // also loads sponsor home pages in the embedded web view if needed.
        compositeDisposable.add(tunnelServiceInteractor.tunnelStateFlowable()
                .observeOn(AndroidSchedulers.mainThread())
                // Update the connection status UI
                .doOnNext(this::updateStatusUI)
                // Check for URLs to be opened in the embedded web view.
                .doOnNext(tunnelState -> {
                    // If the tunnel is either stopped or running but not connected
                    // then stop loading the sponsor page and flip to status view.
                    if (tunnelState.isStopped() ||
                            (tunnelState.isRunning() && !tunnelState.connectionData().isConnected())) {
                        if (sponsorHomePage != null) {
                            sponsorHomePage.stop();
                        }
                        if (sponsorViewFlipper != null && statusLayout != null) {
                            boolean isShowingWebView = sponsorViewFlipper.getCurrentView() != statusLayout;
                            if (isShowingWebView) {
                                sponsorViewFlipper.showNext();
                            }
                        }
                        // Also reset isWebViewLoaded
                        isWebViewLoaded = false;
                    }
                })
                // Load the embedded web view if needed
                .switchMap(tunnelState -> {
                    // Check if tunnel is connected
                    // Sponsor home pages disabled in Shir o Khorshid — these are
                    // Psiphon Inc promotional pages (e.g. "Install Conduit" prompts)
                    // that are not appropriate for this community build.
                    return Flowable.<String>empty();
                })
                .doOnNext(this::loadEmbeddedWebView)
                .subscribe());

        // Live session figures. This is the same periodic stream the Statistics
        // tab uses, so showing duration and volume on the Home tab costs nothing
        // extra and saves a tab switch for the two numbers people check most.
        compositeDisposable.add(tunnelServiceInteractor.dataStatsFlowable()
                .startWith(Boolean.FALSE)
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(this::updateSessionCard)
                .subscribe());
    }

    @Override
    public void onDestroyView() {
        // The animator holds a hard reference to the emblem, so it has to die
        // with the view, not with the fragment.
        stopPulseAnimation();
        if (sponsorHomePage != null) {
            sponsorHomePage.stop();
            sponsorHomePage = null;
        }
        sponsorViewFlipper = null;
        statusLayout = null;
        statusViewImage = null;
        statusGlow = null;
        connectionStatusLabel = null;
        connectionStatusHint = null;
        sessionCard = null;
        sessionDurationText = null;
        sessionSentText = null;
        sessionReceivedText = null;
        lastLogEntryTv = null;
        lanProxyInfoSection = null;
        lanProxyHttpText = null;
        lanProxySocksText = null;
        lanProxyUsernameText = null;
        lanProxyPasswordText = null;
        mainView = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        compositeDisposable.dispose();
        stopPulseAnimation();
        if (sponsorHomePage != null) {
            sponsorHomePage.stop();
        }
    }

    private void setLastLogEntry(String entry) {
        if (lastLogEntryTv != null) {
            lastLogEntryTv.setText(entry);
        }
    }

    private void updateStatusUI(TunnelState tunnelState) {
        if (statusViewImage == null) {
            // View already destroyed; a queued emission must not touch it.
            return;
        }
        if (tunnelState.isRunning()) {
            if (tunnelState.connectionData().isConnected()) {
                // Connected: full brightness, no animation
                stopPulseAnimation();
                statusViewImage.setImageAlpha(EMBLEM_ALPHA_FULL);
                setGlowAlpha(GLOW_ALPHA_ON);
                setEmblemAction(R.string.sk_emblem_action_disconnect);
                setStatusText(R.string.sk_status_connected,
                        R.string.sk_status_hint_connected,
                        R.color.sk_status_connected);
                // Show LAN proxy info if sharing is enabled
                updateLanProxyInfo(tunnelState.connectionData());
            } else {
                // Connecting: pulse animation
                startPulseAnimation();
                setGlowAlpha(GLOW_ALPHA_CONNECTING);
                setEmblemAction(R.string.sk_emblem_action_disconnect);
                boolean waitingForNetwork =
                        tunnelState.connectionData().networkConnectionState() ==
                                TunnelState.ConnectionData.NetworkConnectionState.WAITING_FOR_NETWORK;
                setStatusText(
                        waitingForNetwork
                                ? R.string.sk_status_waiting_network
                                : R.string.sk_status_connecting,
                        waitingForNetwork
                                ? R.string.sk_status_hint_waiting_network
                                : R.string.sk_status_hint_connecting,
                        R.color.sk_status_connecting);
                hideLanProxyInfo();
                hideSessionCard();
            }
        } else {
            // Disconnected: dim, no animation
            stopPulseAnimation();
            statusViewImage.setImageAlpha(EMBLEM_ALPHA_DIM);
            setGlowAlpha(GLOW_ALPHA_OFF);
            setEmblemAction(R.string.sk_emblem_action_connect);
            setStatusText(R.string.sk_status_not_connected,
                    R.string.sk_status_hint_not_connected,
                    R.color.sk_status_off);
            hideLanProxyInfo();
            hideSessionCard();
        }
    }

    private void setGlowAlpha(float alpha) {
        if (statusGlow != null) {
            statusGlow.setAlpha(alpha);
        }
    }

    /**
     * Keep the emblem's spoken label in step with what tapping it will do.
     */
    private void setEmblemAction(@StringRes int actionRes) {
        Context context = getContext();
        if (statusViewImage == null || context == null) {
            return;
        }
        statusViewImage.setContentDescription(context.getString(actionRes));
    }

    private void setStatusText(@StringRes int labelRes, @StringRes int hintRes, @ColorRes int colorRes) {
        Context context = getContext();
        if (context == null) {
            return;
        }
        if (connectionStatusLabel != null) {
            connectionStatusLabel.setText(labelRes);
            connectionStatusLabel.setTextColor(ContextCompat.getColor(context, colorRes));
        }
        if (connectionStatusHint != null) {
            connectionStatusHint.setText(hintRes);
        }
    }

    /**
     * Fill in the live session card, or hide it when there is no session.
     */
    private void updateSessionCard(boolean isConnected) {
        if (sessionCard == null) {
            return;
        }
        if (!isConnected) {
            hideSessionCard();
            return;
        }
        DataTransferStats.DataTransferStatsForUI stats =
                DataTransferStats.getDataTransferStatsForUI();
        sessionCard.setVisibility(View.VISIBLE);
        if (sessionDurationText != null) {
            sessionDurationText.setText(Utils.elapsedTimeToDisplay(stats.getElapsedTime()));
        }
        if (sessionSentText != null) {
            sessionSentText.setText(Utils.byteCountToDisplaySize(stats.getTotalBytesSent(), false));
        }
        if (sessionReceivedText != null) {
            sessionReceivedText.setText(
                    Utils.byteCountToDisplaySize(stats.getTotalBytesReceived(), false));
        }
    }

    private void hideSessionCard() {
        if (sessionCard != null) {
            sessionCard.setVisibility(View.GONE);
        }
    }

    private void updateLanProxyInfo(TunnelState.ConnectionData connectionData) {
        if (lanProxyInfoSection == null) {
            return;
        }

        if (!connectionData.isLanSharingEnabled()) {
            hideLanProxyInfo();
            return;
        }

        Context context = getContext();
        if (context == null) {
            hideLanProxyInfo();
            return;
        }

        String lanIp = getLanIpAddress(context);
        if (lanIp == null) {
            hideLanProxyInfo();
            return;
        }

        int httpPort = connectionData.httpPort();
        int socksPort = connectionData.socksPort();

        if (httpPort <= 0 && socksPort <= 0) {
            hideLanProxyInfo();
            return;
        }

        lanProxyInfoSection.setVisibility(View.VISIBLE);

        if (httpPort > 0) {
            lanProxyHttpValue = String.format(Locale.US, "%s:%d", lanIp, httpPort);
            lanProxyHttpText.setText(getString(R.string.lan_proxy_http_address, lanIp, httpPort));
            lanProxyHttpText.setVisibility(View.VISIBLE);
        } else {
            lanProxyHttpValue = null;
            lanProxyHttpText.setVisibility(View.GONE);
        }

        if (socksPort > 0) {
            lanProxySocksValue = String.format(Locale.US, "%s:%d", lanIp, socksPort);
            lanProxySocksText.setText(getString(R.string.lan_proxy_socks_address, lanIp, socksPort));
            lanProxySocksText.setVisibility(View.VISIBLE);
        } else {
            lanProxySocksValue = null;
            lanProxySocksText.setVisibility(View.GONE);
        }

        updateLanProxyCredentials(context);
    }

    private void updateLanProxyCredentials(Context context) {
        if (lanProxyUsernameText == null || lanProxyPasswordText == null) {
            return;
        }

        AppPreferences preferences = new AppPreferences(context);
        String username = preferences
                .getString(context.getString(R.string.shareProxyOnNetworkUsernamePreference), "")
                .trim();
        String password = preferences
                .getString(context.getString(R.string.shareProxyOnNetworkPasswordPreference), "");

        if (TextUtils.isEmpty(username) || TextUtils.isEmpty(password)) {
            lanProxyUsernameValue = null;
            lanProxyPasswordValue = null;
            lanProxyUsernameText.setVisibility(View.GONE);
            lanProxyPasswordText.setVisibility(View.GONE);
            return;
        }

        lanProxyUsernameValue = username;
        lanProxyPasswordValue = password;
        lanProxyUsernameText.setText(getString(R.string.lan_proxy_username, username));
        lanProxyPasswordText.setText(getString(R.string.lan_proxy_password, password));
        lanProxyUsernameText.setVisibility(View.VISIBLE);
        lanProxyPasswordText.setVisibility(View.VISIBLE);
    }

    private void hideLanProxyInfo() {
        if (lanProxyInfoSection != null) {
            lanProxyInfoSection.setVisibility(View.GONE);
        }
    }

    /**
     * Get the device's LAN IPv4 address using a multi-strategy approach.
     * This must work reliably even when our own VPN is active — the VPN
     * obscures the real Wi-Fi/Ethernet IP in several Android APIs.
     *
     * Strategy 1 (API 23+): ConnectivityManager — iterate ALL networks
     *   looking for TRANSPORT_WIFI or TRANSPORT_ETHERNET (skip VPN/cellular),
     *   then read LinkProperties for a site-local IPv4 address.
     *
     * Strategy 2 (all levels): NetworkInterface enumeration — walk every
     *   interface, skip known non-LAN names (tun, ppp, rmnet, lo, dummy,
     *   p2p, wigig), and return the first site-local IPv4 address.
     *
     * Strategy 3 (all levels, deprecated but functional through API 34+):
     *   WifiManager.getConnectionInfo().getIpAddress() — returns the raw
     *   Wi-Fi IPv4 even with VPN active because it reads from the Wi-Fi
     *   HAL directly, not from the routing table.
     *
     * Every strategy is tried in order; the first non-null result wins.
     */
    private static String getLanIpAddress(Context context) {
        // Strategy 1: ConnectivityManager + LinkProperties (most authoritative)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String ip = getLanIpFromConnectivityManager(context);
            if (ip != null) return ip;
        }

        // Strategy 2: NetworkInterface enumeration
        String ip = getLanIpFromNetworkInterfaces();
        if (ip != null) return ip;

        // Strategy 3: WifiManager — deprecated but still functional on all
        // API levels through at least API 34. This is our most reliable
        // fallback because it reads the Wi-Fi IP directly from the HAL,
        // bypassing any VPN routing interference.
        return getLanIpFromWifiManager(context);
    }

    /**
     * Strategy 1: Walk all networks via ConnectivityManager looking for a
     * Wi-Fi or Ethernet transport that is NOT a VPN. When our VPN is active,
     * getActiveNetwork() returns the VPN network, so we must iterate
     * getAllNetworks() to find the underlying physical network.
     */
    @android.annotation.TargetApi(Build.VERSION_CODES.M)
    private static String getLanIpFromConnectivityManager(Context context) {
        ConnectivityManager cm = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;

        try {
            for (Network network : cm.getAllNetworks()) {
                NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
                if (capabilities == null) continue;

                // Skip VPN and cellular transports — we only want the
                // physical LAN network (Wi-Fi or Ethernet).
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    continue;
                }

                LinkProperties linkProperties = cm.getLinkProperties(network);
                if (linkProperties == null) continue;
                for (android.net.LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
                    InetAddress address = linkAddress.getAddress();
                    if (address instanceof Inet4Address &&
                            !address.isLoopbackAddress() &&
                            address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // SecurityException possible on some OEMs; fall through to next strategy
        }
        return null;
    }

    /**
     * Strategy 2: Enumerate all NetworkInterfaces and return the first
     * site-local IPv4 address on a physical-looking interface. We skip
     * known virtual/tunnel interface name prefixes.
     */
    private static String getLanIpFromNetworkInterfaces() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return null;

            // Collect candidates — prefer wlan/eth over others
            String otherCandidate = null;

            for (NetworkInterface ni : Collections.list(interfaces)) {
                if (ni.isLoopback() || !ni.isUp()) continue;
                String name = ni.getName().toLowerCase();

                // Skip known non-LAN interfaces
                if (name.startsWith("tun") || name.startsWith("ppp") ||
                        name.startsWith("rmnet") || name.startsWith("lo") ||
                        name.startsWith("dummy") || name.startsWith("p2p") ||
                        name.startsWith("wigig") || name.startsWith("v4-") ||
                        name.startsWith("clat")) {
                    continue;
                }

                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address &&
                            !addr.isLoopbackAddress() &&
                            addr.isSiteLocalAddress()) {
                        String ip = addr.getHostAddress();
                        if (name.startsWith("wlan") || name.startsWith("eth") ||
                                name.startsWith("en")) {
                            // High-confidence LAN interface — return immediately
                            return ip;
                        }
                        if (otherCandidate == null) {
                            otherCandidate = ip;
                        }
                    }
                }
            }

            // Return best candidate found
            return otherCandidate;
        } catch (SocketException ignored) {}
        return null;
    }

    /**
     * Strategy 3: WifiManager.getConnectionInfo().getIpAddress() reads the
     * IPv4 address directly from the Wi-Fi HAL, so it works correctly even
     * when a VPN is the active network. The API is deprecated since API 31
     * but remains functional and returns correct values through at least
     * Android 14 (API 34). This is the most reliable single-call fallback.
     */
    @SuppressWarnings("deprecation")
    private static String getLanIpFromWifiManager(Context context) {
        try {
            WifiManager wm = (WifiManager)
                    context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return null;
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) return null;
            int ipInt = info.getIpAddress();
            if (ipInt == 0) return null;
            return String.format(Locale.US, "%d.%d.%d.%d",
                    (ipInt & 0xff), (ipInt >> 8 & 0xff),
                    (ipInt >> 16 & 0xff), (ipInt >> 24 & 0xff));
        } catch (Exception ignored) {
            // SecurityException on some OEMs without ACCESS_WIFI_STATE
            return null;
        }
    }

    private void startPulseAnimation() {
        if (statusViewImage == null) {
            return;
        }
        if (pulseAnimator != null && pulseAnimator.isRunning()) {
            return; // Already pulsing
        }
        pulseAnimator = ObjectAnimator.ofInt(statusViewImage, "imageAlpha",
                EMBLEM_ALPHA_DIM, EMBLEM_ALPHA_FULL);
        pulseAnimator.setDuration(1000);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        pulseAnimator.start();
    }

    private void stopPulseAnimation() {
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
    }

    private void loadEmbeddedWebView(String url) {
        if (mainView == null || sponsorViewFlipper == null) {
            return;
        }
        isWebViewLoaded = true;
        sponsorHomePage = new SponsorHomePage(mainView.findViewById(R.id.sponsorWebView),
                mainView.findViewById(R.id.sponsorWebViewProgressBar));
        sponsorHomePage.load(url);

        // Flip to the web view if it is not showing
        boolean isShowingWebView = sponsorViewFlipper.getCurrentView() != statusLayout;
        if (!isShowingWebView) {
            sponsorViewFlipper.showNext();
        }
    }

    protected class SponsorHomePage {
        private class SponsorWebChromeClient extends WebChromeClient {
            private final ProgressBar mProgressBar;

            public SponsorWebChromeClient(ProgressBar progressBar) {
                super();
                mProgressBar = progressBar;
            }

            private boolean mStopped = false;

            public void stop() {
                mStopped = true;
            }

            @Override
            public void onProgressChanged(WebView webView, int progress) {
                if (mStopped) {
                    return;
                }

                mProgressBar.setProgress(progress);
                mProgressBar.setVisibility(progress == 100 ? View.GONE : View.VISIBLE);
            }
        }

        private class SponsorWebViewClient extends WebViewClient {
            private Timer mTimer;
            private boolean mWebViewLoaded = false;
            private boolean mStopped = false;

            public void stop() {
                mStopped = true;
                if (mTimer != null) {
                    mTimer.cancel();
                    mTimer = null;
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView webView, String url) {
                if (mStopped) {
                    return true;
                }

                if (mTimer != null) {
                    mTimer.cancel();
                    mTimer = null;
                }

                if (mWebViewLoaded) {
                    viewModel.signalExternalBrowserUrl(url);
                }
                return mWebViewLoaded;
            }

            @Override
            public void onPageFinished(WebView webView, String url) {
                if (mStopped) {
                    return;
                }

                if (!mWebViewLoaded) {
                    mTimer = new Timer();
                    mTimer.schedule(new TimerTask() {
                        @Override
                        public void run() {
                            if (mStopped) {
                                return;
                            }
                            mWebViewLoaded = true;
                        }
                    }, 2000);
                }
            }
        }

        private final WebView mWebView;
        private final SponsorWebViewClient mWebViewClient;
        private final SponsorWebChromeClient mWebChromeClient;
        private final ProgressBar mProgressBar;

        public SponsorHomePage(WebView webView, ProgressBar progressBar) {
            mWebView = webView;
            mProgressBar = progressBar;
            mWebChromeClient = new SponsorWebChromeClient(mProgressBar);
            mWebViewClient = new SponsorWebViewClient();

            mWebView.setWebChromeClient(mWebChromeClient);
            mWebView.setWebViewClient(mWebViewClient);

            WebSettings webSettings = mWebView.getSettings();
            webSettings.setJavaScriptEnabled(true);
            webSettings.setDomStorageEnabled(true);
            webSettings.setLoadWithOverviewMode(true);
            webSettings.setUseWideViewPort(true);
            // Disable all file:// URLs
            webSettings.setAllowFileAccess(false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                webSettings.setAllowFileAccessFromFileURLs(false);
                webSettings.setAllowUniversalAccessFromFileURLs(false);
            }
            // Disable all content:// URLs
            webSettings.setAllowContentAccess(false);
        }

        public void stop() {
            mWebViewClient.stop();
            mWebChromeClient.stop();
        }

        public void load(String url) {
            mProgressBar.setVisibility(View.VISIBLE);
            mWebView.loadUrl(url);
        }
    }
}
