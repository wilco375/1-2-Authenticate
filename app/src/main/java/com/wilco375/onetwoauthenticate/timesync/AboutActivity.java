/*
 * Copyright 2012 Google Inc. All Rights Reserved.
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

package com.wilco375.onetwoauthenticate.timesync;

import android.os.Bundle;

import com.wilco375.onetwoauthenticate.R;
import com.wilco375.onetwoauthenticate.activity.wizard.WizardPageActivity;

import java.io.Serializable;

/**
 * Activity that displays more information about the Time Correction/Sync feature.
 *
 * @author klyubin@google.com (Alex Klyubin)
 */
public class AboutActivity extends WizardPageActivity<Serializable> {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setPageContentView(R.layout.timesync_about);
        setTextViewHtmlFromResource(R.id.details, R.string.timesync_about_feature_screen_details);

        setButtonBarModeRightButtonOnly();
        mRightButton.setImageResource(R.drawable.ic_check_white_24dp);
    }

    @Override
    protected void onRightButtonPressed() {
        onBackPressed();
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }
}
