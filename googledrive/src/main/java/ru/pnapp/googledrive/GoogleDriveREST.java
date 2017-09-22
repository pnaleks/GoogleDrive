/*
 *  Copyright 2016 P.N.Alekseev <pnaleks@gmail.com>
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
package ru.pnapp.googledrive;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.WorkerThread;
import android.support.v4.content.ContextCompat;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.InputStreamContent;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GoogleDriveREST extends GoogleDrive {
    private static final String PREF_ACCOUNT_NAME = "accountName";
    private static final int REQUEST_ACCOUNT_NAME = 0x7319;
    private static final int REQUEST_AUTHORIZE = 0x7320;
    private static final int REQUEST_GOOGLE_PLAY_SERVICES = 0x7327;

    @SuppressWarnings("FieldCanBeLocal")
    private static boolean DELETE_PERMANENTLY = true;

    private final HttpTransport httpTransport = AndroidHttp.newCompatibleTransport();
    private final JsonFactory jsonFactory = GsonFactory.getDefaultInstance();

    private String accountName;
    private GoogleAccountCredential credential;
    private Drive drive;
    private Context mContext;

    private String mFolder;

    private Map<String, Content> contentMap = new HashMap<>();

    private class Content {
        String mime;
        String name;
        java.io.File tempFile;

        Content(String name, String mime) {
            this.mime = mime;
            this.name = name;
        }
    }

    @WorkerThread
    synchronized public void connect() throws IOException {
        if (!isEnabled()) throw new IOException(ERROR_NOT_ENABLED);
        if (drive != null) return;

        if (!checkGooglePlayServices()) {
            setEnabled(false);
            throw new IOException("Google Play Services not available");
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                int status = ContextCompat.checkSelfPermission(mContext,Manifest.permission.GET_ACCOUNTS);
                if (status != PackageManager.PERMISSION_GRANTED) {
                    throw new IOException("Access to contacts is required!");
                }
            }

            credential = GoogleAccountCredential.usingOAuth2(mContext, Collections.singleton(mScope.toString()));

            if (accountName == null) {
                setEnabled(false);
                if (mContext instanceof Activity) {
                    ((Activity)mContext).runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            ((Activity)mContext).startActivityForResult(credential.newChooseAccountIntent(), REQUEST_ACCOUNT_NAME);
                        }
                    });
                }
                throw new IOException("Not authorized, context is " + mContext);
            }

            credential.setSelectedAccountName(accountName);

            drive = new Drive.Builder(httpTransport, jsonFactory, credential)
                    .setApplicationName(BuildConfig.APPLICATION_ID)
                    .build();

            File file;
            if (mFolder == null)
                file = drive.files().get("root").execute();
            else
                file = drive.files().get(mFolder).execute();
            mFolder = file.getId();
        } catch (UserRecoverableAuthIOException e) {
            final Intent intent = e.getIntent();
            if (mContext instanceof Activity) {
                ((Activity) mContext).runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        ((Activity) mContext).startActivityForResult(intent, REQUEST_AUTHORIZE);
                    }
                });
            }
        } catch (Exception e) {
            throw new IOException("Connect fails: " + e.getMessage());
        }
    }

    @Override
    public void init(Context context) {
        mContext = context;
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        accountName = preferences.getString(PREF_ACCOUNT_NAME, null);
        drive = null;
    }

    @Override
    public void destroy() {
        for(Content content : contentMap.values()) {
            if (content.tempFile != null) {
                //noinspection ResultOfMethodCallIgnored
                content.tempFile.delete();
            }
        }
    }

    @Override @NonNull
    public String cd(String id, String path) throws IOException {
        connect();
        if (id != null) {
            File file = drive.files().get(id).execute();
            mFolder = file.getId();
            return mFolder;
        }

        if (path == null) throw new FileNotFoundException();

        File file = drive.files().get("root").setFields("id").execute();
        Uri uri = Uri.parse(path);
        boolean seek = true;
        for (String segment : uri.getPathSegments()) {
            if (seek) {
                List<File> fileList = drive.files().list()
                        .setFields("files(id)")
                        .setQ("name='" + segment + "' and mimeType='application/vnd.google-apps.folder' and '" + file.getId() + "' in parents")
                        .execute().getFiles();
                if (fileList != null && fileList.size() > 0) {
                    file = fileList.get(0);
                    continue;
                }
                seek = false;
            }

            File newFile = new File();
            newFile
                    .setName(segment)
                    .setParents(Collections.singletonList(file.getId()))
                    .setMimeType("application/vnd.google-apps.folder");

            file = drive.files().create(newFile).setFields("id").execute();
        }

        mFolder = file.getId();
        return mFolder;
    }


    @Override @NonNull
    public List<String> ls() throws IOException {
        connect();
        List<File> fileList = drive.files().list()
                .setFields("files(id)")
                .setQ("'" + mFolder + "' in parents")
                .execute().getFiles();

        ArrayList<String> result = new ArrayList<>(fileList.size());
        for (File file : fileList) result.add(file.getId());

        return result;
    }

    @Override @NonNull
    public String write(String id, String title, String mimeType, InputStream inputStream) throws IOException {
        //connect(); -- Connect called in review()
        id = review(id, title, mimeType);
        Content content = contentMap.get(id);
        drive.files().update(id, null, new InputStreamContent(content.mime, inputStream)).execute();
        contentMap.remove(id);
        return id;
    }


    @Override @NonNull
    public String review(String id, String title, String mimeType) throws IOException {
        connect();

        if (contentMap.containsKey(id)) {
            if (contentMap.get(id).tempFile != null) throw new IOException("Resource busy");
            return id;
        }

        if (id != null) {
            try {
                File file = drive.files().get(id).setFields("id, mimeType, name").execute();
                Content content = new Content(
                        (title == null) ? file.getName() : title,
                        (mimeType == null) ? file.getMimeType() : mimeType
                );
                contentMap.put(id, content);
                return id;
            } catch (IOException ignore) {}
        }

        File mediaContent = new File()
                .setName(title)
                .setMimeType(mimeType)
                .setParents(Collections.singletonList(mFolder));
        File file = drive.files().create(mediaContent).execute();

        id = file.getId();
        contentMap.put(id, new Content(title, mimeType));

        return id;
    }

    @Override
    public void commit(String id) throws IOException {
        Content content = contentMap.get(id);
        if (content != null) {
            if (content.tempFile != null) {
                FileContent fileContent = new FileContent(content.mime, content.tempFile);
                drive.files().update(id, null, fileContent).execute();
                //noinspection ResultOfMethodCallIgnored
                content.tempFile.delete();
            }
            contentMap.remove(id);
        }
    }

    @Override
    public void close(String id) throws IOException {
        Content content = contentMap.get(id);
        if (content != null) {
            if (content.tempFile != null) {
                //noinspection ResultOfMethodCallIgnored
                content.tempFile.delete();
            }
            contentMap.remove(id);
        }
    }

    @Override @NonNull
    public InputStream openInputStream(String id) throws IOException {
        connect();
        return  drive.files().get(id).executeMediaAsInputStream();
    }


    @Override @NonNull
    public OutputStream openOutputStream(String id) throws IOException {
        connect();
        Content content = contentMap.get(id);
        if (content == null) throw new IOException("Call review(id, name, mimeType) first!");
        if (content.tempFile != null) throw new IOException("Resource busy");
        content.tempFile = java.io.File.createTempFile(
                Long.toHexString(new Date().getTime()), null, mContext == null ? null : mContext.getCacheDir());
        return new FileOutputStream(content.tempFile);
    }

    @Override
    public long lastModified(String id) throws IOException {
        connect();
        File file = drive.files().get(id).setFields("modifiedTime").execute();
        return file.getModifiedTime().getValue();
    }

    @Override
    public void delete(String id) throws IOException {
        connect();
        if (DELETE_PERMANENTLY) {
            drive.files().delete(id).execute();
        } else {
            drive.files().update(id, new File().setTrashed(true)).setFields("trashed").execute();
        }
    }

    @Override
    public boolean activityResultCallback(Activity activity, int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_GOOGLE_PLAY_SERVICES) {
            if (resultCode == Activity.RESULT_OK) {
                startConnect();
            } else {
                new AlertDialog.Builder(activity)
                        .setMessage("Для использования Google Disk необходимо установить Google Play Services!")
                        .show();
            }
            return true;
        }
        if (requestCode == REQUEST_ACCOUNT_NAME) {
            if (resultCode == Activity.RESULT_OK) {
                mContext = activity;
                accountName = data.getStringExtra("authAccount");
                drive = null;
                Log.i("PNApp", "NEW ACCOUNT NAME IS: " + accountName);
                if (accountName != null) {
                    PreferenceManager.getDefaultSharedPreferences(activity).edit()
                            .putString(PREF_ACCOUNT_NAME, accountName)
                            .apply();
                    startConnect();
                }
            }
            return true;
        }
        if (requestCode == REQUEST_AUTHORIZE) {
            if (resultCode == Activity.RESULT_OK) startConnect();
        }
        return false;
    }

    private void startConnect() {
        setEnabled(true);
        Runnable task = new Runnable() {
            @Override
            public void run() {
                try { connect(); } catch (Exception ignore) {}
            }
        };
        new Thread(task).start();
    }

    private boolean checkGooglePlayServices() {
        GoogleApiAvailability apiAvailability = GoogleApiAvailability.getInstance();
        final int result = apiAvailability.isGooglePlayServicesAvailable(mContext);
        if (result == ConnectionResult.SUCCESS) return true;
        if (mContext instanceof Activity && apiAvailability.isUserResolvableError(result)) {
            Dialog dialog = apiAvailability.getErrorDialog(
                    (Activity) mContext, result, REQUEST_GOOGLE_PLAY_SERVICES);
            dialog.show();
        }
        return false;
    }
}
