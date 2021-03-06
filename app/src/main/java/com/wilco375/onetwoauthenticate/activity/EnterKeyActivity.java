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

package com.wilco375.onetwoauthenticate.activity;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;

import com.wilco375.onetwoauthenticate.database.AccountDb;
import com.wilco375.onetwoauthenticate.database.AccountDb.OtpType;
import com.wilco375.onetwoauthenticate.testability.DependencyInjector;
import com.wilco375.onetwoauthenticate.util.Base32String;
import com.wilco375.onetwoauthenticate.util.Base32String.DecodingException;
import com.wilco375.onetwoauthenticate.R;
import com.wilco375.onetwoauthenticate.activity.wizard.WizardPageActivity;

import java.io.Serializable;

/**
 * The activity that lets the user manually add an account by entering its name, key, and type
 * (TOTP/HOTP).
 *
 * @author sweis@google.com (Steve Weis)
 */
public class EnterKeyActivity extends WizardPageActivity<Serializable> implements TextWatcher {
    private static final int MIN_KEY_BYTES = 10;
    private EditText mKeyEntryField;
    private EditText mAccountName;
    private Spinner mType;

    /**
     * Called when the activity is first created
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPageContentView(R.layout.enter_key);

        // Find all the views on the page
        mKeyEntryField = findViewById(R.id.key_value);
        mAccountName = findViewById(R.id.account_name);
        mType = findViewById(R.id.type_choice);

        ArrayAdapter<CharSequence> types = ArrayAdapter.createFromResource(this,
                R.array.type, android.R.layout.simple_spinner_item);
        types.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mType.setAdapter(types);

        // Set listeners
        mKeyEntryField.addTextChangedListener(this);

        mRightButton.setImageDrawable(getResources().getDrawable(R.drawable.ic_check_white_24dp));
    }

    /*
     * Return key entered by user, replacing visually similar characters 1 and 0.
     */
    private String getEnteredKey() {
        String enteredKey = mKeyEntryField.getText().toString();
        return enteredKey.replace('1', 'I').replace('0', 'O');
    }

    /*
     * Verify that the input field contains a valid base32 string,
     * and meets minimum key requirements.
     */
    private boolean validateNameAndKeyAndUpdateStatus(boolean submitting) {
        boolean success = true;

        String userEnteredName = mAccountName.getText().toString();
        if (DependencyInjector.getAccountDb().nameExists(userEnteredName)) {
            mAccountName.setError(submitting ? getString(R.string.error_exists) : null);
            success = false;
        } else {
            mAccountName.setError(null);
        }

        String userEnteredKey = getEnteredKey();
        try {
            byte[] decoded = Base32String.decode(userEnteredKey);
            if (decoded.length < MIN_KEY_BYTES) {
                // If the user is trying to submit a key that's too short, then
                // display a message saying it's too short.
                mKeyEntryField.setError(submitting ? getString(R.string.enter_key_too_short) : null);
                success = false;
            } else {
                mKeyEntryField.setError(null);
            }
        } catch (DecodingException e) {
            mKeyEntryField.setError(getString(R.string.enter_key_illegal_char));
            success = false;
        }
        return success;
    }

    @Override
    protected void onRightButtonPressed() {
        OtpType mode = mType.getSelectedItemPosition() ==
                OtpType.TOTP.value ? OtpType.TOTP : OtpType.HOTP;
        if (validateNameAndKeyAndUpdateStatus(true)) {
            AuthenticatorActivity.saveSecret(this,
                    mAccountName.getText().toString(),
                    getEnteredKey(),
                    null,
                    mode,
                    AccountDb.DEFAULT_HOTP_COUNTER);
            exitWizard();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void afterTextChanged(Editable userEnteredValue) {
        validateNameAndKeyAndUpdateStatus(false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void beforeTextChanged(CharSequence arg0, int arg1, int arg2, int arg3) {
        // Do nothing
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onTextChanged(CharSequence arg0, int arg1, int arg2, int arg3) {
        // Do nothing
    }
}
