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
package ru.pnapp.googledrivetest;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.api.Scope;
import com.google.api.services.drive.DriveScopes;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;
import java.util.List;

import ru.pnapp.googledrive.BuildConfig;
import ru.pnapp.googledrive.GoogleDrive;
import ru.pnapp.googledrive.GoogleDriveAndroid;
import ru.pnapp.googledrive.GoogleDriveREST;

public class MainActivity extends AppCompatActivity implements GoogleDrive.Client {
    private static final String TAG = "GoogleDriveTest";
    private static final String[] SCOPES = {
            DriveScopes.DRIVE_APPDATA, DriveScopes.DRIVE_FILE, DriveScopes.DRIVE
    };
    private static final String TEXT = "This file was created by " + BuildConfig.APPLICATION_ID + " just for test.\n" +
            "You may delete it at any time if you like!";

    GoogleDrive drive;

    TextView textView;

    boolean canceled;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        textView = (TextView) findViewById(R.id.text);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (drive != null && drive.activityResultCallback(this, requestCode, resultCode, data)) {
            if (resultCode == RESULT_OK) {
                set(R.id.activity_result, true);
            } else {
                set(R.id.activity_result, false);
                canceled = true;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (canceled) return;

        if (drive == null) {
            new AlertDialog.Builder(this)
                    .setTitle("Select implementation")
                    .setItems(
                            new String[]{"GoogleDriveAndroid", "GoogleDriveREST"},
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    switch (which) {
                                        case 0:
                                            drive = new GoogleDriveAndroid();
                                            break;
                                        case 1:
                                            drive = new GoogleDriveREST();
                                            break;
                                        default:
                                            finish();
                                            return;
                                    }

                                    new AlertDialog.Builder(MainActivity.this)
                                            .setTitle("Select scope")
                                            .setItems(SCOPES, new DialogInterface.OnClickListener() {
                                                @Override
                                                public void onClick(DialogInterface dialog, int which) {
                                                    drive.setScope(new Scope(SCOPES[which]));
                                                    drive.init(MainActivity.this);
                                                    new Thread(test).start();
                                                }
                                            })
                                            .show();
                                }
                    })
                    .show();
        } else {
            new Thread(test).start();
        }
    }

    @Override
    protected void onDestroy() {
        if (drive != null) drive.destroy();
        super.onDestroy();
    }

    void set(final int id, final boolean status, final String toast) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ((TextView) findViewById(id)).setCompoundDrawablesWithIntrinsicBounds(0,0,
                        status ? R.drawable.ic_check_circle_black_24dp : R.drawable.ic_cancel_black_24dp,0);
                if (toast != null) {
                    Toast.makeText(MainActivity.this, toast, Toast.LENGTH_LONG).show();
                }
            }
        });
    }
    void set(final int id, final boolean status) {
        set(id, status, null);
    }

    private boolean testConnect() {
        // Test setEnabled
        GoogleDrive.setEnabled(true);
        if (!GoogleDrive.isEnabled()) {
            set(R.id.set_enabled, false);
            return false;
        }
        GoogleDrive.setEnabled(false);
        if (GoogleDrive.isEnabled()) {
            set(R.id.set_enabled, false);
            return false;
        }
        set(R.id.set_enabled, true);

        // Test connect fails if disabled
        try {
            drive.connect();
            set(R.id.connect_disabled, false);
            return false;
        } catch (IOException e) {
            if(!GoogleDrive.ERROR_NOT_ENABLED.equals(e.getMessage())) {
                set(R.id.connect_disabled, false);
                e.printStackTrace();
                return false;
            }
        }
        set(R.id.connect_disabled, true);

        // Test connect
        GoogleDrive.setEnabled(true);
        try {
            drive.connect();
        } catch (IOException e) {
            set(R.id.connect, false);
            e.printStackTrace();
            return false;
        }

        set(R.id.connect, true);
        return true;
    }

    Runnable test = new Runnable() {
        @Override
        public void run() {
            long startTime = new Date().getTime();

            // Test connection stuff
            if (!testConnect()) return;

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    setTitle(drive.getClass().getSimpleName());
                }
            });

            // Test cd
            try {
                String id = drive.cd(null, BuildConfig.APPLICATION_ID + "/subDir1/subDir2");
                Log.i(TAG, id + " - working folder");
            } catch (IOException e) {
                set(R.id.cd, false);
                e.printStackTrace();
                return;
            }
            set(R.id.cd, true);

            // Test write
            InputStream is = new ByteArrayInputStream(TEXT.getBytes());
            String file1;
            try {
                file1 = drive.write(null, "test1.txt", "text/plain", is);
            } catch (IOException e) {
                set(R.id.write,false);
                e.printStackTrace();
                return;
            } finally {
                try { is.close(); } catch (IOException e) { e.printStackTrace(); }
            }
            set(R.id.write,true);

            // Test stream write
            // 1. Create new empty file
            String file2;
            try {
                file2 = drive.review(null, "test2.txt", "text/plain");
            } catch (IOException e) {
                set(R.id.review_create, false);
                e.printStackTrace();
                return;
            }
            set(R.id.review_create, true);
            // 2. Modify the file with output stream
            OutputStream os = null;
            try {
                os = drive.openOutputStream(file2);
                os.write(TEXT.getBytes());
                try { os.close(); } catch (IOException e) { e.printStackTrace(); }
                drive.commit(file2);
            } catch (IOException e) {
                e.printStackTrace();
                set(R.id.stream_write, false);
                if (os != null) try { os.close(); } catch (IOException e1) { e1.printStackTrace(); }
                return;
            }
            set(R.id.stream_write, true);

            // Test stream read
            is = null;
            try {
                is = drive.openInputStream(file1);
                byte[] bytes = new byte[TEXT.length()];
                int n = is.read(bytes);
                if (n < TEXT.length()) throw new IOException("Wrong data size");
                if (!TEXT.equals(new String(bytes))) throw new IOException("Read data doesn't match");
            } catch (IOException e) {
                set(R.id.stream_read, false);
                e.printStackTrace();
                return;
            } finally {
                if (is != null) try {
                    is.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            set(R.id.stream_read, true);

            // Test ls
            List<String> list;
            try {
                list = drive.ls();
                set(R.id.ls, true, "ls() - found " + list.size() + " files");
            } catch (IOException e) {
                set(R.id.ls,false);
                e.printStackTrace();
                return;
            }

            // Test lastModified
            try {
                for (String id : list) {
                    long t = drive.lastModified(id);
                    Log.i(TAG, id + " " + t);
                    if (file1.equals(id) && Math.abs(t - startTime) > 60000L) throw new IOException("File1 lastModified fails! d = " + (t - startTime));
                    if (file2.equals(id) && Math.abs(t - startTime) > 60000L) throw new IOException("File2 lastModified fails! d = " + (t - startTime));
                }
            } catch (IOException e) {
                set(R.id.last_modofied,false);
                e.printStackTrace();
                return;
            }
            set(R.id.last_modofied,true);

            // Test delete
            int deleted = 0;
            try {
                for (String id : list) {
                    Log.i(TAG, "Delete " + id);
                    drive.delete(id);
                    deleted++;
                }
            } catch (IOException e) {
                set(R.id.delete,false);
                Log.e(TAG,"Delete from Google Drive fails",e);
                return;
            }
            set(R.id.delete,true, "delete(id) - Deleted " + deleted + " files\nAll tests done!");

        }
    };

    @Override
    public void googleDriveConnected() {
        Toast.makeText(this,"Connected to Google Drive!", Toast.LENGTH_SHORT).show();
    }
}
