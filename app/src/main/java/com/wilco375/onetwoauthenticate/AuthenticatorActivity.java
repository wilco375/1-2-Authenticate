/*
 * Copyright 2009 Google Inc. All Rights Reserved.
 * Modified Copyright 2018 Wilco van Beijnum.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.wilco375.onetwoauthenticate;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.text.ClipboardManager;
import android.text.Html;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.pes.androidmaterialcolorpickerdialog.ColorPicker;
import com.wilco375.onetwoauthenticate.AccountDb.OtpType;
import com.wilco375.onetwoauthenticate.testability.DependencyInjector;
import com.wilco375.onetwoauthenticate.testability.TestableActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * The main activity that displays usernames and codes
 *
 * @author sweis@google.com (Steve Weis)
 * @author adhintz@google.com (Drew Hintz)
 * @author cemp@google.com (Cem Paya)
 * @author klyubin@google.com (Alex Klyubin)
 */
public class AuthenticatorActivity extends TestableActivity {

    /**
     * The tag for log messages
     */
    private static final String LOCAL_TAG = "AuthenticatorActivity";
    private static final long VIBRATE_DURATION = 200L;

    /**
     * Frequency (milliseconds) with which TOTP countdown indicators are updated.
     */
    private static final long TOTP_COUNTDOWN_REFRESH_PERIOD = 100;

    /**
     * Minimum amount of time (milliseconds) that has to elapse from the moment a HOTP code is
     * generated for an account until the moment the next code can be generated for the account.
     * This is to prevent the user from generating too many HOTP codes in a short period of time.
     */
    private static final long HOTP_MIN_TIME_INTERVAL_BETWEEN_CODES = 5000;

    /**
     * The maximum amount of time (milliseconds) for which a HOTP code is displayed after it's been
     * generated.
     */
    private static final long HOTP_DISPLAY_TIMEOUT = 2 * 60 * 1000;

    // @VisibleForTesting
    static final int DIALOG_ID_UNINSTALL_OLD_APP = 12;

    // @VisibleForTesting
    static final int DIALOG_ID_SAVE_KEY = 13;

    /**
     * Intent action to that tells this Activity to initiate the scanning of barcode to add an
     * account.
     */
    // @VisibleForTesting
    static final String ACTION_SCAN_BARCODE =
            AuthenticatorActivity.class.getName() + ".ScanBarcode";

    private View mContentNoAccounts;
    private View mContentAccountsPresent;
    private ListView mUserList;
    private PinListAdapter mUserAdapter;
    private PinInfo[] mUsers = {};
    private View mCustomizeView;

    /**
     * Counter used for generating TOTP verification codes.
     */
    private TotpCounter mTotpCounter;

    /**
     * Clock used for generating TOTP verification codes.
     */
    private TotpClock mTotpClock;

    /**
     * Task that periodically notifies this activity about the amount of time remaining until
     * the TOTP codes refresh. The task also notifies this activity when TOTP codes refresh.
     */
    private TotpCountdownTask mTotpCountdownTask;

    /**
     * Phase of TOTP countdown indicators. The phase is in {@code [0, 1]} with {@code 1} meaning
     * full time step remaining until the code refreshes, and {@code 0} meaning the code is refreshing
     * right now.
     */
    private double mTotpCountdownPhase;
    private AccountDb mAccountDb;
    private OtpSource mOtpProvider;

    /**
     * Key under which the {@link #mOldAppUninstallIntent} is stored in the instance state
     * {@link Bundle}.
     */
    private static final String KEY_OLD_APP_UNINSTALL_INTENT = "oldAppUninstallIntent";

    /**
     * {@link Intent} for uninstalling the "old" app or {@code null} if not known/available.
     * <p>
     * <p>
     * Note: this field is persisted in the instance state {@link Bundle}. We need to resolve to this
     * error-prone mechanism because showDialog on Eclair doesn't take parameters. Once Froyo is
     * the minimum targetted SDK, this contrived code can be removed.
     */
    private Intent mOldAppUninstallIntent;

    /**
     * Key under which the {@link #mSaveKeyDialogParams} is stored in the instance state
     * {@link Bundle}.
     */
    private static final String KEY_SAVE_KEY_DIALOG_PARAMS = "saveKeyDialogParams";

    /**
     * Parameters to the save key dialog (DIALOG_ID_SAVE_KEY).
     * <p>
     * <p>
     * Note: this field is persisted in the instance state {@link Bundle}. We need to resolve to this
     * error-prone mechanism because showDialog on Eclair doesn't take parameters. Once Froyo is
     * the minimum targetted SDK, this contrived code can be removed.
     */
    private SaveKeyDialogParams mSaveKeyDialogParams;

    /**
     * Whether this activity is currently displaying a confirmation prompt in response to the
     * "save key" Intent.
     */
    private boolean mSaveKeyIntentConfirmationInProgress;

    private static final String OTP_SCHEME = "otpauth";
    private static final String TOTP = "totp"; // time-based
    private static final String HOTP = "hotp"; // counter-based
    private static final String SECRET_PARAM = "secret";
    private static final String COUNTER_PARAM = "counter";
    // @VisibleForTesting
    public static final int CHECK_KEY_VALUE_ID = 0;
    // @VisibleForTesting
    public static final int RENAME_ID = 1;
    // @VisibleForTesting
    public static final int REMOVE_ID = 2;
    // @VisibleForTesting
    static final int COPY_TO_CLIPBOARD_ID = 3;
    // @VisibleForTesting
    static final int CUSTOMIZE_ID = 4;
    // @VisibleForTesting
    static final int SCAN_REQUEST = 31337;
    // @VisibleForTesting
    static final int CHOOSE_ICON = 31338;

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mAccountDb = DependencyInjector.getAccountDb();
        mOtpProvider = DependencyInjector.getOtpProvider();

        // Use a different (longer) title from the one that's declared in the manifest (and the one that
        // the Android launcher displays).
        setTitle(R.string.app_name);

        mTotpCounter = mOtpProvider.getTotpCounter();
        mTotpClock = mOtpProvider.getTotpClock();

        setContentView(R.layout.main);

        // restore state on screen rotation
        Object savedState = getLastCustomNonConfigurationInstance();
        if (savedState != null) {
            mUsers = (PinInfo[]) savedState;
            // Re-enable the Get Code buttons on all HOTP accounts, otherwise they'll stay disabled.
            for (PinInfo account : mUsers) {
                if (account.isHotp) {
                    account.hotpCodeGenerationAllowed = true;
                }
            }
        }

        if (savedInstanceState != null) {
            mOldAppUninstallIntent = savedInstanceState.getParcelable(KEY_OLD_APP_UNINSTALL_INTENT);
            mSaveKeyDialogParams =
                    (SaveKeyDialogParams) savedInstanceState.getSerializable(KEY_SAVE_KEY_DIALOG_PARAMS);
        }

        mUserList = findViewById(R.id.user_list);
        mContentNoAccounts = findViewById(R.id.content_no_accounts);
        mContentAccountsPresent = findViewById(R.id.content_accounts_present);
        mContentNoAccounts.setVisibility((mUsers.length > 0) ? View.GONE : View.VISIBLE);
        mContentAccountsPresent.setVisibility((mUsers.length > 0) ? View.VISIBLE : View.GONE);
        TextView noAccountsPromptDetails = findViewById(R.id.details);
        noAccountsPromptDetails.setText(
                Html.fromHtml(getString(R.string.welcome_page_details)));

        findViewById(R.id.add_account_button).setOnClickListener(v -> addAccount());

        mUserAdapter = new PinListAdapter(this, R.layout.user_row, mUsers);

        mUserList.setVisibility(View.GONE);
        mUserList.setAdapter(mUserAdapter);
        mUserList.setOnItemClickListener((unusedParent, row, unusedPosition, unusedId) -> {
            NextOtpButtonListener clickListener = (NextOtpButtonListener) row.getTag();
            View nextOtp = row.findViewById(R.id.next_otp);
            if ((clickListener != null) && nextOtp.isEnabled()) {
                clickListener.onClick(row);
            }
            mUserList.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
        });

        findViewById(R.id.add_account_fab).setOnClickListener(view -> addAccount());

        if (savedInstanceState == null) {
            // This is the first time this Activity is starting (i.e., not restoring previous state which
            // was saved, for example, due to orientation change)
            DependencyInjector.getOptionalFeatures().onAuthenticatorActivityCreated(this);
            handleIntent(getIntent());
        }
    }

    /**
     * Reacts to the {@link Intent} that started this activity or arrived to this activity without
     * restarting it (i.e., arrived via {@link #onNewIntent(Intent)}). Does nothing if the provided
     * intent is {@code null}.
     */
    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }

        String action = intent.getAction();
        if (action == null) {
            return;
        }

        if (ACTION_SCAN_BARCODE.equals(action)) {
            scanBarcode();
        } else if (intent.getData() != null) {
            interpretScanResult(intent.getData(), true);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putParcelable(KEY_OLD_APP_UNINSTALL_INTENT, mOldAppUninstallIntent);
        outState.putSerializable(KEY_SAVE_KEY_DIALOG_PARAMS, mSaveKeyDialogParams);
    }

    @Override
    public Object onRetainCustomNonConfigurationInstance() {
        return mUsers;  // save state of users and currently displayed PINs
    }

    // Because this activity is marked as singleTop, new launch intents will be
    // delivered via this API instead of onResume().
    // Override here to catch otpauth:// URL being opened from QR code reader.
    @Override
    protected void onNewIntent(Intent intent) {
        Log.i(getString(R.string.app_name), LOCAL_TAG + ": onNewIntent");
        handleIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();

        updateCodesAndStartTotpCountdownTask();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(getString(R.string.app_name), LOCAL_TAG + ": onResume");
    }

    @Override
    protected void onStop() {
        stopTotpCountdownTask();

        super.onStop();
    }

    private void updateCodesAndStartTotpCountdownTask() {
        stopTotpCountdownTask();

        mTotpCountdownTask =
                new TotpCountdownTask(mTotpCounter, mTotpClock, TOTP_COUNTDOWN_REFRESH_PERIOD);
        mTotpCountdownTask.setListener(new TotpCountdownTask.Listener() {
            @Override
            public void onTotpCountdown(long millisRemaining) {
                if (isFinishing()) {
                    // No need to reach to this even because the Activity is finishing anyway
                    return;
                }
                setTotpCountdownPhaseFromTimeTillNextValue(millisRemaining);
            }

            @Override
            public void onTotpCounterValueChanged() {
                if (isFinishing()) {
                    // No need to reach to this even because the Activity is finishing anyway
                    return;
                }
                refreshVerificationCodes();
            }
        });

        mTotpCountdownTask.startAndNotifyListener();
    }

    private void stopTotpCountdownTask() {
        if (mTotpCountdownTask != null) {
            mTotpCountdownTask.stop();
            mTotpCountdownTask = null;
        }
    }

    /**
     * Display list of user emails and updated pin codes.
     */
    protected void refreshUserList() {
        refreshUserList(false);
    }

    private void setTotpCountdownPhase(double phase) {
        mTotpCountdownPhase = phase;
        updateCountdownIndicators();
    }

    private void setTotpCountdownPhaseFromTimeTillNextValue(long millisRemaining) {
        setTotpCountdownPhase(
                ((double) millisRemaining) / Utilities.secondsToMillis(mTotpCounter.getTimeStep()));
    }

    private void refreshVerificationCodes() {
        refreshUserList();
        setTotpCountdownPhase(1.0);
    }

    private void updateCountdownIndicators() {
        for (int i = 0, len = mUserList.getChildCount(); i < len; i++) {
            View listEntry = mUserList.getChildAt(i);
            CountdownIndicator indicator =
                    listEntry.findViewById(R.id.countdown_icon);
            if (indicator != null) {
                indicator.setPhase(mTotpCountdownPhase);
            }
        }
    }

    /**
     * Display list of user emails and updated pin codes.
     *
     * @param isAccountModified if true, force full refresh
     */
    // @VisibleForTesting
    public void refreshUserList(boolean isAccountModified) {
        ArrayList<String> usernames = new ArrayList<>();
        mAccountDb.getNames(usernames);

        int userCount = usernames.size();

        if (userCount > 0) {
            boolean newListRequired = isAccountModified || mUsers.length != userCount;
            if (newListRequired) {
                mUsers = new PinInfo[userCount];
            }

            for (int i = 0; i < userCount; ++i) {
                String user = usernames.get(i);
                try {
                    computeAndDisplayPin(user, i, false);
                } catch (OtpSourceException ignored) {
                }
            }

            if (newListRequired) {
                // Make the list display the data from the newly created array of accounts
                // This forces the list to scroll to top.
                mUserAdapter = new PinListAdapter(this, R.layout.user_row, mUsers);
                mUserList.setAdapter(mUserAdapter);
            }

            mUserAdapter.notifyDataSetChanged();

            if (mUserList.getVisibility() != View.VISIBLE) {
                mUserList.setVisibility(View.VISIBLE);
                registerForContextMenu(mUserList);
            }
        } else {
            mUsers = new PinInfo[0]; // clear any existing user PIN state
            mUserList.setVisibility(View.GONE);
        }

        // Display the list of accounts if there are accounts, otherwise display a
        // different layout explaining the user how this app works and providing the user with an easy
        // way to add an account.
        mContentNoAccounts.setVisibility((mUsers.length > 0) ? View.GONE : View.VISIBLE);
        mContentAccountsPresent.setVisibility((mUsers.length > 0) ? View.VISIBLE : View.GONE);
    }

    /**
     * Computes the PIN and saves it in mUsers. This currently runs in the UI
     * thread so it should not take more than a second or so. If necessary, we can
     * move the computation to a background thread.
     *
     * @param user        the user email to display with the PIN
     * @param position    the index for the screen of this user and PIN
     * @param computeHotp true if we should increment counter and display new hotp
     */
    public void computeAndDisplayPin(String user, int position,
                                     boolean computeHotp) throws OtpSourceException {

        PinInfo currentPin;
        if (mUsers[position] != null) {
            currentPin = mUsers[position]; // existing PinInfo, so we'll update it
        } else {
            currentPin = new PinInfo();
            currentPin.pin = getString(R.string.empty_pin);
            currentPin.hotpCodeGenerationAllowed = true;
        }

        OtpType type = mAccountDb.getType(user);
        currentPin.isHotp = (type == OtpType.HOTP);

        currentPin.user = user;

        if (!currentPin.isHotp || computeHotp) {
            // Always safe to recompute, because this code path is only
            // reached if the account is:
            // - Time-based, in which case getNextCode() does not change state.
            // - Counter-based (HOTP) and computeHotp is true.
            currentPin.pin = mOtpProvider.getNextCode(user);
            currentPin.hotpCodeGenerationAllowed = true;
        }

        mUsers[position] = currentPin;
    }

    /**
     * Parses a secret value from a URI. The format will be:
     * <p>
     * otpauth://totp/user@example.com?secret=FFF...
     * otpauth://hotp/user@example.com?secret=FFF...&counter=123
     *
     * @param uri               The URI containing the secret key
     * @param confirmBeforeSave a boolean to indicate if the user should be
     *                          prompted for confirmation before updating the otp
     *                          account information.
     */
    private void parseSecret(Uri uri, boolean confirmBeforeSave) {
        final String scheme = uri.getScheme().toLowerCase();
        final String path = uri.getPath();
        final String authority = uri.getAuthority();
        final String user;
        final String secret;
        final OtpType type;
        final Integer counter;

        if (!OTP_SCHEME.equals(scheme)) {
            Log.e(getString(R.string.app_name), LOCAL_TAG + ": Invalid or missing scheme in uri");
            showDialog(Utilities.INVALID_QR_CODE);
            return;
        }

        switch (authority) {
            case TOTP:
                type = OtpType.TOTP;
                counter = AccountDb.DEFAULT_HOTP_COUNTER; // only interesting for HOTP

                break;
            case HOTP:
                type = OtpType.HOTP;
                String counterParameter = uri.getQueryParameter(COUNTER_PARAM);
                if (counterParameter != null) {
                    try {
                        counter = Integer.parseInt(counterParameter);
                    } catch (NumberFormatException e) {
                        Log.e(getString(R.string.app_name), LOCAL_TAG + ": Invalid counter in uri");
                        showDialog(Utilities.INVALID_QR_CODE);
                        return;
                    }
                } else {
                    counter = AccountDb.DEFAULT_HOTP_COUNTER;
                }
                break;
            default:
                Log.e(getString(R.string.app_name), LOCAL_TAG + ": Invalid or missing authority in uri");
                showDialog(Utilities.INVALID_QR_CODE);
                return;
        }

        user = validateAndGetUserInPath(path);
        if (user == null) {
            Log.e(getString(R.string.app_name), LOCAL_TAG + ": Missing user id in uri");
            showDialog(Utilities.INVALID_QR_CODE);
            return;
        }

        secret = uri.getQueryParameter(SECRET_PARAM);

        if (secret == null || secret.length() == 0) {
            Log.e(getString(R.string.app_name), LOCAL_TAG +
                    ": Secret key not found in URI");
            showDialog(Utilities.INVALID_SECRET_IN_QR_CODE);
            return;
        }

        if (AccountDb.getSigningOracle(secret) == null) {
            Log.e(getString(R.string.app_name), LOCAL_TAG + ": Invalid secret key");
            showDialog(Utilities.INVALID_SECRET_IN_QR_CODE);
            return;
        }

        if (secret.equals(mAccountDb.getSecret(user)) &&
                counter == mAccountDb.getCounter(user) &&
                type == mAccountDb.getType(user)) {
            return;  // nothing to update.
        }

        if (confirmBeforeSave) {
            mSaveKeyDialogParams = new SaveKeyDialogParams(user, secret, type, counter);
            showDialog(DIALOG_ID_SAVE_KEY);
        } else {
            saveSecretAndRefreshUserList(user, secret, null, type, counter);
        }
    }

    private static String validateAndGetUserInPath(String path) {
        if (path == null || !path.startsWith("/")) {
            return null;
        }
        // path is "/user", so remove leading "/", and trailing white spaces
        String user = path.substring(1).trim();
        if (user.length() == 0) {
            return null; // only white spaces.
        }
        return user;
    }

    /**
     * Saves the secret key to local storage on the phone and updates the displayed account list.
     *
     * @param user         the user email address. When editing, the new user email.
     * @param secret       the secret key
     * @param originalUser If editing, the original user email, otherwise null.
     * @param type         hotp vs totp
     * @param counter      only important for the hotp type
     */
    private void saveSecretAndRefreshUserList(String user, String secret,
                                              String originalUser, OtpType type, Integer counter) {
        if (saveSecret(this, user, secret, originalUser, type, counter)) {
            refreshUserList(true);
        }
    }

    /**
     * Saves the secret key to local storage on the phone.
     *
     * @param user         the user email address. When editing, the new user email.
     * @param secret       the secret key
     * @param originalUser If editing, the original user email, otherwise null.
     * @param type         hotp vs totp
     * @param counter      only important for the hotp type
     * @return {@code true} if the secret was saved, {@code false} otherwise.
     */
    static boolean saveSecret(Context context, String user, String secret,
                              String originalUser, OtpType type, Integer counter) {
        if (originalUser == null) {  // new user account
            originalUser = user;
        }
        if (secret != null) {
            AccountDb accountDb = DependencyInjector.getAccountDb();
            accountDb.update(user, secret, originalUser, type, counter);
            DependencyInjector.getOptionalFeatures().onAuthenticatorActivityAccountSaved(context, user);
            // TODO: Consider having a display message that activities can call and it
            //       will present a toast with a uniform duration, and perhaps update
            //       status messages (presuming we have a way to remove them after they
            //       are stale).
            Toast.makeText(context, R.string.secret_saved, Toast.LENGTH_LONG).show();
            ((Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE))
                    .vibrate(VIBRATE_DURATION);
            return true;
        } else {
            Log.e(LOCAL_TAG, "Trying to save an empty secret key");
            Toast.makeText(context, R.string.error_empty_secret, Toast.LENGTH_LONG).show();
            return false;
        }
    }

    /**
     * Converts user list ordinal id to user email
     */
    private String idToEmail(long id) {
        return mUsers[(int) id].user;
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        String user = idToEmail(info.id);
        OtpType type = mAccountDb.getType(user);
        menu.setHeaderTitle(user);
        menu.add(0, COPY_TO_CLIPBOARD_ID, 0, R.string.copy_to_clipboard);
        // Option to display the check-code is only available for HOTP accounts.
        if (type == OtpType.HOTP) {
            menu.add(0, CHECK_KEY_VALUE_ID, 0, R.string.check_code_menu_item);
        }
        menu.add(0, CUSTOMIZE_ID, 0, R.string.customize);
        menu.add(0, RENAME_ID, 0, R.string.rename);
        menu.add(0, REMOVE_ID, 0, R.string.context_menu_remove_account);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
        Intent intent;
        final String user = idToEmail(info.id); // final so listener can see value
        switch (item.getItemId()) {
            case COPY_TO_CLIPBOARD_ID:
                ClipboardManager clipboard =
                        (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                clipboard.setText(mUsers[(int) info.id].pin);
                return true;
            case CHECK_KEY_VALUE_ID:
                intent = new Intent(Intent.ACTION_VIEW);
                intent.setClass(this, CheckCodeActivity.class);
                intent.putExtra("user", user);
                startActivity(intent);
                return true;
            case CUSTOMIZE_ID:
                mCustomizeView = getLayoutInflater().inflate(R.layout.customize,
                        findViewById(R.id.customize_root));

                View customizeColor = mCustomizeView.findViewById(R.id.customize_color);
                Integer dbColor = mAccountDb.getColor(user);
                if (dbColor == null) {
                    dbColor = getResources().getColor(R.color.theme_color);
                }
                int color = dbColor; // Needs to be effectively final in lambda
                customizeColor.setOnClickListener(view -> {
                    ColorPicker colorPicker = new ColorPicker(AuthenticatorActivity.this, (color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF);
                    colorPicker.show();
                    colorPicker.setCallback(newColor -> {
                        customizeColor.setBackgroundColor(newColor);
                        colorPicker.dismiss();
                    });
                });
                customizeColor.setBackgroundColor(color);

                ImageView customizeIcon = mCustomizeView.findViewById(R.id.customize_icon);
                Bitmap icon = FileUtilities.getBitmap(getApplicationContext(), user);
                customizeIcon.setOnClickListener(view -> {
                    Intent i = new Intent()
                            .setType("image/*")
                            .setAction(Intent.ACTION_GET_CONTENT)
                            .putExtra("return-data", true)
                            .putExtra("scale", true)
                            .putExtra("outputX", 256)
                            .putExtra("outputY", 256);
                    Intent chooser = Intent.createChooser(i, getString(R.string.icon));
                    startActivityForResult(chooser, CHOOSE_ICON);
                });
                customizeIcon.setImageBitmap(icon);

                new AlertDialog.Builder(this)
                        .setTitle(R.string.customize)
                        .setView(mCustomizeView)
                        .setPositiveButton(R.string.submit, (dialogInterface, i) -> {
                            // Save color to DB
                            Drawable colorBackground = customizeColor.getBackground();
                            if (colorBackground != null && colorBackground instanceof ColorDrawable) {
                                int newColor = ((ColorDrawable) colorBackground).getColor();
                                if (newColor != color) {
                                    mAccountDb.update(user,
                                            mAccountDb.getSecret(user), user,
                                            mAccountDb.getType(user),
                                            mAccountDb.getCounter(user),
                                            null,
                                            newColor);
                                }
                            }

                            // Save icon to storage
                            Drawable iconDrawable = customizeIcon.getDrawable();
                            if (iconDrawable != null && iconDrawable instanceof BitmapDrawable) {
                                Bitmap newIcon = ((BitmapDrawable) iconDrawable).getBitmap();
                                if (newIcon != icon && newIcon != null) {
                                    FileUtilities.saveBitmap(getApplicationContext(), user, newIcon);
                                }
                            }

                            mUserAdapter.notifyDataSetChanged();
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
                return true;
            case RENAME_ID:
                final Context context = this; // final so listener can see value
                final View frame = getLayoutInflater().inflate(R.layout.rename,
                        findViewById(R.id.rename_root));
                final EditText nameEdit = frame.findViewById(R.id.rename_edittext);
                nameEdit.setText(user);
                new AlertDialog.Builder(this)
                        .setTitle(String.format(getString(R.string.rename_message), user))
                        .setView(frame)
                        .setPositiveButton(R.string.submit,
                                this.getRenameClickListener(context, user, nameEdit))
                        .setNegativeButton(R.string.cancel, null)
                        .show();
                return true;
            case REMOVE_ID:
                new AlertDialog.Builder(this)
                        .setTitle(getString(R.string.remove_account_dialog_title, user))
                        .setIcon(R.drawable.ic_dialog_alert)
                        .setPositiveButton(R.string.remove_account_dialog_button_remove,
                                (dialog, whichButton) -> {
                                    mAccountDb.delete(user);
                                    refreshUserList(true);
                                }
                        )
                        .setNegativeButton(R.string.cancel, null)
                        .show();
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    private DialogInterface.OnClickListener getRenameClickListener(final Context context,
                                                                   final String user, final EditText nameEdit) {
        return (dialog, whichButton) -> {
            String newName = nameEdit.getText().toString();
            if (newName != user) {
                if (mAccountDb.nameExists(newName)) {
                    Toast.makeText(context, R.string.error_exists, Toast.LENGTH_LONG).show();
                } else {
                    saveSecretAndRefreshUserList(newName,
                            mAccountDb.getSecret(user), user, mAccountDb.getType(user),
                            mAccountDb.getCounter(user));
                }
            }
        };
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.import_entries:
                importEntries();
                return true;
            case R.id.export_entries:
                exportEntries();
                return true;
            case R.id.settings:
                showSettings();
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        Log.i(getString(R.string.app_name), LOCAL_TAG + ": onActivityResult");
        if (resultCode == Activity.RESULT_OK) {
            switch (requestCode) {
                case SCAN_REQUEST:
                    // Grab the scan results and convert it into a URI
                    String scanResult = (intent != null) ? intent.getStringExtra("SCAN_RESULT") : null;
                    Uri uri = (scanResult != null) ? Uri.parse(scanResult) : null;
                    interpretScanResult(uri, false);
                    break;
                case CHOOSE_ICON:
                    Uri data = intent.getData();
                    if (data != null) {
                        try {
                            Bitmap icon = MediaStore.Images.Media.getBitmap(this.getContentResolver(), data);
                            if (mCustomizeView != null) {
                                ImageView customizeIcon = mCustomizeView.findViewById(R.id.customize_icon);
                                customizeIcon.setImageBitmap(icon);
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    break;
            }
        }
    }

    private void addAccount() {
        DependencyInjector.getOptionalFeatures().onAuthenticatorActivityAddAccount(this);
    }

    private void scanBarcode() {
        Intent intentScan = new Intent("com.google.zxing.client.android.SCAN");
        intentScan.putExtra("SCAN_MODE", "QR_CODE_MODE");
        intentScan.putExtra("SAVE_HISTORY", false);
        try {
            startActivityForResult(intentScan, SCAN_REQUEST);
        } catch (ActivityNotFoundException error) {
            showDialog(Utilities.DOWNLOAD_DIALOG);
        }
    }

    public static Intent getLaunchIntentActionScanBarcode(Context context) {
        return new Intent(AuthenticatorActivity.ACTION_SCAN_BARCODE)
                .setComponent(new ComponentName(context, AuthenticatorActivity.class));
    }

    private void showSettings() {
        Intent intent = new Intent();
        intent.setClass(this, SettingsActivity.class);
        startActivity(intent);
    }

    private void importEntries() {
        File directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        List<String> exports = new ArrayList<>();
        for (String fileName : directory.list()) {
            if (fileName.endsWith(".json")) {
                exports.add(fileName);
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.import_choose);
        builder.setItems(exports.toArray(new String[0]), (dialogInterface, index) -> {
            dialogInterface.dismiss();

            try {
                BufferedReader reader = new BufferedReader(new FileReader(new File(directory, exports.get(index))));
                String jsonString = reader.readLine();
                JSONArray json = new JSONArray(jsonString);

                for (int i = 0; i < json.length(); i++) {
                    JSONObject item = json.getJSONObject(i);

                    System.out.println(item);
                    System.out.println(item.getString("email"));

                    saveSecretAndRefreshUserList(
                            item.getString("email"),
                            item.getString("secret"),
                            item.getString("email"),
                            OtpType.valueOf(item.getString("type")),
                            item.getInt("counter")
                    );
                }
            } catch (IOException | JSONException e) {
                e.printStackTrace();
            }
        });
        builder.show();
    }

    private void exportEntries() {
        List<String> usernames = new ArrayList<>();
        mAccountDb.getNames(usernames);
        try {
            JSONArray json = new JSONArray();
            for (String username : usernames) {
                JSONObject jsonObject = new JSONObject();
                jsonObject.put("email", username);
                jsonObject.put("secret", mAccountDb.getSecret(username));
                jsonObject.put("counter", mAccountDb.getCounter(username));
                jsonObject.put("type", mAccountDb.getType(username).toString());
                jsonObject.put("color", mAccountDb.getColor(username));
                json.put(jsonObject);
            }
            String jsonString = json.toString();

            File directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File file = new File(directory, "1-2-authenticate-export-" + System.currentTimeMillis() + ".json");
            OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file));
            writer.append(jsonString);
            writer.close();

            Toast.makeText(this, R.string.exported_to, Toast.LENGTH_LONG).show();
        } catch (JSONException | IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Interprets the QR code that was scanned by the user.  Decides whether to
     * launch the key provisioning sequence or the OTP seed setting sequence.
     *
     * @param scanResult        a URI holding the contents of the QR scan result
     * @param confirmBeforeSave a boolean to indicate if the user should be
     *                          prompted for confirmation before updating the otp
     *                          account information.
     */
    private void interpretScanResult(Uri scanResult, boolean confirmBeforeSave) {
        if (DependencyInjector.getOptionalFeatures().interpretScanResult(this, scanResult)) {
            // Scan result consumed by an optional component
            return;
        }
        // The scan result is expected to be a URL that adds an account.

        // If confirmBeforeSave is true, the user has to confirm/reject the action.
        // We need to ensure that new results are accepted only if the previous ones have been
        // confirmed/rejected by the user. This is to prevent the attacker from sending multiple results
        // in sequence to confuse/DoS the user.
        if (confirmBeforeSave) {
            if (mSaveKeyIntentConfirmationInProgress) {
                Log.w(LOCAL_TAG, "Ignoring save key Intent: previous Intent not yet confirmed by user");
                return;
            }
            // No matter what happens below, we'll show a prompt which, once dismissed, will reset the
            // flag below.
            mSaveKeyIntentConfirmationInProgress = true;
        }

        // Sanity check
        if (scanResult == null) {
            showDialog(Utilities.INVALID_QR_CODE);
            return;
        }

        // See if the URL is an account setup URL containing a shared secret
        if (OTP_SCHEME.equals(scanResult.getScheme()) && scanResult.getAuthority() != null) {
            parseSecret(scanResult, confirmBeforeSave);
        } else {
            showDialog(Utilities.INVALID_QR_CODE);
        }
    }

    /**
     * This method is deprecated in SDK level 8, but we have to use it because the
     * new method, which replaces this one, does not exist before SDK level 8
     */
    @Override
    protected Dialog onCreateDialog(final int id) {
        Dialog dialog = null;
        switch (id) {
            /**
             * Prompt to download ZXing from Market. If Market app is not installed,
             * such as on a development phone, open the HTTPS URI for the ZXing apk.
             */
            case Utilities.DOWNLOAD_DIALOG:
                AlertDialog.Builder dlBuilder = new AlertDialog.Builder(this);
                dlBuilder.setTitle(R.string.install_dialog_title);
                dlBuilder.setMessage(R.string.install_dialog_message);
                dlBuilder.setIcon(android.R.drawable.ic_dialog_alert);
                dlBuilder.setPositiveButton(R.string.install_button,
                        (dialog14, whichButton) -> {
                            Intent intent = new Intent(Intent.ACTION_VIEW,
                                    Uri.parse(Utilities.ZXING_MARKET));
                            try {
                                startActivity(intent);
                            } catch (ActivityNotFoundException e) { // if no Market app
                                intent = new Intent(Intent.ACTION_VIEW,
                                        Uri.parse(Utilities.ZXING_DIRECT));
                                startActivity(intent);
                            }
                        }
                );
                dlBuilder.setNegativeButton(R.string.cancel, null);
                dialog = dlBuilder.create();
                break;

            case DIALOG_ID_SAVE_KEY:
                final SaveKeyDialogParams saveKeyDialogParams = mSaveKeyDialogParams;
                dialog = new AlertDialog.Builder(this)
                        .setTitle(R.string.save_key_message)
                        .setMessage(saveKeyDialogParams.user)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setPositiveButton(R.string.ok,
                                (dialog13, whichButton) -> saveSecretAndRefreshUserList(
                                        saveKeyDialogParams.user,
                                        saveKeyDialogParams.secret,
                                        null,
                                        saveKeyDialogParams.type,
                                        saveKeyDialogParams.counter))
                        .setNegativeButton(R.string.cancel, null)
                        .create();
                // Ensure that whenever this dialog is to be displayed via showDialog, it displays the
                // correct (latest) user/account name. If this dialog is not explicitly removed after it's
                // been dismissed, then next time showDialog is invoked, onCreateDialog will not be invoked
                // and the dialog will display the previous user/account name instead of the current one.
                dialog.setOnDismissListener(dialog12 -> {
                    removeDialog(id);
                    onSaveKeyIntentConfirmationPromptDismissed();
                });
                break;

            case Utilities.INVALID_QR_CODE:
                dialog = createOkAlertDialog(R.string.error_title, R.string.error_qr,
                        android.R.drawable.ic_dialog_alert);
                markDialogAsResultOfSaveKeyIntent(dialog);
                break;

            case Utilities.INVALID_SECRET_IN_QR_CODE:
                dialog = createOkAlertDialog(
                        R.string.error_title, R.string.error_uri, android.R.drawable.ic_dialog_alert);
                markDialogAsResultOfSaveKeyIntent(dialog);
                break;

            case DIALOG_ID_UNINSTALL_OLD_APP:
                dialog = new AlertDialog.Builder(this)
                        .setTitle(R.string.dataimport_import_succeeded_uninstall_dialog_title)
                        .setMessage(
                                DependencyInjector.getOptionalFeatures().appendDataImportLearnMoreLink(
                                        this,
                                        getString(R.string.dataimport_import_succeeded_uninstall_dialog_prompt)))
                        .setCancelable(true)
                        .setPositiveButton(
                                R.string.button_uninstall_old_app,
                                (dialog1, whichButton) -> startActivity(mOldAppUninstallIntent))
                        .setNegativeButton(R.string.cancel, null)
                        .create();
                break;

            default:
                dialog =
                        DependencyInjector.getOptionalFeatures().onAuthenticatorActivityCreateDialog(this, id);
                if (dialog == null) {
                    dialog = super.onCreateDialog(id);
                }
                break;
        }
        return dialog;
    }

    private void markDialogAsResultOfSaveKeyIntent(Dialog dialog) {
        dialog.setOnDismissListener(dialog1 -> onSaveKeyIntentConfirmationPromptDismissed());
    }

    /**
     * Invoked when a user-visible confirmation prompt for the Intent to add a new account has been
     * dimissed.
     */
    private void onSaveKeyIntentConfirmationPromptDismissed() {
        mSaveKeyIntentConfirmationInProgress = false;
    }

    /**
     * Create dialog with supplied ids; icon is not set if iconId is 0.
     */
    private Dialog createOkAlertDialog(int titleId, int messageId, int iconId) {
        return new AlertDialog.Builder(this)
                .setTitle(titleId)
                .setMessage(messageId)
                .setIcon(iconId)
                .setPositiveButton(R.string.ok, null)
                .create();
    }

    /**
     * A tuple of user, OTP value, and type, that represents a particular user.
     *
     * @author adhintz@google.com (Drew Hintz)
     */
    private static class PinInfo {
        private String pin; // calculated OTP, or a placeholder if not calculated
        private String user;
        private boolean isHotp = false; // used to see if button needs to be displayed

        /**
         * HOTP only: Whether code generation is allowed for this account.
         */
        private boolean hotpCodeGenerationAllowed;
    }


    /**
     * Scale to use for the text displaying the PIN numbers.
     */
    private static final float PIN_TEXT_SCALEX_NORMAL = 1.0f;
    /**
     * Underscores are shown slightly smaller.
     */
    private static final float PIN_TEXT_SCALEX_UNDERSCORE = 0.87f;

    /**
     * Listener for the Button that generates the next OTP value.
     *
     * @author adhintz@google.com (Drew Hintz)
     */
    private class NextOtpButtonListener implements OnClickListener {
        private final Handler mHandler = new Handler();
        private final PinInfo mAccount;

        private NextOtpButtonListener(PinInfo account) {
            mAccount = account;
        }

        @Override
        public void onClick(View v) {
            int position = findAccountPositionInList();
            if (position == -1) {
                throw new RuntimeException("Account not in list: " + mAccount);
            }

            try {
                computeAndDisplayPin(mAccount.user, position, true);
            } catch (OtpSourceException e) {
                DependencyInjector.getOptionalFeatures().onAuthenticatorActivityGetNextOtpFailed(
                        AuthenticatorActivity.this, mAccount.user, e);
                return;
            }

            final String pin = mAccount.pin;

            // Temporarily disable code generation for this account
            mAccount.hotpCodeGenerationAllowed = false;
            mUserAdapter.notifyDataSetChanged();
            // The delayed operation below will be invoked once code generation is yet again allowed for
            // this account. The delay is in wall clock time (monotonically increasing) and is thus not
            // susceptible to system time jumps.
            mHandler.postDelayed(
                    () -> {
                        mAccount.hotpCodeGenerationAllowed = true;
                        mUserAdapter.notifyDataSetChanged();
                    },
                    HOTP_MIN_TIME_INTERVAL_BETWEEN_CODES);
            // The delayed operation below will hide this OTP to prevent the user from seeing this OTP
            // long after it's been generated (and thus hopefully used).
            mHandler.postDelayed(
                    () -> {
                        if (!pin.equals(mAccount.pin)) {
                            return;
                        }
                        mAccount.pin = getString(R.string.empty_pin);
                        mUserAdapter.notifyDataSetChanged();
                    },
                    HOTP_DISPLAY_TIMEOUT);
        }

        /**
         * Gets the position in the account list of the account this listener is associated with.
         *
         * @return {@code 0}-based position or {@code -1} if the account is not in the list.
         */
        private int findAccountPositionInList() {
            for (int i = 0, len = mUsers.length; i < len; i++) {
                if (mUsers[i] == mAccount) {
                    return i;
                }
            }

            return -1;
        }
    }

    /**
     * Displays the list of users and the current OTP values.
     *
     * @author adhintz@google.com (Drew Hintz)
     */
    private class PinListAdapter extends ArrayAdapter<PinInfo> {

        public PinListAdapter(Context context, int userRowId, PinInfo[] items) {
            super(context, userRowId, items);
        }

        /**
         * Displays the user and OTP for the specified position. For HOTP, displays
         * the button for generating the next OTP value; for TOTP, displays the countdown indicator.
         */
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LayoutInflater inflater = getLayoutInflater();
            PinInfo currentPin = getItem(position);

            View row;
            if (convertView != null) {
                // Reuse an existing view
                row = convertView;
            } else {
                // Create a new view
                row = inflater.inflate(R.layout.user_row, null);
            }

            ImageView iconView = row.findViewById(R.id.icon);
            TextView pinView = row.findViewById(R.id.pin_value);
            TextView userView = row.findViewById(R.id.current_user);
            ImageButton buttonView = row.findViewById(R.id.next_otp);
            CountdownIndicator countdownIndicator =
                    row.findViewById(R.id.countdown_icon);

            Bitmap icon = FileUtilities.getBitmap(getApplicationContext(), currentPin.user);
            if (icon != null) {
                iconView.setImageBitmap(icon);
                iconView.setVisibility(View.VISIBLE);
            } else {
                iconView.setVisibility(View.GONE);
            }

            Integer color = mAccountDb.getColor(currentPin.user);
            if (color == null)
                color = getResources().getColor(R.color.theme_color);

            if (currentPin.isHotp) {
                buttonView.setVisibility(View.VISIBLE);
                buttonView.setEnabled(currentPin.hotpCodeGenerationAllowed);
                if (buttonView.isEnabled()) {
                    buttonView.setColorFilter(color);
                } else {
                    buttonView.clearColorFilter();
                }
                ((ViewGroup) row).setDescendantFocusability(
                        ViewGroup.FOCUS_BLOCK_DESCENDANTS); // makes long press work
                NextOtpButtonListener clickListener = new NextOtpButtonListener(currentPin);
                buttonView.setOnClickListener(clickListener);
                row.setTag(clickListener);

                countdownIndicator.setVisibility(View.GONE);
            } else { // TOTP, so no button needed
                buttonView.setVisibility(View.GONE);
                buttonView.setOnClickListener(null);
                row.setTag(null);

                countdownIndicator.setVisibility(View.VISIBLE);
                countdownIndicator.setPhase(mTotpCountdownPhase);
                countdownIndicator.setColor(color);
            }

            if (getString(R.string.empty_pin).equals(currentPin.pin)) {
                pinView.setTextScaleX(PIN_TEXT_SCALEX_UNDERSCORE); // smaller gap between underscores
            } else {
                pinView.setTextScaleX(PIN_TEXT_SCALEX_NORMAL);
            }
            pinView.setText(currentPin.pin);
            userView.setText(currentPin.user);
            userView.setTextColor(color);

            return row;
        }
    }

    /**
     * Parameters to the {@link AuthenticatorActivity#DIALOG_ID_SAVE_KEY} dialog.
     */
    private static class SaveKeyDialogParams implements Serializable {
        private final String user;
        private final String secret;
        private final OtpType type;
        private final Integer counter;

        private SaveKeyDialogParams(String user, String secret, OtpType type, Integer counter) {
            this.user = user;
            this.secret = secret;
            this.type = type;
            this.counter = counter;
        }
    }
}
